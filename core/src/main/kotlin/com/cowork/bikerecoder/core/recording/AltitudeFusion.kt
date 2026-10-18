package com.cowork.bikerecoder.core.recording

import kotlin.math.abs
import kotlin.math.pow

object Barometric {
    const val SEA_LEVEL_HPA = 1013.25

    /** SensorManager.getAltitude와 같은 식: 44330 × (1 − (p / 1013.25)^(1 / 5.255)). */
    fun altitudeM(pressureHpa: Double): Double =
        44330.0 * (1.0 - (pressureHpa / SEA_LEVEL_HPA).pow(1.0 / 5.255))
}

enum class AltitudeMode { NONE, GPS, BAROMETER }

/**
 * 기압 고도 + GPS 보정, 기압계가 없거나 끊기면 GPS 고도 평활.
 *
 * 시각은 호출자가 주는 [onPressure]/[onGps]의 nowMillis뿐이다(시계를 따로 읽지 않는다).
 * [mode]는 가장 최근에 받은 nowMillis를 기준으로 기압 표본이 신선한지 판정하므로,
 * 호출자가 기압·GPS를 주기적으로 넣어 줘야 전환이 일어난다.
 */
class AltitudeFusion(
    private val baroWindowMs: Long = 5_000,
    private val maxVerticalAccuracyM: Float = 10f,
    private val initialCalibrationMs: Long = 60_000,
    private val recalibrationMs: Long = 300_000,
    private val emaAlpha: Double = 0.1,
    private val baroStaleMs: Long = 10_000,
    private val gpsMedianSize: Int = 5,
    private val gpsSmoothingAlpha: Double = 0.25,
) {
    private class Sample(val timeMs: Long, val altitudeM: Double)

    private val baroSamples = ArrayDeque<Sample>()
    private var baroAltitude: Double? = null
    private var lastPressureMs: Long? = null
    private var lastNowMs: Long = Long.MIN_VALUE

    private val recentGps = ArrayDeque<Double>()
    private var gpsSmoothed: Double? = null

    // 보정: 첫 차이 이후 initialCalibrationMs 동안은 지금까지 차이의 중간값을 실시간 오프셋으로,
    // 그 뒤에는 고정하고 recalibrationMs마다 창의 중간값으로 EMA 갱신.
    private var offset: Double? = null
    private var firstDiffMs: Long? = null
    private var initialFrozen = false
    private val windowDiffs = ArrayList<Double>()
    private var recalWindowStartMs = 0L

    val mode: AltitudeMode
        get() {
            val pressureMs = lastPressureMs
            return when {
                pressureMs != null && lastNowMs - pressureMs <= baroStaleMs -> AltitudeMode.BAROMETER
                gpsSmoothed != null -> AltitudeMode.GPS
                else -> AltitudeMode.NONE
            }
        }

    /** 화면·저장용 해발 고도. 기압 모드에서 오프셋이 아직 없으면 null. */
    val altitudeM: Double?
        get() = when (mode) {
            AltitudeMode.BAROMETER -> {
                val baro = baroAltitude
                val off = offset
                if (baro != null && off != null) baro + off else null
            }
            AltitudeMode.GPS -> gpsSmoothed
            AltitudeMode.NONE -> null
        }

    /** 누적 상승 계산용. 기압 모드는 보정 전 기압 고도라서 오프셋 변화가 상승으로 잡히지 않는다. */
    val climbAltitudeM: Double?
        get() = when (mode) {
            AltitudeMode.BAROMETER -> baroAltitude
            AltitudeMode.GPS -> gpsSmoothed
            AltitudeMode.NONE -> null
        }

    val climbThresholdM: Double
        get() = if (mode == AltitudeMode.BAROMETER) BARO_CLIMB_THRESHOLD_M else GPS_CLIMB_THRESHOLD_M

    fun onPressure(pressureHpa: Double, nowMillis: Long) {
        advance(nowMillis)
        baroSamples.addLast(Sample(nowMillis, Barometric.altitudeM(pressureHpa)))
        while (baroSamples.isNotEmpty() && baroSamples.first().timeMs <= nowMillis - baroWindowMs) {
            baroSamples.removeFirst()
        }
        baroAltitude = baroSamples.map { it.altitudeM }.average()
        lastPressureMs = nowMillis
    }

    fun onGps(altitudeM: Double, verticalAccuracyM: Float?, nowMillis: Long) {
        advance(nowMillis)
        recentGps.addLast(altitudeM)
        while (recentGps.size > gpsMedianSize) recentGps.removeFirst()
        val median = median(recentGps)
        gpsSmoothed = gpsSmoothed?.let { it + gpsSmoothingAlpha * (median - it) } ?: median

        val baro = baroAltitude
        val pressureMs = lastPressureMs
        val baroFresh = pressureMs != null && nowMillis - pressureMs <= baroStaleMs
        if (baro != null && baroFresh && verticalAccuracyM != null && verticalAccuracyM <= maxVerticalAccuracyM) {
            addDifference(altitudeM - baro, nowMillis)
        }
    }

    private fun addDifference(diff: Double, nowMs: Long) {
        if (firstDiffMs == null) {
            firstDiffMs = nowMs
            recalWindowStartMs = nowMs + initialCalibrationMs
        }
        windowDiffs.add(diff)
        if (!initialFrozen) offset = median(windowDiffs)
    }

    /** 시간이 흐르면서 생기는 상태 변화: 초기 보정 고정, 5분 재보정. 새 표본을 넣기 전에 부른다. */
    private fun advance(nowMs: Long) {
        if (nowMs > lastNowMs) lastNowMs = nowMs
        val first = firstDiffMs ?: return
        if (!initialFrozen) {
            if (nowMs - first < initialCalibrationMs) return
            initialFrozen = true
            offset = median(windowDiffs)       // 초기 60초 동안 모은 차이
            windowDiffs.clear()
            return
        }
        if (nowMs - recalWindowStartMs >= recalibrationMs) {
            val current = offset
            if (windowDiffs.isNotEmpty() && current != null) {
                offset = current + emaAlpha * (median(windowDiffs) - current)
            }
            windowDiffs.clear()
            // 표본 없이 여러 창이 지나갔어도 창 경계를 유지한다.
            recalWindowStartMs += ((nowMs - recalWindowStartMs) / recalibrationMs) * recalibrationMs
        }
    }

    private fun median(values: Collection<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private companion object {
        const val BARO_CLIMB_THRESHOLD_M = 3.0
        const val GPS_CLIMB_THRESHOLD_M = 5.0
    }
}

/**
 * 누적 상승·하강(히스테리시스). 기준점 대비 |차| ≥ threshold일 때만 그 차를 누적하고 기준점을 옮긴다.
 * 첫 고도는 기준점이 될 뿐 누적하지 않는다.
 */
class AscentCounter {
    var ascentM: Double = 0.0
        private set
    var descentM: Double = 0.0
        private set
    private var reference: Double? = null

    fun onAltitude(altitudeM: Double, thresholdM: Double) {
        val ref = reference
        if (ref == null) {
            reference = altitudeM
            return
        }
        val diff = altitudeM - ref
        if (abs(diff) < thresholdM) return
        if (diff > 0) ascentM += diff else descentM -= diff
        reference = altitudeM
    }

    /** 다음 고도를 새 기준점으로(일시정지 후 재개, 모드 전환). */
    fun rebase() {
        reference = null
    }
}

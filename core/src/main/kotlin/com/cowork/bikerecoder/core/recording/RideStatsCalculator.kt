package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.LocationFix

/**
 * 기록 중 상태의 채택 위치만 받아 거리·이동 시간·속도를 누적한다.
 * 연속한 두 위치의 간격 dt가 [gpsGapMs] 이하면 거리와 dt를 더하고, 넘으면 그 구간 평균 속도가
 * [minGapSpeedMps] 이상일 때만 더한다(터널). 아니면 둘 다 더하지 않는다.
 */
class RideStatsCalculator(
    private val gpsGapMs: Long = 10_000,
    private val minGapSpeedMps: Double = 3.0 / 3.6,
    private val windowMs: Long = 3_000,
) {
    var distanceM: Double = 0.0
        private set
    var movingTimeMs: Long = 0L
        private set

    /** 위치마다 구한 3초 창 속도 중간값의 최댓값. */
    var maxSpeedMps: Double = 0.0
        private set

    /** (t − windowMs, t] 안 속도들의 평균. */
    var currentSpeedMps: Double = 0.0
        private set

    /** distanceM / 이동 시간(초), 이동 시간 0이면 0. */
    val avgSpeedMps: Double
        get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0

    private var prev: LocationFix? = null
    private val window = ArrayDeque<Pair<Long, Double>>()

    fun add(fix: LocationFix, speedMps: Double) {
        val p = prev
        if (p != null) {
            val dtMs = fix.timeMillis - p.timeMillis
            if (dtMs > 0) {
                val segM = GeoMath.distanceM(p.point, fix.point)
                if (dtMs <= gpsGapMs || segM / (dtMs / 1000.0) >= minGapSpeedMps) {
                    distanceM += segM
                    movingTimeMs += dtMs
                }
            }
        }
        prev = fix

        val t = fix.timeMillis
        window.addLast(t to speedMps)
        while (window.isNotEmpty() && window.first().first <= t - windowMs) window.removeFirst()

        currentSpeedMps = window.sumOf { it.second } / window.size
        if (window.size >= 2) {
            val sorted = window.map { it.second }.sorted()
            val median = sorted[(sorted.size - 1) / 2] // 짝수 개면 아래쪽 중간값
            if (median > maxSpeedMps) maxSpeedMps = median
        }
    }

    /** 일시정지·재개·재기준: 다음 위치와 거리·시간을 잇지 않고 속도 창도 비운다. */
    fun breakChain() {
        prev = null
        window.clear()
        currentSpeedMps = 0.0
    }
}

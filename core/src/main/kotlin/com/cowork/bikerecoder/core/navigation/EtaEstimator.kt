package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix

/**
 * 최근 [windowMs] 동안의 이동 구간을 바탕으로 평균 이동 속도를 추정하고,
 * 남은 거리에 대한 도착 예정 시각(ETA)을 계산한다.
 *
 * 구간 속도는 연속된 두 위치 사이의 거리/시간으로 계산한다(`LocationFix.speedMps`는 사용하지 않음).
 * 구간 속도가 [stopSpeedMps] 미만이면 그 구간은 시간/거리 계산에서 제외한다(정지 구간).
 * 창(window) 안에서 "이동 중"으로 인정된 시간의 합이 [minDataMs] 미만이면 [defaultSpeedMps]를 기본값으로 사용한다.
 * 정확도가 [MAX_ACCURACY_M]을 초과하는 위치는 버린다.
 */
class EtaEstimator(
    private val windowMs: Long = 15 * 60_000L,
    private val minDataMs: Long = 5 * 60_000L,
    private val defaultSpeedMps: Double = 15.0 / 3.6,
    private val stopSpeedMps: Double = 2.0 / 3.6,
) {

    private companion object {
        const val MAX_ACCURACY_M = 30f
    }

    /** 시간 오름차순으로 유지되는 최근 위치 기록. 창 밖으로 벗어난 항목은 onFix에서 정리한다. */
    private val fixes = ArrayDeque<LocationFix>()

    fun onFix(fix: LocationFix) {
        if (fix.accuracyM > MAX_ACCURACY_M) return

        fixes.addLast(fix)

        // 메모리를 창 크기로 한정한다. 다음 구간 계산에 필요한 "경계 바로 앞" 한 건은 남겨둔다.
        val cutoff = fix.timeMillis - windowMs
        while (fixes.size >= 2 && fixes[1].timeMillis <= cutoff) {
            fixes.removeFirst()
        }
    }

    /** 창(window) 안의 이동 구간들로부터 평균 이동 속도를 구한다. 데이터가 부족하면 기본 속도를 반환한다. */
    fun baseSpeedMps(nowMillis: Long): Double {
        val cutoff = nowMillis - windowMs

        var movingTimeMs = 0L
        var movingDistanceM = 0.0

        for (i in 1 until fixes.size) {
            val prev = fixes[i - 1]
            val cur = fixes[i]

            // 구간의 종료 시각이 창(now - windowMs, now] 안에 있어야 포함한다.
            if (cur.timeMillis <= cutoff) continue

            val dtMs = cur.timeMillis - prev.timeMillis
            if (dtMs <= 0) continue

            val distanceM = GeoMath.distanceM(prev.point, cur.point)
            val segmentSpeedMps = distanceM / (dtMs / 1_000.0)
            if (segmentSpeedMps < stopSpeedMps) continue

            movingTimeMs += dtMs
            movingDistanceM += distanceM
        }

        if (movingTimeMs < minDataMs) return defaultSpeedMps
        return movingDistanceM / (movingTimeMs / 1_000.0)
    }

    /** 남은 거리 [remainingM]을 현재 추정 속도로 나눠 도착 예정 시각(epoch millis)을 구한다. */
    fun etaMillis(nowMillis: Long, remainingM: Double): Long {
        val speedMps = baseSpeedMps(nowMillis)
        return nowMillis + (remainingM / speedMps * 1_000.0).toLong()
    }
}

/**
 * 주행 거리를 누적한다. 정확도가 낮은([maxAccuracyM] 초과) 위치는 버리고,
 * 남은 위치들 사이의 거리를 단순히 합산한다. 시각(날짜 경계 포함)과는 무관하게 세션 단위로 계속 누적된다.
 */
class Odometer(private val maxAccuracyM: Float = 30f, initialDistanceM: Double = 0.0) {

    private var lastPoint: GeoPoint? = null

    /** [initialDistanceM](이어서 안내할 때 이미 이동한 거리)부터 누적한다. */
    var distanceM: Double = initialDistanceM
        private set

    fun onFix(fix: LocationFix) {
        if (fix.accuracyM > maxAccuracyM) return

        val previous = lastPoint
        if (previous != null) {
            distanceM += GeoMath.distanceM(previous, fix.point)
        }
        lastPoint = fix.point
    }
}

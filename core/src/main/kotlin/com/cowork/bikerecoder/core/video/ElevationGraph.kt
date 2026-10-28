package com.cowork.bikerecoder.core.video

import kotlin.math.max

/**
 * 숫자 띠 위의 고도 그래프(스펙 §3.4). [xs] = 경로 거리 비율(0~1, 구간 사이 이동은 0으로 센다), [ys] = 높이 비율(0~1, 1 = 위).
 * 고도가 있는 점만 담는다.
 */
class ElevationGraph(val xs: DoubleArray, val ys: DoubleArray) {

    /** [located]까지 지나온 경로 거리의 비율(0~1). xs와 같은 거리를 쓴다. */
    fun doneFraction(located: Located, timeline: RideTimeline): Double {
        val distance = timeline.routeDistance
        val total = distance.cumulative.lastOrNull() ?: return 0.0
        if (total <= 0.0) return 0.0
        return (distance.at(located) / total).coerceIn(0.0, 1.0)
    }

    companion object {
        /** 세로 범위의 최소(m). */
        const val MIN_RANGE_M = 40.0

        /** 가장 낮은 점 아래 여유(m). */
        const val FLOOR_MARGIN_M = 4.0

        /** 고도가 있는 점이 2개 미만이면 null. */
        fun of(timeline: RideTimeline): ElevationGraph? {
            val withAltitude = timeline.points.indices.filter { timeline.points[it].altitudeM != null }
            if (withAltitude.size < 2) return null
            val cumulative = timeline.routeDistance.cumulative
            val total = cumulative.last()
            val altitudes = DoubleArray(withAltitude.size) { timeline.points[withAltitude[it]].altitudeM!! }
            val low = altitudes.min()
            // 실제 범위가 40 m보다 작으면 40 m로 잡고, 맨 위 점이 잘리지 않도록 바닥 여유를 높이에 더한다.
            val height = max(altitudes.max() - low, MIN_RANGE_M) + FLOOR_MARGIN_M
            val xs = DoubleArray(withAltitude.size) {
                if (total > 0.0) (cumulative[withAltitude[it]] / total).coerceIn(0.0, 1.0) else it.toDouble() / withAltitude.lastIndex
            }
            val ys = DoubleArray(withAltitude.size) { ((altitudes[it] - low + FLOOR_MARGIN_M) / height).coerceIn(0.0, 1.0) }
            return ElevationGraph(xs, ys)
        }
    }
}

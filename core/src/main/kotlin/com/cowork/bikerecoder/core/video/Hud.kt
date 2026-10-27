package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.post.HeartRateSample

/** 숫자 띠 한 장의 값. [altitudeM]·[heartBpm]이 null이면 그 칸은 생략한다. */
data class HudValues(val distanceKm: Double, val speedKmh: Double, val altitudeM: Double?, val heartBpm: Int?)

/** 주행 장면 숫자 띠(스펙 §2.2). */
object Hud {

    /** 속도 평균 창: 이동 시각 앞뒤 각각 10초. */
    const val SPEED_WINDOW_MS = 10_000L

    /**
     * 이동 시각 [moveMs]의 지금까지 거리, 속도(앞뒤 10초 이동 시간의 거리 ÷ 시간, 경로 밖은 잘라 낸 창), 고도,
     * 심박(그 실제 시각 ±30초 안 가장 가까운 샘플, [HeartRateColor] 규칙). [heartRate]는 시간순.
     */
    fun at(moveMs: Long, timeline: RideTimeline, heartRate: List<HeartRateSample>): HudValues {
        val route = RouteDistance.of(timeline)
        val located = timeline.at(moveMs)
        val from = (moveMs - SPEED_WINDOW_MS).coerceAtLeast(0L)
        val to = (moveMs + SPEED_WINDOW_MS).coerceAtMost(timeline.totalMoveMs)
        val speedMps = if (to > from) (route.atMove(to) - route.atMove(from)) / ((to - from) / 1000.0) else 0.0
        return HudValues(
            distanceKm = route.at(located) / 1000.0,
            speedKmh = speedMps * 3.6,
            altitudeM = located.altitudeM,
            heartBpm = HeartRateColor.matchedBpm(heartRate, timeAt(timeline, located)),
        )
    }

    /** 보간 위치의 실제 시각(epoch ms). */
    private fun timeAt(timeline: RideTimeline, located: Located): Long {
        val a = timeline.points[located.doneIndex]
        val b = timeline.points.getOrNull(located.doneIndex + 1) ?: return a.timeMillis
        return a.timeMillis + ((b.timeMillis - a.timeMillis) * located.fraction).toLong()
    }
}

/**
 * 경로를 따라 처음부터 잰 거리(m). 구간이 바뀌는 곳(다른 구간의 첫 지점으로 옮겨 감)의 거리는 세지 않는다.
 * 프레임마다 다시 재지 않도록 마지막 경로 하나의 결과를 둔다.
 */
internal class RouteDistance private constructor(private val timeline: RideTimeline) {

    private val points = timeline.points

    /** 지점별 누적 거리(m). */
    val cumulative: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) {
            val step = if (points[i].segmentIndex == points[i - 1].segmentIndex) GeoMath.distanceM(points[i - 1].point, points[i].point) else 0.0
            cum[i] = cum[i - 1] + step
        }
    }

    fun at(located: Located): Double {
        val i = located.doneIndex
        if (i >= points.lastIndex || located.fraction == 0.0) return cumulative[i]
        return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * located.fraction
    }

    fun atMove(moveMs: Long): Double = at(timeline.at(moveMs))

    companion object {
        @Volatile private var last: RouteDistance? = null

        fun of(timeline: RideTimeline): RouteDistance =
            last?.takeIf { it.timeline === timeline } ?: RouteDistance(timeline).also { last = it }
    }
}

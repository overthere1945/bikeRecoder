package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.AscentCounter
import kotlin.math.roundToLong

/**
 * 숫자 띠 한 장의 값. [altitudeM]·[heartBpm]이 null이면 그 칸은 생략한다.
 * [elapsedMs]는 그 구간의 첫 지점에서 지금까지 흐른 실제 시간(쉰 시간 포함, 등산의 산행 시간),
 * [ascentM]은 그 구간의 첫 지점에서 지금까지의 누적 상승(등산의 누적 상승, [HikeProgress]).
 */
data class HudValues(
    val distanceKm: Double,
    val speedKmh: Double,
    val altitudeM: Double?,
    val heartBpm: Int?,
    val elapsedMs: Long,
    val ascentM: Double,
)

/** 주행 장면 숫자 띠(스펙 §2.2). */
object Hud {

    /** 속도 평균 창: 이동 시각 앞뒤 각각 10초. */
    const val SPEED_WINDOW_MS = 10_000L

    /**
     * 이동 시각 [moveMs]의 지금까지 거리, 속도(앞뒤 10초 이동 시간의 거리 ÷ 시간, 경로 밖은 잘라 낸 창), 고도,
     * 심박(그 실제 시각 ±30초 안 가장 가까운 샘플, [HeartRateColor] 규칙). [heartRate]는 시간순.
     * 산행 시간과 누적 상승은 그 지점이 속한 구간의 처음부터 센다(스펙 P11).
     */
    fun at(moveMs: Long, timeline: RideTimeline, heartRate: List<HeartRateSample>): HudValues {
        val route = timeline.routeDistance
        val located = timeline.at(moveMs)
        val from = (moveMs - SPEED_WINDOW_MS).coerceAtLeast(0L)
        val to = (moveMs + SPEED_WINDOW_MS).coerceAtMost(timeline.totalMoveMs)
        val speedMps = if (to > from) (route.atMove(to) - route.atMove(from)) / ((to - from) / 1000.0) else 0.0
        val time = timeAt(timeline, located)
        val hike = timeline.hikeProgress
        return HudValues(
            distanceKm = route.at(located) / 1000.0,
            speedKmh = speedMps * 3.6,
            altitudeM = located.altitudeM,
            heartBpm = HeartRateColor.matchedBpm(heartRate, time),
            elapsedMs = (time - hike.segmentStartMillis(located.doneIndex)).coerceAtLeast(0L),
            ascentM = hike.ascentAt(located),
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
 * 프레임마다 다시 재지 않도록 경로마다 한 번만 만든다([RideTimeline.routeDistance]).
 */
internal class RouteDistance(private val timeline: RideTimeline) {

    private val points = timeline.points

    /** 지점별 누적 거리(m). */
    val cumulative: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) {
            val step = if (points[i].segmentIndex == points[i - 1].segmentIndex) GeoMath.distanceM(points[i - 1].point, points[i].point) else 0.0
            cum[i] = cum[i - 1] + step
        }
    }

    /** 경로 전체 거리(m). 지점이 없으면 0. */
    val total: Double = cumulative.lastOrNull() ?: 0.0

    fun at(located: Located): Double {
        val i = located.doneIndex
        if (i >= points.lastIndex || located.fraction == 0.0) return cumulative[i]
        return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * located.fraction
    }

    fun atMove(moveMs: Long): Double = at(timeline.at(moveMs))

    /**
     * 거리가 [alongM]에 처음 닿는 이동 시각([atMove]의 역, 스펙 §2.1). [alongM]은 0~[total]로 자르고, 두 지점 사이는 직선으로 보간한다.
     * 같은 거리에 머문 지점(일시정지·구간 사이)은 그 처음 지점의 이동 시각이다.
     */
    fun moveAt(alongM: Double): Long {
        if (points.isEmpty()) return 0L
        val d = alongM.coerceIn(0.0, total)
        // 누적 거리가 d 이상인 첫 지점(그 앞 지점은 d 미만이므로 그 사이 거리는 0보다 크다).
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] >= d) hi = mid else lo = mid + 1
        }
        if (lo == 0) return points[0].moveMs
        val a = points[lo - 1]
        val b = points[lo]
        val f = (d - cumulative[lo - 1]) / (cumulative[lo] - cumulative[lo - 1])
        return a.moveMs + ((b.moveMs - a.moveMs) * f).roundToLong()
    }
}

/**
 * 구간마다 처음부터 센 누적 상승(스펙 P11). 고도가 있는 지점을 차례로 [AscentCounter]에 넣어 미리 구해 두고, 프레임마다 다시 세지 않는다.
 * 구간이 바뀌면 다시 센다(`rebase`가 아니라 0부터: 산행 시간이 구간 첫 지점부터인 것과 같은 기준). 고도 없는 지점은 건너뛴다.
 * 문턱은 [CLIMB_THRESHOLD_M]: 기압계 기록의 기준(3 m, `AltitudeFusion`)이다.
 */
internal class HikeProgress(private val timeline: RideTimeline) {

    private val points = timeline.points

    /** 지점마다 그 지점이 속한 구간의 첫 지점 인덱스. */
    private val segmentFirst = IntArray(points.size).also { first ->
        for (i in points.indices) first[i] = if (i > 0 && points[i].segmentIndex == points[i - 1].segmentIndex) first[i - 1] else i
    }

    /** 지점별 그 구간 처음부터의 누적 상승(m). */
    private val ascent = DoubleArray(points.size).also { out ->
        var counter = AscentCounter()
        for (i in points.indices) {
            if (segmentFirst[i] == i) counter = AscentCounter()
            points[i].altitudeM?.let { counter.onAltitude(it, CLIMB_THRESHOLD_M) }
            out[i] = counter.ascentM
        }
    }

    /** 인덱스 [index] 지점이 속한 구간의 첫 지점 시각(epoch ms). */
    fun segmentStartMillis(index: Int): Long = points[segmentFirst[index]].timeMillis

    /** [located]까지의 누적 상승. 같은 구간 안의 두 지점 사이는 직선으로 보탠다. */
    fun ascentAt(located: Located): Double {
        val i = located.doneIndex
        if (i >= points.lastIndex || located.fraction == 0.0 || points[i + 1].segmentIndex != points[i].segmentIndex) return ascent[i]
        return ascent[i] + (ascent[i + 1] - ascent[i]) * located.fraction
    }

    companion object {
        const val CLIMB_THRESHOLD_M = 3.0
    }
}

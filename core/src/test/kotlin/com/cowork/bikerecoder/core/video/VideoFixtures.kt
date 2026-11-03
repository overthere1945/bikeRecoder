package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.north
import java.time.LocalDate
import java.time.ZoneId

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

fun segment(id: Long, startedAt: Long, date: LocalDate = LocalDate.of(2026, 10, 17)) = RideSegment(
    id = id,
    tripId = 1,
    startedAt = startedAt,
    endedAt = startedAt + 1,
    localDate = date,
    totals = SegmentTotals.ZERO.copy(distanceM = 100.0),
)

fun trackPoint(timeMillis: Long, northM: Double, altitudeM: Double? = null) =
    TrackPoint(timeMillis, north(northM), altitudeM = altitudeM, speedMps = null, accuracyM = 5f)

/** [startMillis]부터 [stepMs] 간격으로 [count]개, [startM]부터 한 점에 [stepM]미터씩 북쪽으로. */
fun straightPoints(startMillis: Long, count: Int, stepMs: Long = 1_000, startM: Double = 0.0, stepM: Double = 5.0) =
    List(count) { trackPoint(startMillis + it * stepMs, startM + it * stepM) }

/** 이동 시간이 [moveSeconds]초인 한 구간짜리 여정. */
fun simpleTimeline(moveSeconds: Int): RideTimeline =
    RideTimelineBuilder.build(listOf(segment(1, 0) to straightPoints(0, moveSeconds + 1)), SEOUL)

/** 기준점에서 북쪽 [northM], 동쪽 [eastM]미터(짧은 거리의 평면 근사, [north]와 같은 기준점). */
fun pointAt(northM: Double, eastM: Double): GeoPoint {
    val base = north(northM)
    return GeoPoint(base.lat, base.lon + Math.toDegrees(eastM / (6_371_008.8 * Math.cos(Math.toRadians(base.lat)))))
}

/** (북, 동) 미터 꺾은점 [corners]를 차례로 [stepM]미터씩, 한 점에 1초씩 걷는 지점들(변 길이는 [stepM]의 배수). */
fun pathPoints(corners: List<Pair<Double, Double>>, stepM: Double = 5.0): List<TrackPoint> {
    val out = mutableListOf(TrackPoint(0, pointAt(corners[0].first, corners[0].second), null, null, 5f))
    for ((a, b) in corners.zipWithNext()) {
        val steps = Math.round(Math.hypot(b.first - a.first, b.second - a.second) / stepM).toInt()
        for (k in 1..steps) {
            val f = k.toDouble() / steps
            val p = pointAt(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f)
            out += TrackPoint(out.size * 1_000L, p, null, null, 5f)
        }
    }
    return out
}

/** 한 점에 1초, [stepM]미터씩 북쪽으로 가며 고도가 [altitudes]인 지점들. */
fun altitudePoints(altitudes: List<Double?>, stepM: Double = 5.0) =
    altitudes.mapIndexed { i, a -> trackPoint(i * 1_000L, i * stepM, a) }

/** [points]로 된 한 구간짜리 여정. */
fun timelineOf(points: List<TrackPoint>): RideTimeline = RideTimelineBuilder.build(listOf(segment(1, 0) to points), SEOUL)

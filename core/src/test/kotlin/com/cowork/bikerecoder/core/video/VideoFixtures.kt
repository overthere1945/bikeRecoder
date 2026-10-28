package com.cowork.bikerecoder.core.video

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

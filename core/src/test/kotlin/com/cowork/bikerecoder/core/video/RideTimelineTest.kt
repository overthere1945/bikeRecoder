package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.recording.north
import java.time.LocalDate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideTimelineTest {

    @Test
    fun pausesAndGapsExcluded() {
        // 구간 1: 0~10초 이동, 60초 쉼(점 간격 60초), 70~80초 이동 → 이동 20초.
        val first = straightPoints(0, 11) + straightPoints(70_000, 11, startM = 60.0)
        // 구간 2: 한 시간 뒤 시작, 10초 이동.
        val second = straightPoints(3_600_000, 11, startM = 200.0)
        val timeline = RideTimelineBuilder.build(
            listOf(segment(1, 0) to first, segment(2, 3_600_000) to second),
            SEOUL,
        )
        assertEquals(30_000L, timeline.totalMoveMs)
        assertEquals(20_000L, timeline.moveMsAtTime(80_000))
        assertEquals(10_000L, timeline.moveMsAtTime(40_000)) // 쉬는 동안 찍은 사진은 멈춘 지점
        assertEquals(20_000L, timeline.moveMsAtTime(1_800_000)) // 구간 사이
        assertEquals(25_000L, timeline.moveMsAtTime(3_605_000))
        assertNull(timeline.moveMsAtTime(-1))
        assertNull(timeline.moveMsAtTime(3_610_001))
        assertEquals(1, timeline.at(25_000).segmentIndex)
    }

    @Test
    fun tenSecondGapStillCounts() {
        val points = listOf(trackPoint(0, 0.0), trackPoint(10_000, 50.0), trackPoint(20_001, 100.0))
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to points), SEOUL)
        assertEquals(10_000L, timeline.totalMoveMs)
    }

    @Test
    fun interpolatesPositionAndAltitude() {
        val points = listOf(trackPoint(0, 0.0, altitudeM = 10.0), trackPoint(10_000, 100.0, altitudeM = 30.0))
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to points), SEOUL)
        val mid = timeline.at(2_500)
        assertEquals(north(25.0).lat, mid.point.lat, 1e-9)
        assertEquals(15.0, mid.altitudeM!!, 1e-9)
        assertEquals(0, mid.doneIndex)
        assertEquals(0.25, mid.fraction, 1e-9)

        val end = timeline.at(99_999)
        assertEquals(1, end.doneIndex)
        assertEquals(north(100.0).lat, end.point.lat, 1e-9)
        assertEquals(0, timeline.at(-5).doneIndex)
    }

    @Test
    fun dayIndexFollowsLocalDateAndCaptionsMarkLaterDays() {
        val day1 = LocalDate.of(2026, 10, 17)
        val day2 = LocalDate.of(2026, 10, 18)
        // 2026-10-18 08:00 KST
        val morning = 1_792_278_000_000L
        val timeline = RideTimelineBuilder.build(
            listOf(
                segment(2, morning, day2) to straightPoints(morning, 11),
                segment(1, 0, day1) to straightPoints(0, 21),
            ),
            SEOUL,
        )
        assertEquals(0, timeline.at(5_000).dayIndex)
        assertEquals(1, timeline.at(25_000).dayIndex)
        assertEquals(listOf(20_000L to "2일째 · 10월 18일"), timeline.dayStarts)
    }

    @Test
    fun singleDayHasNoCaptions() {
        assertTrue(simpleTimeline(30).dayStarts.isEmpty())
    }
}

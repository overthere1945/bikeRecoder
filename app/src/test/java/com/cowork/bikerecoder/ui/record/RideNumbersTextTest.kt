package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.ui.activity.ActivityText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The per-activity summary lines (V1.9.0 spec §3.2, plan P15) and the record cards' three cells (spec §2.2). */
class RideNumbersTextTest {

    private val bike = RideStats.ZERO.copy(
        currentSpeedMps = 5.0,
        distanceM = 3_010.0,
        movingTimeMs = 600_000,
        elapsedMs = 700_000,
        avgSpeedMps = 5.0,
    )

    /** 5.00 km in 27:40, i.e. 5'32"/km. */
    private val run = RideStats.ZERO.copy(
        currentSpeedMps = 3.0,
        distanceM = 5_000.0,
        movingTimeMs = 1_660_000,
        elapsedMs = 1_700_000,
        avgSpeedMps = 5_000.0 / 1_660.0,
        recentSpeedMps = 1000.0 / 330.0,
    )

    private val hike = RideStats.ZERO.copy(
        distanceM = 4_200.0,
        movingTimeMs = 5_000_000,
        elapsedMs = 5_025_000,
        altitudeM = 812.4,
        ascentM = 520.0,
    )

    @Test
    fun bikeLinesUnchanged() {
        assertEquals("18.0km/h · 3.01km · 0:10:00 · 평균 18.0", rideMapSummaryLine(bike))
        assertEquals("3.01km · 0:10:00 · 평균 18.0", rideSummaryLine(bike))
        assertEquals("3.01km · 0:10:00 · 평균 18.0", rideSummaryLine(bike, ActivityType.BIKE))
        assertEquals("18.0km/h · 3.01km · 0:10:00 · 평균 18.0", rideMapSummaryLine(bike, ActivityType.BIKE))
    }

    /** The screen's "직전 1 km" is the voice's lap pace: the lap's moving time over the km it covered (600 s / 2 km). */
    @Test
    fun lastKmPaceSharesALapThatSpansSeveralKm() {
        assertEquals("--'--\"/km", lastKmPaceText(run))
        assertEquals("5'30\"/km", lastKmPaceText(run.copy(lastLapMovingTimeMs = 330_000L)))
        assertEquals("5'00\"/km", lastKmPaceText(run.copy(lastLapMovingTimeMs = 600_000L, lastLapKms = 2)))
    }

    @Test
    fun runSummaryLine() {
        assertEquals("5.00km · 0:27:40 · 평균 5'32\"/km", rideSummaryLine(run, ActivityType.RUN))
        assertEquals("5.00km · 0:27:40 · 평균 5'32\"/km", rideSummaryLine(run, ActivityType.WALK))
    }

    @Test
    fun runMapLineStartsWithCurrentPace() {
        assertEquals(
            "5'30\"/km · 5.00km · 0:27:40 · 평균 5'32\"/km",
            rideMapSummaryLine(run, ActivityType.RUN),
        )
        // Fewer than two points in the 30 s window: no current pace yet.
        assertEquals(
            "--'--\"/km · 5.00km · 0:27:40 · 평균 5'32\"/km",
            rideMapSummaryLine(run.copy(recentSpeedMps = null), ActivityType.RUN),
        )
    }

    /** The 30 s window is not aged by the clock, so a paused recording must not show a stale pace. */
    @Test
    fun pausedMapLineHasNoCurrentPace() {
        assertEquals(
            "--'--\"/km · 5.00km · 0:27:40 · 평균 5'32\"/km",
            rideMapSummaryLine(run, ActivityType.WALK, paused = true),
        )
    }

    @Test
    fun hikeSummaryLine() {
        val expected = "산행 1:23:45 · 812m · ↑ 520m"
        assertEquals(expected, rideSummaryLine(hike, ActivityType.HIKE))
        // No lead-in on the map page.
        assertEquals(expected, rideMapSummaryLine(hike, ActivityType.HIKE))
        assertEquals(
            "산행 1:23:45 · -- · ↑ 520m",
            rideSummaryLine(hike.copy(altitudeM = null), ActivityType.HIKE),
        )
    }

    @Test
    fun cardCellsPerType() {
        val d = 5_000.0
        val moving = 1_660_000L
        val elapsed = 1_700_000L
        assertEquals(
            listOf("거리" to "5.0 km", "이동 시간" to "27분", "평균 속도" to "10.8 km/h"),
            ActivityText.cardCells(ActivityType.BIKE, d, moving, elapsed, 30.0, 100.0),
        )
        assertEquals(
            listOf("거리" to "5.0 km", "평균 페이스" to "5'32\"/km", "이동 시간" to "27분"),
            ActivityText.cardCells(ActivityType.RUN, d, moving, elapsed, 30.0, 100.0),
        )
        assertEquals(
            listOf("거리" to "5.0 km", "이동 시간" to "27분", "평균 페이스" to "5'32\"/km"),
            ActivityText.cardCells(ActivityType.WALK, d, moving, elapsed, 30.0, 100.0),
        )
        assertEquals(
            listOf("산행 시간" to "28분", "누적 상승" to "30 m", "최고 고도" to "100 m"),
            ActivityText.cardCells(ActivityType.HIKE, d, moving, elapsed, 30.0, 100.0),
        )
    }

    @Test
    fun hikeCardUsesElapsed() {
        // 2 h 5 min elapsed with 1 h 30 min moving: the hike time is the elapsed one, rests included.
        val cells = ActivityText.cardCells(ActivityType.HIKE, 6_000.0, 5_400_000, 7_500_000, 520.0, null)
        assertEquals("2시간 5분", cells[0].second)
        assertEquals("520 m", cells[1].second)
        assertEquals("--", cells[2].second)
    }

    @Test
    fun cardCellsWithoutMovingTime() {
        val cells = ActivityText.cardCells(ActivityType.RUN, 0.0, 0, 0, 0.0, null)
        assertEquals("--'--\"/km", cells[1].second)
        assertEquals("0.0 km/h", ActivityText.cardCells(ActivityType.BIKE, 0.0, 0, 0, 0.0, null)[2].second)
    }

    /** Spec §3.2: no V1.8.0 summary number is lost; a run and a walk trade "평균/최고" for the average speed. */
    @Test
    fun summaryRestCellsKeepTheOldNumbers() {
        val totals = SegmentTotals.ZERO.copy(
            distanceM = 5_000.0,
            movingTimeMs = 1_660_000,
            maxSpeedMps = 5.0,
            ascentM = 30.0,
        )
        assertEquals(
            listOf("최고 속도" to "18.0km/h", "누적 상승" to "30m"),
            summaryRestCells(ActivityType.BIKE, totals),
        )
        assertEquals(
            listOf("평균 속도" to "10.8km/h", "누적 상승" to "30m"),
            summaryRestCells(ActivityType.RUN, totals),
        )
        assertEquals(
            listOf("평균 속도" to "10.8km/h", "누적 상승" to "30m"),
            summaryRestCells(ActivityType.WALK, totals),
        )
        assertEquals(
            listOf("거리" to "5.00km", "이동 시간" to "0:27:40", "평균/최고" to "10.8 / 18.0km/h"),
            summaryRestCells(ActivityType.HIKE, totals),
        )
    }
}

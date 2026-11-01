package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.video.HudValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The closing card's six numbers (spec §3.7): heart rate or "주행 N회", calories or the ascent. */
class FrameRendererCellsTest {

    private fun totals(avgBpm: Int?, calories: Double?) =
        TripTotals(12_345.0, 3_600_000L, 3.43, 9.0, 120.4, avgBpm, calories)

    @Test
    fun sixCellsWithFallbacks() {
        val first = listOf(
            "거리" to "12.34km", "이동 시간" to "1시간 0분", "평균 속도" to "12.3km/h", "최고 속도" to "32.4km/h",
        )

        assertEquals(
            first + listOf("주행" to "2회", "상승 고도" to "120m"),
            FrameRenderer.summaryCells(totals(avgBpm = null, calories = null), segmentCount = 2),
        )
        assertEquals(
            first + listOf("평균 심박" to "132bpm", "칼로리" to "210kcal"),
            FrameRenderer.summaryCells(totals(avgBpm = 132, calories = 210.4), segmentCount = 2),
        )
        assertEquals(
            first + listOf("평균 심박" to "132bpm", "상승 고도" to "120m"),
            FrameRenderer.summaryCells(totals(avgBpm = 132, calories = null), segmentCount = 1),
        )
        assertEquals(
            first + listOf("주행" to "1회", "칼로리" to "210kcal"),
            FrameRenderer.summaryCells(totals(avgBpm = null, calories = 210.4), segmentCount = 1),
        )
    }

    /** The card writes units smaller (fix round 1); a duration stays whole. */
    @Test
    fun unitsSplitOffNumbers() {
        assertEquals("18.0" to "km/h", FrameRenderer.splitUnit("18.0km/h"))
        assertEquals("12.34" to "km", FrameRenderer.splitUnit("12.34km"))
        assertEquals("132" to "bpm", FrameRenderer.splitUnit("132bpm"))
        assertEquals("210" to "kcal", FrameRenderer.splitUnit("210kcal"))
        assertEquals("120" to "m", FrameRenderer.splitUnit("120m"))
        assertEquals("2" to "회", FrameRenderer.splitUnit("2회"))
        assertEquals("1시간 0분" to "", FrameRenderer.splitUnit("1시간 0분"))
        assertEquals("6분" to "", FrameRenderer.splitUnit("6분"))
    }

    @Test
    fun longDateSpellsTheWeekday() {
        assertEquals("10월 25일 일요일", FrameRenderer.longDate("10월 25일 (일)"))
        assertEquals("10월 17일 ~ 18일", FrameRenderer.longDate("10월 17일 ~ 18일"))
        assertEquals("10월 31일 ~ 11월 1일", FrameRenderer.longDate("10월 31일 ~ 11월 1일"))
    }

    // ---- V1.9.0 spec §5.1: the first cells follow the trip's dominant activity ----

    /** 5 km in 27 min 40 s (5'32" a kilometre), 11.0 km/h average. */
    private fun run(fastestKmMs: Long?) = TripTotals(
        5_000.0, 1_660_000L, 3.012, 4.5, 40.0, avgBpm = null, calories = null, maxAltitudeM = 55.0, fastestKmMs = fastestKmMs,
    )

    @Test
    fun runCellsWithFastestKm() {
        assertEquals(
            listOf(
                "거리" to "5.00km", "이동 시간" to "27분", "평균 페이스" to "5'32\"/km", "가장 빠른 1 km" to "4'48\"/km",
                "활동" to "2회", "상승 고도" to "40m",
            ),
            FrameRenderer.summaryCells(run(fastestKmMs = 288_000L), segmentCount = 2, activity = ActivityType.RUN),
        )
    }

    @Test
    fun runWithoutFastestKmDropsCell() {
        val cells = FrameRenderer.summaryCells(run(fastestKmMs = null), segmentCount = 1, activity = ActivityType.RUN)
        assertEquals(
            listOf("거리" to "5.00km", "이동 시간" to "27분", "평균 페이스" to "5'32\"/km", "활동" to "1회", "상승 고도" to "40m"),
            cells,
        )
    }

    @Test
    fun mixedTripPaceIsTheDominantActivitysOnly() {
        // RUN 5 km / 30 min (dominant) + BIKE 10 km / 20 min: the distance is the trip's, the pace the run's.
        val mixed = TripTotals(
            15_000.0, 3_000_000L, 5.0, 9.0, 0.0, avgBpm = null, calories = null,
            paceDistanceM = 5_000.0, paceMovingTimeMs = 1_800_000L,
        )
        assertEquals(
            listOf("거리" to "15.00km", "이동 시간" to "50분", "평균 페이스" to "6'00\"/km"),
            FrameRenderer.rideCells(mixed, ActivityType.RUN),
        )
    }

    @Test
    fun walkCells() {
        val walk = TripTotals(3_200.0, 2_400_000L, 1.333, 2.0, 12.0, avgBpm = 101, calories = 150.0)
        assertEquals(
            listOf(
                "거리" to "3.20km", "이동 시간" to "40분", "평균 페이스" to "12'30\"/km", "평균 속도" to "4.8km/h",
                "평균 심박" to "101bpm", "칼로리" to "150kcal",
            ),
            FrameRenderer.summaryCells(walk, segmentCount = 1, activity = ActivityType.WALK),
        )
    }

    @Test
    fun hikeCells() {
        val hike = TripTotals(
            6_400.0, 9_000_000L, 0.7, 1.4, 520.4, avgBpm = null, calories = null, hikeElapsedMs = 12_000_000L, maxAltitudeM = 835.6,
        )
        assertEquals(
            listOf("산행 시간" to "3시간 20분", "누적 상승" to "520m", "최고 고도" to "836m", "거리" to "6.40km", "활동" to "1회"),
            FrameRenderer.summaryCells(hike, segmentCount = 1, activity = ActivityType.HIKE),
        )
        // The calories take the last place; an unknown highest point is written as the app writes it elsewhere.
        assertEquals(
            listOf("산행 시간" to "3시간 20분", "누적 상승" to "520m", "최고 고도" to "--", "거리" to "6.40km", "평균 심박" to "120bpm", "칼로리" to "300kcal"),
            FrameRenderer.summaryCells(hike.copy(maxAltitudeM = null, avgBpm = 120, calories = 300.0), segmentCount = 1, activity = ActivityType.HIKE),
        )
    }

    @Test
    fun bikeCellsUnchanged() {
        val bike = totals(avgBpm = null, calories = null)
        assertEquals(
            listOf("거리" to "12.34km", "이동 시간" to "1시간 0분", "평균 속도" to "12.3km/h", "최고 속도" to "32.4km/h"),
            FrameRenderer.rideCells(bike, ActivityType.BIKE),
        )
        assertEquals(FrameRenderer.summaryCells(bike, 2), FrameRenderer.summaryCells(bike, 2, ActivityType.BIKE))
        assertEquals("주행" to "2회", FrameRenderer.heartRateOrRides(bike, 2))
    }

    @Test
    fun paceSplitsOffItsUnit() {
        assertEquals("5'32\"" to "/km", FrameRenderer.splitUnit("5'32\"/km"))
        assertEquals("--'--\"" to "/km", FrameRenderer.splitUnit("--'--\"/km"))
        assertEquals("12.34" to "km", FrameRenderer.splitUnit("12.34km"))
    }

    // ---- the number band of one frame ----

    private fun hud(altitudeM: Double? = 120.4, heartBpm: Int? = 131) = HudValues(
        distanceKm = 5.123, speedKmh = 10.84, altitudeM = altitudeM, heartBpm = heartBpm, elapsedMs = 3_725_000L, ascentM = 87.6,
    )

    @Test
    fun hudBikeCellsAsBefore() {
        assertEquals(
            listOf(
                Triple("거리", "5.12", "km"), Triple("속도", "10.8", "km/h"), Triple("고도", "120", "m"), Triple("심박", "131", "bpm"),
            ),
            FrameRenderer.hudCells(hud(), ActivityType.BIKE),
        )
    }

    @Test
    fun hudRunAndWalkShowThePace() {
        val expected = listOf(
            Triple("거리", "5.12", "km"), Triple("페이스", "5'32\"", "/km"), Triple("고도", "120", "m"), Triple("심박", "131", "bpm"),
        )
        // 10.84 km/h = 3.011 m/s -> 332 s a km.
        assertEquals(expected, FrameRenderer.hudCells(hud(), ActivityType.RUN))
        assertEquals(expected, FrameRenderer.hudCells(hud(), ActivityType.WALK))
        assertEquals("--'--\"", FrameRenderer.hudCells(hud().copy(speedKmh = 0.4), ActivityType.WALK)[1].second)
    }

    @Test
    fun hudHikeShowsTimeAltitudeAndAscent() {
        assertEquals(
            listOf(
                Triple("산행 시간", "1:02:05", ""), Triple("고도", "120", "m"), Triple("누적 상승", "88", "m"), Triple("심박", "131", "bpm"),
            ),
            FrameRenderer.hudCells(hud(), ActivityType.HIKE),
        )
        // A route without heights has neither altitude nor ascent; no heart rate drops that cell.
        assertEquals(
            listOf(Triple("산행 시간", "1:02:05", "")),
            FrameRenderer.hudCells(hud(altitudeM = null, heartBpm = null), ActivityType.HIKE, hasAltitude = false),
        )
    }
}

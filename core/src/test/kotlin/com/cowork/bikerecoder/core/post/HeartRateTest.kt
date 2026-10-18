package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateTest {

    private fun hr(timeMillis: Long, bpm: Int) = HeartRateSample(timeMillis, bpm)

    /** 위도를 [timeMillis]에 비례해 옮겨 점마다 거리가 늘도록 한다(1초 ≈ 1.11 m). */
    private fun tp(timeMillis: Long) =
        TrackPoint(timeMillis, GeoPoint(37.0 + timeMillis / 1_000 * 0.00001, 127.0), altitudeM = null, speedMps = null, accuracyM = 5f)

    @Test
    fun averageAndMax() {
        val stats = HeartRateSummary.of(listOf(hr(0, 100), hr(1, 110), hr(2, 121)), movingTimeMs = 60_000)
        assertNotNull(stats)
        assertEquals(110, stats.avgBpm) // 331 / 3 = 110.33
        assertEquals(121, stats.maxBpm)
    }

    @Test
    fun averageRoundsHalfUp() {
        val stats = HeartRateSummary.of(listOf(hr(0, 100), hr(1, 101)), movingTimeMs = 60_000)
        assertNotNull(stats)
        assertEquals(101, stats.avgBpm) // 100.5
    }

    @Test
    fun detailedWhenOnePerMinute() {
        val ten = (0 until 10).map { hr(it * 1_000L, 120) }
        assertTrue(HeartRateSummary.of(ten, movingTimeMs = 10 * 60_000L)!!.detailed)
        assertFalse(HeartRateSummary.of(ten.take(9), movingTimeMs = 10 * 60_000L)!!.detailed)
    }

    @Test
    fun detailedUsesFractionalMinutes() {
        // 9.5 minutes of moving time: 10 samples is enough, 9 is not.
        val ten = (0 until 10).map { hr(it * 1_000L, 120) }
        assertTrue(HeartRateSummary.of(ten, movingTimeMs = 570_000L)!!.detailed)
        assertFalse(HeartRateSummary.of(ten.take(9), movingTimeMs = 570_000L)!!.detailed)
    }

    @Test
    fun emptyIsNull() {
        assertNull(HeartRateSummary.of(emptyList(), movingTimeMs = 60_000))
    }

    @Test
    fun tripAverageIsTimeWeighted() {
        val parts = listOf(
            HeartRateStats(100, 120, detailed = true) to 10 * 60_000L,
            HeartRateStats(160, 180, detailed = false) to 30 * 60_000L,
        )
        assertEquals(145, HeartRateSummary.tripAverage(parts))
    }

    @Test
    fun tripAverageOfNothingIsNull() {
        assertNull(HeartRateSummary.tripAverage(emptyList()))
    }

    @Test
    fun tripAverageWithZeroMovingTimeFallsBackToPlainMean() {
        val parts = listOf(
            HeartRateStats(100, 120, detailed = true) to 0L,
            HeartRateStats(150, 180, detailed = true) to 0L,
        )
        assertEquals(125, HeartRateSummary.tripAverage(parts))
    }

    @Test
    fun bandEdges() {
        assertEquals(HrBand.BLUE, HeartRateColor.band(99))
        assertEquals(HrBand.GREEN, HeartRateColor.band(100))
        assertEquals(HrBand.GREEN, HeartRateColor.band(119))
        assertEquals(HrBand.YELLOW, HeartRateColor.band(120))
        assertEquals(HrBand.YELLOW, HeartRateColor.band(139))
        assertEquals(HrBand.ORANGE, HeartRateColor.band(140))
        assertEquals(HrBand.ORANGE, HeartRateColor.band(159))
        assertEquals(HrBand.RED, HeartRateColor.band(160))
    }

    @Test
    fun bandColours() {
        assertEquals("#1E88E5", HrBand.BLUE.colorHex)
        assertEquals("#43A047", HrBand.GREEN.colorHex)
        assertEquals("#FDD835", HrBand.YELLOW.colorHex)
        assertEquals("#FB8C00", HrBand.ORANGE.colorHex)
        assertEquals("#E53935", HrBand.RED.colorHex)
        assertEquals("#9E9E9E", HrBand.NONE.colorHex)
    }

    @Test
    fun bandsMatchWithinThirtySeconds() {
        val track = listOf(tp(100_000), tp(129_000), tp(131_000))
        val bands = HeartRateColor.bands(track, listOf(hr(100_000, 90)))
        assertEquals(listOf(HrBand.BLUE, HrBand.BLUE, HrBand.NONE), bands) // dt 0, +29 s, +31 s
    }

    @Test
    fun bandsMatchSamplesBeforeAndAfterThePoint() {
        val track = listOf(tp(100_000))
        assertEquals(listOf(HrBand.RED), HeartRateColor.bands(track, listOf(hr(70_000, 170)))) // 30 s before
        assertEquals(listOf(HrBand.RED), HeartRateColor.bands(track, listOf(hr(130_000, 170)))) // 30 s after
        assertEquals(listOf(HrBand.NONE), HeartRateColor.bands(track, listOf(hr(130_001, 170))))
    }

    @Test
    fun bandsPickTheNearestSample() {
        val track = listOf(tp(100_000))
        val samples = listOf(hr(80_000, 90), hr(95_000, 130), hr(125_000, 170)) // 20 s, 5 s, 25 s away
        assertEquals(listOf(HrBand.YELLOW), HeartRateColor.bands(track, samples))
    }

    @Test
    fun bandsWithoutSamplesAreNone() {
        val track = listOf(tp(0), tp(1_000))
        assertEquals(listOf(HrBand.NONE, HrBand.NONE), HeartRateColor.bands(track, emptyList()))
        assertEquals(emptyList(), HeartRateColor.bands(emptyList(), listOf(hr(0, 100))))
    }

    @Test
    fun chartSkipsUnmatchedPoints() {
        // Points every 10 s from t=0..60 s; the only sample is at t=0, so only points within 30 s match.
        val track = (0..6).map { tp(it * 10_000L) }
        val chart = HeartRateCharts.bpm(track, listOf(hr(0, 120)))
        assertEquals(4, chart.size) // t = 0, 10, 20, 30 s
        assertTrue(chart.all { it.value == 120.0 })
        assertEquals(0.0, chart.first().distanceKm)
        assertTrue(chart.zipWithNext().all { (a, b) -> b.distanceKm > a.distanceKm })
    }

    @Test
    fun chartUsesTheDistanceAxisOfTheOtherCharts() {
        val track = listOf(tp(0), tp(5_000), tp(10_000))
        val chart = HeartRateCharts.bpm(track, listOf(hr(0, 100), hr(5_000, 110), hr(10_000, 120)))
        assertEquals(listOf(100.0, 110.0, 120.0), chart.map { it.value })
        assertEquals(0.0, chart[0].distanceKm)
        // 5 s ≈ 5.56 m at 0.00001 deg/s latitude
        assertEquals(0.0056, chart[1].distanceKm, 0.0003)
        assertEquals(0.0111, chart[2].distanceKm, 0.0003)
    }

    @Test
    fun chartDownsamplesToMaxPoints() {
        val track = (0 until 100).map { tp(it * 1_000L) }
        val samples = (0 until 100).map { hr(it * 1_000L, 100 + it) }
        val chart = HeartRateCharts.bpm(track, samples, maxPoints = 10)
        assertEquals(10, chart.size)
        assertEquals(100.0, chart.first().value)
        assertEquals(199.0, chart.last().value)
    }

    @Test
    fun chartWithoutSamplesIsEmpty() {
        assertEquals(emptyList(), HeartRateCharts.bpm(listOf(tp(0), tp(1_000)), emptyList()))
    }
}

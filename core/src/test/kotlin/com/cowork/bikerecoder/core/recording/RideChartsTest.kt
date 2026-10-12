package com.cowork.bikerecoder.core.recording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RideChartsTest {

    private fun point(t: Long, m: Double, altitudeM: Double? = null, speedMps: Float? = null) =
        TrackPoint(timeMillis = t, point = north(m), altitudeM = altitudeM, speedMps = speedMps, accuracyM = 5f)

    @Test
    fun `x skips gaps over 10 s`() {
        // 0..100 m at 1 Hz (10 m steps), then 60 s later 200..300 m: the 100 → 200 m jump adds nothing.
        val before = (0..10).map { point(it * 1_000L, it * 10.0, altitudeM = 50.0) }
        val after = (0..10).map { point(70_000L + it * 1_000L, 200.0 + it * 10.0, altitudeM = 50.0) }

        val chart = RideCharts.altitude(before + after)

        assertEquals(22, chart.size)
        assertEquals(0.1, chart[10].distanceKm, 0.001)
        assertEquals(0.1, chart[11].distanceKm, 0.001)
        assertEquals(0.2, chart.last().distanceKm, 0.001)
    }

    @Test
    fun `a pair exactly 10 s apart still adds distance`() {
        val chart = RideCharts.altitude(listOf(point(0, 0.0, 10.0), point(10_000, 100.0, 10.0)))

        assertEquals(0.1, chart.last().distanceKm, 0.001)
    }

    @Test
    fun `downsampled to at most maxPoints`() {
        // 1 Hz, 5 m apart, 1_000 points: altitude = index.
        val points = (0 until 1_000).map { point(it * 1_000L, it * 5.0, altitudeM = it.toDouble()) }

        val chart = RideCharts.altitude(points)

        assertEquals(300, chart.size)
        assertEquals(0.0, chart.first().distanceKm, 1e-9)
        assertEquals(0.0, chart.first().value, 1e-9)
        assertEquals(4.995, chart.last().distanceKm, 1e-6)
        assertEquals(999.0, chart.last().value, 1e-9)
        // The 998 middle points fall into 298 ordered buckets: x strictly increases and every bucket is a mean.
        assertEquals(chart.sortedBy { it.distanceKm }, chart)
        assertEquals(chart.map { it.distanceKm }.distinct().size, chart.size)
        // Bucket means of a straight line stay on that line (altitude = 200 · km).
        chart.forEach { assertEquals(it.distanceKm * 200.0, it.value, 1e-6) }
    }

    @Test
    fun `at most maxPoints are kept unchanged`() {
        val points = (0 until 5).map { point(it * 1_000L, it * 5.0, altitudeM = it.toDouble()) }

        val chart = RideCharts.altitude(points, maxPoints = 5)

        assertEquals(5, chart.size)
        chart.forEachIndexed { i, p ->
            assertEquals(i * 0.005, p.distanceKm, 1e-9)
            assertEquals(i.toDouble(), p.value, 1e-9)
        }
    }

    @Test
    fun `downsampling buckets the middle points in order`() {
        // 6 points into 4: first and last kept, middle 4 points into 2 buckets of 2.
        val points = (0 until 6).map { point(it * 1_000L, it * 10.0, altitudeM = it * 10.0) }

        val chart = RideCharts.altitude(points, maxPoints = 4)

        assertEquals(listOf(0.0, 15.0, 35.0, 50.0), chart.map { it.value })
        assertEquals(listOf(0.0, 0.015, 0.035, 0.05), chart.map { it.distanceKm }.map { Math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun `altitude skips nulls`() {
        val points = listOf(
            point(0, 0.0, altitudeM = 100.0),
            point(1_000, 5.0, altitudeM = null),
            point(2_000, 10.0, altitudeM = 110.0),
        )

        val chart = RideCharts.altitude(points)

        assertEquals(2, chart.size)
        assertEquals(0.0, chart[0].distanceKm, 1e-9)
        assertEquals(100.0, chart[0].value, 1e-9)
        assertEquals(0.010, chart[1].distanceKm, 1e-9)
        assertEquals(110.0, chart[1].value, 1e-9)
    }

    @Test
    fun `speed falls back to pair speed`() {
        val chart = RideCharts.speedKmh(listOf(point(0, 0.0), point(1_000, 5.0)))

        assertEquals(1, chart.size)
        assertEquals(0.005, chart[0].distanceKm, 1e-9)
        assertEquals(18.0, chart[0].value, 1e-9)
    }

    @Test
    fun `speed uses the recorded speed when present`() {
        val chart = RideCharts.speedKmh(listOf(point(0, 0.0, speedMps = 2.5f), point(1_000, 5.0, speedMps = 4f)))

        assertEquals(listOf(9.0, 14.4), chart.map { it.value }.map { Math.round(it * 1e6) / 1e6 })
        assertEquals(0.0, chart[0].distanceKm, 1e-9)
    }

    @Test
    fun `empty track draws nothing`() {
        assertEquals(emptyList<ChartPoint>(), RideCharts.altitude(emptyList()))
        assertEquals(emptyList<ChartPoint>(), RideCharts.speedKmh(emptyList()))
    }
}

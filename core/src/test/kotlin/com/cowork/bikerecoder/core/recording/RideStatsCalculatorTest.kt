package com.cowork.bikerecoder.core.recording

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RideStatsCalculatorTest {

    private fun RideStatsCalculator.addKmh(t: Long, m: Double, kmh: Double) = add(fix(t, m), kmh / 3.6)

    @Test
    fun `distance sums accepted fixes`() {
        val calc = RideStatsCalculator()
        for (s in 0..200) calc.add(fix(s * 1_000L, s * 5.0), 5.0)
        assertEquals(1_000.0, calc.distanceM, 0.5)
        assertEquals(200_000L, calc.movingTimeMs)
    }

    @Test
    fun `break chain does not join across`() {
        val calc = RideStatsCalculator()
        for (s in 0..10) calc.add(fix(s * 1_000L, s * 10.0), 10.0) // 0..100 m
        calc.breakChain()
        for (s in 0..10) calc.add(fix(20_000L + s * 1_000L, 130.0 + s * 10.0), 10.0) // 130..230 m
        assertEquals(200.0, calc.distanceM, 0.5)
        assertEquals(20_000L, calc.movingTimeMs)
    }

    @Test
    fun `gap over 10 s while stationary adds nothing`() {
        val calc = RideStatsCalculator()
        calc.add(fix(0, 0.0), 0.0)
        calc.add(fix(60_000, 10.0), 0.0)
        assertEquals(0.0, calc.distanceM, 1e-9)
        assertEquals(0L, calc.movingTimeMs)
    }

    @Test
    fun `moving gap over 10 s is bridged`() {
        val calc = RideStatsCalculator()
        calc.add(fix(0, 0.0), 5.0)
        calc.add(fix(20_000, 100.0), 5.0) // 18 km/h
        assertEquals(100.0, calc.distanceM, 0.5)
        assertEquals(20_000L, calc.movingTimeMs)
    }

    @Test
    fun `gap of exactly 10 s still counts as consecutive`() {
        val calc = RideStatsCalculator()
        calc.add(fix(0, 0.0), 0.0)
        calc.add(fix(10_000, 1.0), 0.0) // 0.36 km/h but dt <= gap
        assertEquals(1.0, calc.distanceM, 0.01)
        assertEquals(10_000L, calc.movingTimeMs)
    }

    @Test
    fun `average is distance over moving time`() {
        val calc = RideStatsCalculator()
        calc.add(fix(0, 0.0), 5.0)
        assertEquals(0.0, calc.avgSpeedMps, 1e-9)
        for (s in 1..200) calc.add(fix(s * 1_000L, s * 5.0), 5.0)
        assertEquals(5.0, calc.avgSpeedMps, 1e-3)
    }

    @Test
    fun `single spike does not raise max`() {
        val calc = RideStatsCalculator()
        listOf(18.0, 18.0, 18.0, 72.0, 18.0, 18.0).forEachIndexed { i, kmh ->
            calc.addKmh(i * 1_000L, i * 5.0, kmh)
        }
        assertEquals(18.0, calc.maxSpeedMps * 3.6, 1e-6)
    }

    @Test
    fun `max is the highest 3 s median`() {
        val calc = RideStatsCalculator()
        listOf(18.0, 21.6, 25.2, 28.8, 28.8, 28.8).forEachIndexed { i, kmh ->
            calc.addKmh(i * 1_000L, i * 5.0, kmh)
        }
        assertEquals(28.8, calc.maxSpeedMps * 3.6, 1e-6)
    }

    @Test
    fun `max needs at least two samples in the window`() {
        val calc = RideStatsCalculator()
        calc.addKmh(0, 0.0, 40.0)
        assertEquals(0.0, calc.maxSpeedMps, 1e-9)
    }

    @Test
    fun `current speed is a 3 s moving average`() {
        val calc = RideStatsCalculator()
        calc.add(fix(0, 0.0), 3.0)
        calc.add(fix(1_000, 3.0), 6.0)
        calc.add(fix(2_000, 9.0), 9.0)
        assertEquals(6.0, calc.currentSpeedMps, 1e-9)
    }

    @Test
    fun `current speed window drops samples older than 3 s`() {
        val calc = RideStatsCalculator()
        calc.add(fix(0, 0.0), 30.0)
        calc.add(fix(3_000, 15.0), 6.0) // t=0은 (0, 3000] 밖
        calc.add(fix(4_000, 21.0), 8.0)
        assertEquals(7.0, calc.currentSpeedMps, 1e-9)
    }

    @Test
    fun `break chain clears the speed window`() {
        val calc = RideStatsCalculator()
        calc.add(fix(0, 0.0), 10.0)
        calc.add(fix(1_000, 10.0), 10.0)
        calc.breakChain()
        calc.add(fix(2_000, 30.0), 2.0)
        assertEquals(2.0, calc.currentSpeedMps, 1e-9)
    }

    @Test
    fun `two-sample window takes the lower median`() {
        val calc = RideStatsCalculator()
        calc.addKmh(0, 0.0, 18.0)
        calc.addKmh(2_000, 10.0, 72.0)
        assertEquals(18.0, calc.maxSpeedMps * 3.6, 1e-6)
    }
}

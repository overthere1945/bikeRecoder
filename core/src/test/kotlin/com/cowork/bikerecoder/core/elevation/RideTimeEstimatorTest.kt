package com.cowork.bikerecoder.core.elevation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RideTimeEstimatorTest {

    private val kmh18 = 18.0 / 3.6

    @Test
    fun flatIsDistanceOverSpeed() {
        val p = elevationProfile(2_000.0) { 30.0 }
        assertEquals(200.0, RideTimeEstimator.seconds(p, 500.0, 1_500.0, 5.0), 1e-6)
    }

    @Test
    fun fivePercentAtEighteen() {
        val p = elevationProfile(3_000.0) { it * 0.05 }
        // 18 km/h / (1 + 0.12 × 5) = 11.25 km/h -> 1 km에 320초.
        assertEquals(320.0, RideTimeEstimator.seconds(p, 1_000.0, 2_000.0, kmh18), 0.5)
    }

    @Test
    fun floorSixKmh() {
        val p = elevationProfile(3_000.0) { it * 0.20 }
        // 18 / 3.4 ≈ 5.3 km/h는 6 km/h 밑이므로 6 km/h -> 600초.
        assertEquals(600.0, RideTimeEstimator.seconds(p, 1_000.0, 2_000.0, kmh18), 0.5)
    }

    @Test
    fun slowBaseBelowFloorKeepsBase() {
        val p = elevationProfile(3_000.0) { it * 0.05 }
        // base 5 km/h는 하한 6 km/h보다 느리므로 base 그대로 -> 1 km에 720초.
        assertEquals(720.0, RideTimeEstimator.seconds(p, 1_000.0, 2_000.0, 5.0 / 3.6), 0.5)
    }

    @Test
    fun downhillFivePercentIsOnePointOneFive() {
        val p = elevationProfile(3_000.0) { 1_000.0 - it * 0.05 }
        // 1 + 0.03 × 5 = 1.15배: 5.75 m/s.
        assertEquals(5.75, RideTimeEstimator.speedMps(-5.0, 5.0), 1e-12)
        assertEquals(1_000.0 / 5.75, RideTimeEstimator.seconds(p, 1_000.0, 2_000.0, 5.0), 0.5)
    }

    @Test
    fun downhillCappedAtOnePointThree() {
        val p = elevationProfile(3_000.0) { 1_000.0 - it * 0.15 }
        // 1 + 0.03 × 15 = 1.45 -> 1.3배: 6.5 m/s.
        assertEquals(1_000.0 / 6.5, RideTimeEstimator.seconds(p, 1_000.0, 2_000.0, 5.0), 0.5)
    }

    @Test
    fun nullProfileIsPlain() {
        assertEquals(200.0, RideTimeEstimator.seconds(null, 100.0, 1_100.0, 5.0), 1e-9)
        assertEquals(0.0, RideTimeEstimator.seconds(null, 1_100.0, 100.0, 5.0), 1e-9)
    }

    @Test
    fun shortTailCellCounted() {
        // 마지막 칸이 5 m인 단면: 전체 시간은 실제 거리 ÷ 속도.
        val p = elevationProfile(2_005.0) { 30.0 }
        assertEquals(p.totalM / 5.0, RideTimeEstimator.seconds(p, 0.0, p.totalM, 5.0), 1e-6)
    }
}

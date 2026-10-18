package com.cowork.bikerecoder.core.recording

import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AltitudeFusionTest {

    /** Barometric.altitudeM의 역함수. */
    private fun pressureFor(altM: Double) = 1013.25 * (1 - altM / 44330).pow(5.255)

    private fun AltitudeFusion.baro(altM: Double, sec: Int) = onPressure(pressureFor(altM), sec * 1_000L)

    private fun AltitudeFusion.gps(altM: Double, vAcc: Float?, sec: Int) = onGps(altM, vAcc, sec * 1_000L)

    /** 매초 기압 표본을 넣고 같은 시각의 climbAltitudeM을 카운터에 먹인다. */
    private fun AltitudeFusion.feedBaro(counter: AscentCounter, altM: Double, sec: Int) {
        baro(altM, sec)
        counter.onAltitude(climbAltitudeM!!, climbThresholdM)
    }

    @Test
    fun `formula matches SensorManager`() {
        assertEquals(0.0, Barometric.altitudeM(1013.25), 1e-9)
        assertEquals(988.65, Barometric.altitudeM(900.0), 0.01)
    }

    @Test
    fun `barometer rise and fall accumulate`() {
        val f = AltitudeFusion()
        val c = AscentCounter()
        var t = 0
        for (i in 0..43) f.feedBaro(c, 0.7 * i, t++)               // 0 → 30.1m
        repeat(10) { f.feedBaro(c, 30.1, t++) }                    // 10초 평지
        for (i in 1..29) f.feedBaro(c, 30.1 - 0.7 * i, t++)        // 9.8m까지 내려옴
        repeat(10) { f.feedBaro(c, 9.8, t++) }                     // 10초 평지
        // 참값 30.1/20.3에서 기준점 이력 3m만큼 덜 잡힌다
        assertEquals(28.0, c.ascentM, 0.01)
        assertEquals(17.36, c.descentM, 0.01)
    }

    @Test
    fun `calibration uses the median gps difference over the first 60 s`() {
        val f = AltitudeFusion()
        val gpsCycle = doubleArrayOf(150.0, 152.0, 148.0)
        for (s in 0..60) {
            f.baro(100.0, s)
            f.gps(gpsCycle[s % 3], 5f, s)
            f.gps(200.0, 15f, s)                                    // 정확도 15m는 보정에서 제외
        }
        assertEquals(150.0, f.altitudeM!!, 1.0)
    }

    @Test
    fun `gps without vertical accuracy never calibrates`() {
        val f = AltitudeFusion()
        for (s in 0..10) {
            f.baro(100.0, s)
            f.gps(150.0, null, s)
        }
        assertEquals(AltitudeMode.BAROMETER, f.mode)
        assertNull(f.altitudeM)
        assertNotNull(f.climbAltitudeM)
    }

    @Test
    fun `recalibrates every 5 min with ema 0_1`() {
        val f = AltitudeFusion()
        for (s in 0..59) { f.baro(100.0, s); f.gps(150.0, 5f, s) }   // 초기 60초: 오프셋 50
        for (s in 60..359) { f.baro(100.0, s); f.gps(160.0, 5f, s) } // 다음 5분: 차이 60
        assertEquals(150.0, f.altitudeM!!, 0.1)                       // 5분이 끝나기 전엔 고정
        f.baro(100.0, 360)                                            // 5분 경계
        assertEquals(151.0, f.altitudeM!!, 0.1)                       // 50 + 0.1 × (60 − 50) = 51
    }

    @Test
    fun `offset is kept when a recalibration window has no differences`() {
        val f = AltitudeFusion()
        for (s in 0..59) { f.baro(100.0, s); f.gps(150.0, 5f, s) }
        for (s in 60..400) f.baro(100.0, s)
        assertEquals(150.0, f.altitudeM!!, 0.1)
    }

    @Test
    fun `2 m wobble is not accumulated`() {
        val wobble = doubleArrayOf(100.0, 102.0, 98.0, 100.0)

        val baro = AltitudeFusion()
        val baroCounter = AscentCounter()
        for (s in 0 until 120) baro.feedBaro(baroCounter, wobble[s % 4], s)
        assertEquals(0.0, baroCounter.ascentM)
        assertEquals(0.0, baroCounter.descentM)

        val gps = AltitudeFusion()
        val gpsCounter = AscentCounter()
        for (s in 0 until 120) {
            gps.gps(wobble[s % 4], 5f, s)
            gpsCounter.onAltitude(gps.climbAltitudeM!!, gps.climbThresholdM)
        }
        assertEquals(AltitudeMode.GPS, gps.mode)
        assertEquals(0.0, gpsCounter.ascentM)
        assertEquals(0.0, gpsCounter.descentM)
    }

    @Test
    fun `50 m climb gives 50 plus minus 3 m`() {
        val f = AltitudeFusion()
        val c = AscentCounter()
        fun noise(s: Int) = if (s % 2 == 0) 0.83 else -0.83
        for (s in 0..300) f.feedBaro(c, 100.0 + 0.1667 * s + noise(s), s)
        val top = 100.0 + 0.1667 * 300
        for (s in 301..330) f.feedBaro(c, top + noise(s), s)
        assertEquals(48.01, c.ascentM, 3.0)
        assertEquals(0.0, c.descentM)
    }

    @Test
    fun `no barometer uses gps`() {
        val f = AltitudeFusion()
        f.gps(120.0, 5f, 0)
        assertEquals(AltitudeMode.GPS, f.mode)
        assertNotNull(f.altitudeM)
        assertEquals(5.0, f.climbThresholdM)
    }

    @Test
    fun `barometer mode uses the 3 m threshold`() {
        val f = AltitudeFusion()
        assertEquals(AltitudeMode.NONE, f.mode)
        assertNull(f.altitudeM)
        assertNull(f.climbAltitudeM)
        f.baro(100.0, 0)
        assertEquals(AltitudeMode.BAROMETER, f.mode)
        assertEquals(3.0, f.climbThresholdM)
    }

    @Test
    fun `stale barometer switches to gps`() {
        val f = AltitudeFusion()
        f.onPressure(pressureFor(100.0), 0)
        f.onGps(150.0, 5f, 10_000)
        assertEquals(AltitudeMode.BAROMETER, f.mode)               // 정확히 10초는 아직 신선
        f.onGps(150.0, 5f, 10_001)
        assertEquals(AltitudeMode.GPS, f.mode)
        assertEquals(5.0, f.climbThresholdM)
    }

    @Test
    fun `gps median rejects a single spike`() {
        val f = AltitudeFusion()
        listOf(100.0, 100.0, 100.0, 160.0, 100.0).forEachIndexed { s, a -> f.gps(a, 5f, s) }
        assertEquals(100.0, f.altitudeM!!, 1.0)
    }

    @Test
    fun `ascent counter uses the first altitude as reference and rebase restarts it`() {
        val c = AscentCounter()
        c.onAltitude(100.0, 3.0)
        c.onAltitude(102.9, 3.0)
        assertEquals(0.0, c.ascentM)
        c.onAltitude(103.0, 3.0)
        assertEquals(3.0, c.ascentM, 1e-9)
        c.onAltitude(100.0, 3.0)
        assertEquals(3.0, c.descentM, 1e-9)
        c.rebase()
        c.onAltitude(200.0, 3.0)                                   // 새 기준점, 점프는 상승이 아니다
        c.onAltitude(201.0, 3.0)
        assertEquals(3.0, c.ascentM, 1e-9)
        assertEquals(3.0, c.descentM, 1e-9)
    }
}

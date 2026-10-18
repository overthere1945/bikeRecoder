package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.LocationFix
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** east 방향으로 [distanceM]만큼 이동한 위치의 측위 결과. speedMps는 사용하지 않으므로 null로 둔다. */
private fun fix(tMs: Long, distanceM: Double, accuracyM: Float = 10f): LocationFix =
    LocationFix(point = pointAt(distanceM, 0.0), accuracyM = accuracyM, speedMps = null, bearingDeg = null, timeMillis = tMs)

class EtaEstimatorTest {

    @Test
    fun `uses default speed with under 5 minutes of data`() {
        val estimator = EtaEstimator()
        val speedMps = 20.0 / 3.6
        for (tSec in 0..180 step 30) {
            estimator.onFix(fix(tSec * 1_000L, speedMps * tSec))
        }

        val base = estimator.baseSpeedMps(180_000L)

        assertEquals(15.0 / 3.6, base, 0.01)
    }

    @Test
    fun `uses 15 minute moving average`() {
        val estimator = EtaEstimator()
        val slowSpeedMps = 10.0 / 3.6
        val fastSpeedMps = 20.0 / 3.6

        for (tSec in 0..300 step 60) {
            estimator.onFix(fix(tSec * 1_000L, slowSpeedMps * tSec))
        }
        val distanceAt5Min = slowSpeedMps * 300
        for (tSec in 360..1_200 step 60) {
            val distance = distanceAt5Min + fastSpeedMps * (tSec - 300)
            estimator.onFix(fix(tSec * 1_000L, distance))
        }

        val base = estimator.baseSpeedMps(1_200_000L)

        assertEquals(fastSpeedMps, base, 0.1)
    }

    @Test
    fun `stops are excluded`() {
        val estimator = EtaEstimator()
        val speedMps = 18.0 / 3.6
        for (tSec in 0..600 step 60) {
            estimator.onFix(fix(tSec * 1_000L, speedMps * tSec))
        }
        val distanceAt10Min = speedMps * 600
        for (tSec in 660..900 step 60) {
            estimator.onFix(fix(tSec * 1_000L, distanceAt10Min)) // 정지: 같은 위치
        }

        val base = estimator.baseSpeedMps(900_000L)

        assertEquals(speedMps, base, 0.1)
    }

    @Test
    fun `eta falls back to default speed when only stationary fixes`() {
        val estimator = EtaEstimator()
        for (tSec in 0..1_800 step 60) {
            estimator.onFix(fix(tSec * 1_000L, 0.0)) // speed=null, 같은 좌표에 정지
        }

        val eta = estimator.etaMillis(1_800_000L, remainingM = 5_000.0)

        val expected = 1_800_000L + (5_000.0 / (15.0 / 3.6) * 1_000.0).toLong()
        assertEquals(expected, eta)
    }

    @Test
    fun `eta adds remaining over base speed`() {
        val estimator = EtaEstimator()
        val speedMps = 15.0 / 3.6
        // pointAt()은 테스트 전용 등장방형 근사라 GeoMath.distanceM과 완전히 같지는 않으므로,
        // 두 지점 사이의 "실제" 거리를 GeoMath로 측정해 그로부터 시간 간격을 역산한다.
        // 이렇게 하면 base speed가 speedMps와 (반올림 오차 수준으로) 정확히 일치한다.
        val a = pointAt(0.0, 0.0)
        val b = pointAt(3_000.0, 0.0)
        val actualDistanceM = GeoMath.distanceM(a, b)
        val dtMs = (actualDistanceM / speedMps * 1_000.0).toLong()

        estimator.onFix(LocationFix(point = a, accuracyM = 10f, speedMps = null, bearingDeg = null, timeMillis = 0L))
        estimator.onFix(LocationFix(point = b, accuracyM = 10f, speedMps = null, bearingDeg = null, timeMillis = dtMs))

        val eta = estimator.etaMillis(dtMs, remainingM = 15_000.0)

        val expected = dtMs + 3_600_000L
        assertTrue(abs(eta - expected) <= 1_000L, "eta=$eta, expected≈$expected")
    }
}

class OdometerTest {

    @Test
    fun `odometer ignores inaccurate fixes`() {
        val odometer = Odometer()

        odometer.onFix(fix(0L, 0.0, accuracyM = 10f))
        odometer.onFix(fix(1_000L, 5_000.0, accuracyM = 80f)) // 튐 점, 버려짐
        odometer.onFix(fix(2_000L, 500.0, accuracyM = 10f))
        odometer.onFix(fix(3_000L, 1_000.0, accuracyM = 10f))

        assertEquals(1_000.0, odometer.distanceM, 5.0)
    }

    @Test
    fun `odometer continues across midnight`() {
        val odometer = Odometer()
        val midnightMs = 1_700_000_000_000L // 임의의 자정 시각(epoch millis)
        val expectedDistanceM = GeoMath.distanceM(pointAt(0.0, 0.0), pointAt(1_000.0, 0.0))

        odometer.onFix(fix(midnightMs - 60_000L, 0.0)) // 23:59
        odometer.onFix(fix(midnightMs + 60_000L, 1_000.0)) // 00:01, 날짜가 바뀌어도 리셋되지 않아야 함

        assertEquals(expectedDistanceM, odometer.distanceM, 0.01)
    }
}

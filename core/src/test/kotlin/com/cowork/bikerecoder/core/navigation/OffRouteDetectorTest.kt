package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.LocationFix
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OffRouteDetectorTest {

    private val point = pointAt(0.0, 0.0)

    private fun fix(tSeconds: Long, accuracyM: Float = 10f): LocationFix =
        LocationFix(point = point, accuracyM = accuracyM, speedMps = null, bearingDeg = null, timeMillis = tSeconds * 1_000L)

    @Test
    fun `off route after 8 seconds at 40m`() {
        val detector = OffRouteDetector()
        var triggeredAt8 = false
        for (t in 0L..8L) {
            val result = detector.onFix(fix(t), lateralOffsetM = 45.0)
            if (t < 8L) {
                assertFalse(result, "t=$t 에서 이미 true가 반환되면 안 됨")
            } else {
                triggeredAt8 = result
            }
        }
        assertTrue(triggeredAt8, "t=8s 에서는 true여야 함")
    }

    @Test
    fun `7 seconds is not off route`() {
        val detector = OffRouteDetector()
        for (t in 0L..7L) {
            assertFalse(detector.onFix(fix(t), lateralOffsetM = 45.0))
        }
        assertFalse(detector.onFix(fix(8L), lateralOffsetM = 10.0))
    }

    @Test
    fun `single spike ignored`() {
        val detector = OffRouteDetector()
        for (t in 0L..20L) {
            val lateral = if (t == 5L) 80.0 else 0.0
            assertFalse(detector.onFix(fix(t), lateralOffsetM = lateral))
        }
    }

    @Test
    fun `poor accuracy fixes ignored`() {
        val detector = OffRouteDetector()
        for (t in 0L..20L) {
            assertFalse(detector.onFix(fix(t, accuracyM = 50f), lateralOffsetM = 45.0))
        }
    }

    @Test
    fun `no second trigger before reroute finished`() {
        val detector = OffRouteDetector()
        var firstTrue = false
        for (t in 0L..8L) {
            val result = detector.onFix(fix(t), lateralOffsetM = 45.0)
            if (t == 8L) firstTrue = result
        }
        assertTrue(firstTrue)
        for (t in 9L..38L) {
            assertFalse(detector.onFix(fix(t), lateralOffsetM = 45.0), "t=$t 에서 추가 true가 반환되면 안 됨")
        }
    }

    @Test
    fun `respects 15s interval after reroute`() {
        val detector = OffRouteDetector()
        for (t in 0L..8L) {
            detector.onFix(fix(t), lateralOffsetM = 45.0)
        }
        detector.onRerouteFinished(10_000L)

        var firstTrueTimeMs: Long? = null
        for (t in 11L..40L) {
            val result = detector.onFix(fix(t), lateralOffsetM = 45.0)
            if (result && firstTrueTimeMs == null) {
                firstTrueTimeMs = t * 1_000L
            }
        }
        assertTrue(firstTrueTimeMs != null, "재탐색 종료 후 다시 이탈 시 언젠가 true가 반환돼야 함")
        assertTrue(firstTrueTimeMs >= 25_000L, "첫 true 시각은 25s 이상이어야 함, 실제: $firstTrueTimeMs")
    }
}

package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.elevation.Climb
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClimbAnnouncerTest {

    /** 동쪽 [fromEastM]에서 시작하는 5 km 평지 경로의 단면(오르막은 시험이 직접 준다; 단면은 시작 좌표용). */
    private fun profile(fromEastM: Double = 0.0) =
        checkNotNull(ElevationProfile.fromRoute(eastRouteWithElevation(5_000.0, fromEastM) { 20.0 }))

    private fun climbAt(startM: Double) = Climb(startM, startM + 500.0, 25.0, 5.0, 6.0)

    @Test
    fun announcedOnceAtThreeHundred() {
        val a = ClimbAnnouncer()
        val p = profile()
        val climbs = listOf(climbAt(1_000.0))
        assertNull(a.onProgress(climbs, p, 0.0))
        assertNull(a.onProgress(climbs, p, 600.0)) // gap 400
        assertEquals(climbs.single(), a.onProgress(climbs, p, 650.0)) // gap 350
        assertNull(a.onProgress(climbs, p, 700.0)) // gap 300
        assertNull(a.onProgress(climbs, p, 740.0)) // gap 260
    }

    @Test
    fun alreadyCloseIsNotAnnounced() {
        val a = ClimbAnnouncer()
        val p = profile()
        val climbs = listOf(climbAt(1_000.0))
        assertNull(a.onProgress(climbs, p, 720.0)) // 처음 gap 280
        assertNull(a.onProgress(climbs, p, 700.0))
        assertNull(a.onProgress(climbs, p, 740.0))
    }

    @Test
    fun sameClimbAfterRerouteIsNotRepeated() {
        val a = ClimbAnnouncer()
        val first = listOf(climbAt(1_000.0))
        val p1 = profile()
        assertNull(a.onProgress(first, p1, 0.0))
        assertEquals(first.single(), a.onProgress(first, p1, 700.0))

        // 동쪽 500 m에서 다시 시작한 경로: 같은 오르막(동쪽 1,020 m, 시작 20 m 차이)이 520 m에 있다.
        val p2 = profile(fromEastM = 500.0)
        val second = listOf(climbAt(520.0))
        assertNull(a.onProgress(second, p2, 0.0)) // gap 520
        assertNull(a.onProgress(second, p2, 220.0)) // gap 300
    }

    @Test
    fun differentClimbAfterRerouteIsAnnounced() {
        val a = ClimbAnnouncer()
        val first = listOf(climbAt(1_000.0))
        val p1 = profile()
        assertNull(a.onProgress(first, p1, 0.0))
        assertEquals(first.single(), a.onProgress(first, p1, 700.0))

        val p2 = profile(fromEastM = 500.0)
        val second = listOf(climbAt(1_500.0)) // 동쪽 2,000 m
        assertNull(a.onProgress(second, p2, 0.0))
        assertEquals(second.single(), a.onProgress(second, p2, 1_200.0))
    }

    @Test
    fun resetForgets() {
        val a = ClimbAnnouncer()
        val p = profile()
        val climbs = listOf(climbAt(1_000.0))
        assertNull(a.onProgress(climbs, p, 0.0))
        assertEquals(climbs.single(), a.onProgress(climbs, p, 700.0))

        a.reset()
        assertNull(a.onProgress(climbs, p, 500.0))
        assertEquals(climbs.single(), a.onProgress(climbs, p, 700.0))
    }
}

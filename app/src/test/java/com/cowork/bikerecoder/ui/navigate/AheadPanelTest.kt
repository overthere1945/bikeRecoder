package com.cowork.bikerecoder.ui.navigate

import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.navigation.AheadState
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.navigation.Progress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The guidance panel's "앞길 고도" line and strip (V1.7.0 spec §3.2, §3.4). */
class AheadPanelTest {

    /** 6 km with a point every 500 m, rising 5% between 2 and 3 km. */
    private val route: Route = run {
        val n = 13
        Route(
            points = (0 until n).map { GeoPoint(37.5 + it * 0.0045, 127.0) },
            cumulativeM = (0 until n).map { it * 500.0 },
            instructions = emptyList(),
            stopPointIndices = listOf(n - 1),
            summary = RouteSummary(6_000.0, 50, 0.5, RouteProfile.CYCLEWAY_FIRST),
            elevationsM = (0 until n).map { i -> 20.0 + 25.0 * (i - 4).coerceIn(0, 2) },
        )
    }
    private val profile = ElevationProfile.fromRoute(route)!!

    private fun nav(
        d: Double,
        ahead: AheadState? = AheadState.Flat,
        withProfile: Boolean = true,
        rerouting: Boolean = false,
        offsetM: Double = 0.0,
    ) = NavState(
        route = route,
        progress = Progress(d, offsetM, 6_000.0 - d, null, null),
        speedMps = 5.0,
        etaMillis = 0,
        gpsWeak = false,
        rerouting = rerouting,
        profile = if (withProfile) profile else null,
        ahead = ahead,
    )

    @Test
    fun climbAheadLine() {
        val panel = aheadPanel(nav(1_700.0, AheadState.ClimbAhead(inM = 300.0, lengthM = 1_200.0, avgGradePct = 5.6)))
        assertEquals("300m 앞 오르막 · 1.2km · 평균 6%", panel!!.line)
    }

    @Test
    fun inClimbAndFlatLines() {
        assertEquals("오르막 남은 거리 450m · 평균 5%", aheadPanel(nav(2_550.0, AheadState.InClimb(450.0, 5.0)))!!.line)
        assertEquals("앞 2 km 평탄", aheadPanel(nav(4_500.0, AheadState.Flat))!!.line)
    }

    @Test
    fun stripIsTheTwoKilometresAhead() {
        val panel = aheadPanel(nav(1_000.0))!!
        val strip = panel.strip
        assertNotNull(strip)
        assertEquals(2_000.0, strip!!.totalM, 1e-6)
        assertFalse(panel.reachesDestination)
        assertFalse(panel.dimmed)
    }

    @Test
    fun destinationWithinTwoKilometresShowsTheFlag() {
        val panel = aheadPanel(nav(4_000.0))!!
        assertTrue(panel.reachesDestination)
        assertEquals(2_000.0, panel.strip!!.totalM, 1e-6)
        assertTrue(aheadPanel(nav(4_500.0))!!.reachesDestination)
        assertEquals(1_500.0, aheadPanel(nav(4_500.0))!!.strip!!.totalM, 1e-6)
        assertFalse(aheadPanel(nav(3_999.0))!!.reachesDestination)
    }

    @Test
    fun dimmedWhileReroutingOrOffRoute() {
        assertTrue(aheadPanel(nav(1_000.0, rerouting = true))!!.dimmed)
        assertTrue(aheadPanel(nav(1_000.0, offsetM = 40.0))!!.dimmed)
        assertFalse(aheadPanel(nav(1_000.0, offsetM = 39.9))!!.dimmed)
    }

    @Test
    fun nothingWithoutElevation() {
        assertNull(aheadPanel(nav(1_000.0, ahead = null, withProfile = false)))
        assertNull(aheadPanel(nav(1_000.0, ahead = null)))
    }
}

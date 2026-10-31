package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.elevation.Climb
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AheadOfRiderTest {

    private val climb = Climb(startM = 1_500.0, endM = 2_100.0, gainM = 30.0, avgGradePct = 5.0, maxGradePct = 7.0)

    private fun flat(lengthM: Double) =
        checkNotNull(ElevationProfile.fromRoute(eastRouteWithElevation(lengthM) { 20.0 }))

    @Test
    fun inClimbRemaining() {
        assertEquals(AheadState.InClimb(remainingM = 400.0, avgGradePct = 5.0), AheadOfRider.state(listOf(climb), 1_700.0))
        assertEquals(AheadState.InClimb(remainingM = 600.0, avgGradePct = 5.0), AheadOfRider.state(listOf(climb), 1_500.0))
    }

    @Test
    fun climbWithinTwoKm() {
        assertEquals(
            AheadState.ClimbAhead(inM = 1_500.0, lengthM = 600.0, avgGradePct = 5.0),
            AheadOfRider.state(listOf(climb), 0.0),
        )
        val far = climb.copy(startM = 5_000.0, endM = 5_500.0)
        assertEquals(
            AheadState.ClimbAhead(inM = 2_000.0, lengthM = 500.0, avgGradePct = 5.0),
            AheadOfRider.state(listOf(far), 3_000.0),
        )
    }

    @Test
    fun climbBeyondTwoKmIsFlat() {
        assertEquals(AheadState.Flat, AheadOfRider.state(listOf(climb.copy(startM = 2_100.0, endM = 2_600.0)), 0.0))
        assertEquals(AheadState.Flat, AheadOfRider.state(listOf(climb), 2_200.0)) // 지나온 오르막
        assertEquals(AheadState.Flat, AheadOfRider.state(emptyList(), 0.0))
    }

    @Test
    fun stripStopsAtDestination() {
        val profile = flat(3_000.0)
        val strip = assertNotNull(AheadOfRider.strip(profile, 2_000.0))
        assertEquals(profile.totalM - 2_000.0, strip.totalM, 1e-6)
        assertEquals(profile.elevationAt(profile.totalM), strip.elevationAt(strip.totalM), 1e-9)
    }

    @Test
    fun stripIsTwoKm() {
        val strip = assertNotNull(AheadOfRider.strip(flat(5_000.0), 500.0))
        assertEquals(2_000.0, strip.totalM, 1e-6)
    }
}

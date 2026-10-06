package com.cowork.bikerecoder.nav

import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.navigation.Progress
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.routing.BRouterGeoJsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavNotificationsTest {

    private val route = BRouterGeoJsonParser.parse(fixture("route_follow.geojson"), RouteProfile.BALANCED, emptyList())

    private fun active(progress: Progress) = NavUiState.Active(
        tripId = 1,
        type = TripType.SINGLE_DAY,
        state = NavState(route, progress, speedMps = 5.0, etaMillis = 0, gpsWeak = false, rerouting = false),
        stops = emptyList(),
        fix = null,
        muted = false,
    )

    @Test
    fun activeShowsTheNextTurnAndTheRemainingDistance() {
        val progress = Progress(500.0, 0.0, 1_900.0, route.instructions.first(), 300.0)

        assertEquals("300m 앞 우회전 · 남은 1.9km", NavNotifications.contentText(active(progress)))
    }

    @Test
    fun afterTheLastTurnShowsTheDestination() {
        val progress = Progress(2_000.0, 0.0, 400.0, null, null)

        assertEquals("목적지까지 · 남은 400m", NavNotifications.contentText(active(progress)))
    }

    @Test
    fun startingShowsRouteComputation() {
        assertEquals("경로를 계산하는 중…", NavNotifications.contentText(NavUiState.Starting(1)))
    }
}

package com.cowork.bikerecoder.map

import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The gradient route line's colours (V1.7.0 spec §3.1): one "#RRGGBB" per route point from the profile's band. */
class RouteColorsTest {

    /** 2 km north in 50 m steps: flat for the first 1 km, then 7% up (UP_6, red). */
    private val route: Route = run {
        val n = 41
        val cumulative = List(n) { it * 50.0 }
        val points = cumulative.map { GeoPoint(37.5 + it / 111_320.0, 127.0) }
        val elevations = cumulative.map { d -> if (d <= 1_000.0) 20.0 else 20.0 + (d - 1_000.0) * 0.07 }
        Route(
            points = points,
            cumulativeM = cumulative,
            instructions = emptyList(),
            stopPointIndices = listOf(n - 1),
            summary = RouteSummary(cumulative.last(), 70, 0.0, RouteProfile.BALANCED),
            elevationsM = elevations,
        )
    }

    @Test
    fun flatUsesOverride() {
        val profile = ElevationProfile.fromRoute(route)!!

        val override = routeColors(route, profile, flatHex = "#123456")!!
        val table = routeColors(route, profile, flatHex = null)!!

        assertEquals(route.points.size, override.size)
        assertEquals("#123456", override[0])
        assertEquals("#123456", override[10])
        // Without an override FLAT is the table's blue.
        assertEquals("#1E63D6", table[0])
        assertEquals("#1E63D6", table[10])
    }

    @Test
    fun climbPointsColored() {
        val profile = ElevationProfile.fromRoute(route)!!

        val colors = routeColors(route, profile, flatHex = "#123456")!!

        // 1,500 m is well inside the 7% climb: UP_6 red, never the FLAT override.
        assertEquals("#E53935", colors[30])
        assertEquals("#E53935", colors[35])
    }

    @Test
    fun nullProfileGivesNull() {
        assertNull(routeColors(route, null, flatHex = "#123456"))
        assertNull(routeColors(route, null, flatHex = null))
    }

    @Test
    fun pathColorsFollowTheProfileAxisAcrossPaths() {
        val profile = ElevationProfile.fromRoute(route)!!
        val first = route.points.subList(0, 21)
        val second = route.points.subList(20, 41)

        val colors = pathColors(listOf(first, second), profile)

        assertEquals(listOf(21, 21), colors.map { it.size })
        assertEquals("#1E63D6", colors[0][0])
        // The second path starts at 1,000 m on the profile axis (no distance across the gap): its 10th point is at 1,500 m.
        assertEquals("#E53935", colors[1][10])
    }
}

package com.cowork.bikerecoder.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource

/** The plan screen's map: route and stops, FREE camera that frames the route when a new one arrives. */
@Composable
fun PlanMap(state: PlanUiState, user: LocationFix?, tileSource: TileSource, modifier: Modifier) {
    val route = (state.route as? RouteUiState.Ready)?.route
    val stops = remember(state.stops) {
        state.stops.mapIndexed { i, s -> Stop(s.key, s.name, s.point, isDestination = i == state.stops.lastIndex) }
    }
    // A new instance per route (or per stop list while there is none) is a new framing request.
    val fit = remember(route, if (route == null) state.stops else null) {
        val points = route?.points ?: state.stops.map { it.point }
        if (points.isEmpty()) null else CameraFit(points)
    }
    BikeMap(
        tileSource = tileSource,
        overlay = MapOverlay(route = route, stops = stops, user = user),
        cameraMode = CameraMode.FREE,
        onLongPress = {},
        onUserGesture = {},
        modifier = modifier,
        initialCenter = user?.point,
        fit = fit,
    )
}

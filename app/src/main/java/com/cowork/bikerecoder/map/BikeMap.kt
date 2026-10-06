package com.cowork.bikerecoder.map

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.Stop
import kotlinx.coroutines.CancellationException
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** What BikeMap draws on top of the base map. */
data class MapOverlay(val route: Route?, val stops: List<Stop>, val user: LocationFix?)

enum class CameraMode {
    /** The user controls the camera; BikeMap never moves it on its own. */
    FREE,

    /** Camera tracks the user: zoom 17, tilt 50, bearing = direction of travel. */
    FOLLOW,
}

private const val ROUTE_SOURCE = "bike-route"
private const val STOPS_SOURCE = "bike-stops"
private const val USER_SOURCE = "bike-user"
private const val ROUTE_COLOR = "#1E88E5"
private const val FOLLOW_ZOOM = 17.0
private const val FOLLOW_TILT = 50.0
private const val FOLLOW_ANIMATION_MS = 800
private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

@Composable
fun BikeMap(
    tileSource: TileSource,
    overlay: MapOverlay,
    cameraMode: CameraMode,
    onLongPress: (GeoPoint) -> Unit,
    onUserGesture: () -> Unit,
    modifier: Modifier = Modifier,
    initialCenter: GeoPoint? = null,
    initialZoom: Double = 13.0,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnUserGesture by rememberUpdatedState(onUserGesture)

    val styleResult by produceState<Result<String>?>(initialValue = null, tileSource) {
        value = try {
            Result.success(tileSource.styleJson())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // MapView lifecycle follows the host lifecycle; on leaving the composition it is driven down to destroyed.
    DisposableEffect(lifecycleOwner, mapView) {
        var created = false
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) {
                    created = true
                    mapView.onCreate(null)
                }
                Lifecycle.Event.ON_START -> if (!started) {
                    started = true
                    mapView.onStart()
                }
                Lifecycle.Event.ON_RESUME -> if (!resumed) {
                    resumed = true
                    mapView.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    resumed = false
                    mapView.onPause()
                }
                Lifecycle.Event.ON_STOP -> if (started) {
                    started = false
                    mapView.onStop()
                }
                Lifecycle.Event.ON_DESTROY -> if (created) {
                    created = false
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            if (created) mapView.onDestroy()
        }
    }

    // Map handle plus gesture wiring, once.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.addOnMapLongClickListener { latLng ->
                currentOnLongPress(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                // Fires only for user gestures, never for programmatic camera animations.
                override fun onMoveBegin(detector: MoveGestureDetector) = currentOnUserGesture()
                override fun onMove(detector: MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            })
            if (initialCenter != null) {
                m.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(initialCenter.lat, initialCenter.lon), initialZoom),
                )
            }
            map = m
        }
    }

    // Load the style (and overlay sources/layers) when both the map and the style JSON are ready.
    val styleJson = styleResult?.getOrNull()
    LaunchedEffect(map, styleJson) {
        val m = map ?: return@LaunchedEffect
        val json = styleJson ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { loaded ->
            installOverlayLayers(loaded)
            style = loaded
        }
    }

    // Overlay data goes through GeoJsonSource updates only; the style is never reloaded for it.
    LaunchedEffect(style, overlay) {
        val loaded = style ?: return@LaunchedEffect
        loaded.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(routeGeoJson(overlay.route))
        loaded.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(stopsGeoJson(overlay.stops))
        loaded.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(userGeoJson(overlay.user))
    }

    // FOLLOW: animate to the user. FREE: no automatic camera moves.
    LaunchedEffect(map, cameraMode, overlay.user) {
        val m = map ?: return@LaunchedEffect
        val fix = overlay.user ?: return@LaunchedEffect
        if (cameraMode != CameraMode.FOLLOW) return@LaunchedEffect
        val position = CameraPosition.Builder()
            .target(LatLng(fix.point.lat, fix.point.lon))
            .zoom(FOLLOW_ZOOM)
            .tilt(FOLLOW_TILT)
            .bearing(fix.bearingDeg?.toDouble() ?: m.cameraPosition.bearing)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(position), FOLLOW_ANIMATION_MS)
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Text(
            text = tileSource.attribution,
            fontSize = 10.sp,
            color = androidx.compose.ui.graphics.Color.DarkGray,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(4.dp)
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (styleResult?.isFailure == true) {
            Text(
                text = "지도를 불러오지 못했습니다",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private fun installOverlayLayers(style: Style) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE, EMPTY_COLLECTION))
    style.addSource(GeoJsonSource(STOPS_SOURCE, EMPTY_COLLECTION))
    style.addSource(GeoJsonSource(USER_SOURCE, EMPTY_COLLECTION))

    style.addLayer(
        LineLayer("$ROUTE_SOURCE-line", ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(Color.parseColor(ROUTE_COLOR)),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    // Destination is red, intermediate waypoints orange.
    style.addLayer(
        CircleLayer("$STOPS_SOURCE-circle", STOPS_SOURCE).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(
                Expression.switchCase(
                    Expression.eq(Expression.get("dest"), Expression.literal(true)),
                    Expression.color(Color.parseColor("#E53935")),
                    Expression.color(Color.parseColor("#FB8C00")),
                ),
            ),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    style.addLayer(
        CircleLayer("$USER_SOURCE-circle", USER_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Color.parseColor("#2979FF")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

private fun coordinate(p: GeoPoint) = "[${p.lon},${p.lat}]"

private fun routeGeoJson(route: Route?): String {
    val points = route?.points
    if (points == null || points.size < 2) return EMPTY_COLLECTION
    val coords = points.joinToString(",", transform = ::coordinate)
    return """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
}

private fun stopsGeoJson(stops: List<Stop>): String {
    if (stops.isEmpty()) return EMPTY_COLLECTION
    val features = stops.joinToString(",") {
        """{"type":"Feature","properties":{"dest":${it.isDestination}},"geometry":{"type":"Point","coordinates":${coordinate(it.point)}}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun userGeoJson(user: LocationFix?): String {
    if (user == null) return EMPTY_COLLECTION
    return """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":${coordinate(user.point)}}}"""
}

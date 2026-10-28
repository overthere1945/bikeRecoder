package com.cowork.bikerecoder.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.ShoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * What BikeMap draws on top of the base map. [track]: the recorded path so far (a red line under the user dot).
 * [trackColors]: a colour ("#RRGGBB") per point of [track], same length, e.g. the heart-rate colours (spec §3.3); the
 * piece from one point to the next takes the first point's colour, and points of one colour in a row are drawn as one
 * line. Null, or a list of another length, draws the track red.
 * [lines]: more recorded paths drawn like [track], each on its own (nothing joins one to the next), e.g. a trip's
 * segments (ruling R10).
 * [photos]: round previews of ride photos where they were taken (spec §2.3).
 * [rides]: the cumulative map's rides (spec §6.4–§6.5), newest first, each in its own colour under the track: width 4,
 * opacity 0.8; with [selectedRide] that ride is width 6, opacity 1 and on top, the others opacity 0.25. Unlike the
 * track they are redrawn as soon as they change. A ride of fewer than 2 points is not drawn.
 */
data class MapOverlay(
    val route: Route?,
    val stops: List<Stop>,
    val user: LocationFix?,
    val track: List<GeoPoint> = emptyList(),
    val photos: List<MapPhoto> = emptyList(),
    val trackColors: List<String>? = null,
    val lines: List<MapLine> = emptyList(),
    val rides: List<RideLine> = emptyList(),
    val selectedRide: Long? = null,
)

/**
 * One recorded path of [MapOverlay.lines], drawn in the track's style. [colors]: as [MapOverlay.trackColors], for
 * [points] (null = the track's red).
 */
data class MapLine(val points: List<GeoPoint>, val colors: List<String>? = null)

/** One ride of [MapOverlay.rides]: its segment, path and colour ("#RRGGBB"). */
data class RideLine(val segmentId: Long, val points: List<GeoPoint>, val color: String)

/** A ride photo on the map: a round preview [MAP_PHOTO_DIAMETER] wide with a white border; a grey disc while [thumbnail] is null. */
data class MapPhoto(val photoId: Long, val point: GeoPoint, val thumbnail: ImageBitmap?)

/** The diameter of a photo preview on the map (spec §2.3). */
val MAP_PHOTO_DIAMETER = 40.dp
private val MAP_PHOTO_BORDER = 2.dp

/**
 * One-shot request to frame [points] (a single point just centres on it, at most [maxZoom]).
 * [padding]: room kept free on each side when framing several points, e.g. under controls drawn over the map; null =
 * the usual margin all round.
 * Deliberately has no equals: every new instance is a new request, even for identical points.
 */
class CameraFit(val points: List<GeoPoint>, val maxZoom: Double = 16.0, val padding: FitPadding? = null)

/** Per-side room for [CameraFit.padding] (start = left, end = right). */
data class FitPadding(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

enum class CameraMode {
    /** The user controls the camera; BikeMap never moves it on its own. */
    FREE,

    /** Camera tracks the user: zoom 17, tilt 50, bearing = direction of travel. */
    FOLLOW,
}

private const val ROUTE_SOURCE = "bike-route"
private const val STOPS_SOURCE = "bike-stops"
private const val USER_SOURCE = "bike-user"
private const val TRACK_SOURCE = "bike-track"
private const val RIDES_SOURCE = "bike-rides"
private const val RIDES_LAYER = "$RIDES_SOURCE-line"
private const val PHOTOS_SOURCE = "bike-photos"
private const val PHOTOS_LAYER = "$PHOTOS_SOURCE-symbol"
private const val PHOTO_PLACEHOLDER_COLOR = "#9E9E9E"
private const val ROUTE_COLOR = "#1E88E5"
private const val TRACK_COLOR = "#E53935"
private const val RIDE_WIDTH = 4.0
private const val RIDE_OPACITY = 0.8
private const val SELECTED_RIDE_WIDTH = 6.0
private const val SELECTED_RIDE_OPACITY = 1.0
private const val DIMMED_RIDE_OPACITY = 0.25

/** Half the side of the square around a tap in which a ride counts as tapped (the lines are only 4 wide). */
private val RIDE_TAP_SLOP = 12.dp
private const val FOLLOW_ZOOM = 17.0
private const val FOLLOW_TILT = 50.0
private const val FOLLOW_ANIMATION_MS = 800
private const val FIT_ANIMATION_MS = 600
private const val FIT_PADDING_PX = 120
private const val TRACK_UPDATE_MS = 5_000L
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
    fit: CameraFit? = null,
    /** The camera stopped (after a gesture or an animation), with the point at the map's center. */
    onCameraIdle: ((GeoPoint) -> Unit)? = null,
    /** The camera started moving, for any reason (gesture, animation, or a programmatic move). */
    onCameraMoveStarted: (() -> Unit)? = null,
    /** A tap on a photo preview of [MapOverlay.photos], with its [MapPhoto.photoId]. */
    onPhotoClick: ((Long) -> Unit)? = null,
    /**
     * A tap on the map with [MapOverlay.rides] tappable: the ride tapped ([pickRide] where several are), or null for a
     * spot with none.
     */
    onRideClick: ((Long?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnUserGesture by rememberUpdatedState(onUserGesture)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val currentOnCameraMoveStarted by rememberUpdatedState(onCameraMoveStarted)
    val currentOnPhotoClick by rememberUpdatedState(onPhotoClick)
    val currentOnRideClick by rememberUpdatedState(onRideClick)
    val currentRides by rememberUpdatedState(overlay.rides)
    val currentSelectedRide by rememberUpdatedState(overlay.selectedRide)
    val rideTapSlopPx = with(LocalDensity.current) { RIDE_TAP_SLOP.toPx() }

    var styleAttempt by remember { mutableIntStateOf(0) }
    val styleResult by produceState<Result<String>?>(initialValue = null, tileSource, styleAttempt) {
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
            m.addOnMapClickListener { latLng ->
                val at = m.projection.toScreenLocation(latLng)
                currentOnPhotoClick?.let { onPhoto ->
                    val tapped = m.queryRenderedFeatures(at, PHOTOS_LAYER).firstOrNull()
                    val photoId = tapped?.getNumberProperty(PHOTO_ID)?.toLong()
                    if (photoId != null) {
                        onPhoto(photoId)
                        return@addOnMapClickListener true
                    }
                }
                val onRide = currentOnRideClick ?: return@addOnMapClickListener false
                val box = RectF(at.x - rideTapSlopPx, at.y - rideTapSlopPx, at.x + rideTapSlopPx, at.y + rideTapSlopPx)
                val hits = m.queryRenderedFeatures(box, RIDES_LAYER)
                    .mapNotNull { f -> if (f.hasProperty(RIDE_ID)) f.getNumberProperty(RIDE_ID).toLong() else null }
                    .distinct()
                onRide(pickRide(hits, currentSelectedRide, currentRides.map { it.segmentId }))
                true
            }
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                // Fires only for user gestures, never for programmatic camera animations.
                override fun onMoveBegin(detector: MoveGestureDetector) = currentOnUserGesture()
                override fun onMove(detector: MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            })
            // Pinch, rotate and tilt are user gestures too (FOLLOW must not snap back after them).
            m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: StandardScaleGestureDetector) = currentOnUserGesture()
                override fun onScale(detector: StandardScaleGestureDetector) = Unit
                override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
            })
            m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: RotateGestureDetector) = currentOnUserGesture()
                override fun onRotate(detector: RotateGestureDetector) = Unit
                override fun onRotateEnd(detector: RotateGestureDetector) = Unit
            })
            m.addOnShoveListener(object : MapLibreMap.OnShoveListener {
                override fun onShoveBegin(detector: ShoveGestureDetector) = currentOnUserGesture()
                override fun onShove(detector: ShoveGestureDetector) = Unit
                override fun onShoveEnd(detector: ShoveGestureDetector) = Unit
            })
            // MapLibre calls these on the main thread; registered before the first camera move so its stop is seen too.
            m.addOnCameraMoveStartedListener { currentOnCameraMoveStarted?.invoke() }
            m.addOnCameraIdleListener {
                currentOnCameraIdle?.let { idle ->
                    val target = m.cameraPosition.target ?: return@let
                    idle(GeoPoint(target.latitude, target.longitude))
                }
            }
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

    // Overlay data goes through GeoJsonSource updates only; the style is never reloaded for it. Each source is
    // updated only when its own data changes, so a new fix does not re-upload the whole route.
    LaunchedEffect(style, overlay.route) {
        style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(routeGeoJson(overlay.route))
    }
    LaunchedEffect(style, overlay.stops) {
        style?.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(stopsGeoJson(overlay.stops))
    }
    // The recorded track grows with every ride point: it is redrawn at most every TRACK_UPDATE_MS (always ending with
    // the latest track), its GeoJSON built off the main thread (the track lists are immutable snapshots). Its colours
    // and the other lines go to the same source.
    // Only the live track's growth is throttled: new colours ([기본] ↔ [심박]) or lines are drawn at once.
    val currentTrack by rememberUpdatedState(TrackDrawing(overlay.track, overlay.trackColors, overlay.lines))
    LaunchedEffect(style) {
        val source = style?.getSourceAs<GeoJsonSource>(TRACK_SOURCE) ?: return@LaunchedEffect
        snapshotFlow { currentTrack }
            .atMostEveryUnless(TRACK_UPDATE_MS, SystemClock::uptimeMillis, ::redrawAtOnce)
            .collect { d -> source.setGeoJson(withContext(Dispatchers.Default) { trackGeoJson(d.track, d.colors, d.lines) }) }
    }
    // Many long paths: built off the main thread; a newer list or highlight cancels the one still being built.
    LaunchedEffect(style, overlay.rides, overlay.selectedRide) {
        val source = style?.getSourceAs<GeoJsonSource>(RIDES_SOURCE) ?: return@LaunchedEffect
        val rides = overlay.rides
        val selected = overlay.selectedRide
        source.setGeoJson(withContext(Dispatchers.Default) { ridesGeoJson(rides, selected) })
    }
    LaunchedEffect(style, overlay.user) {
        style?.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(userGeoJson(overlay.user))
    }
    // One style image per photo ("photo-<id>"), re-made only when its thumbnail changes; images of photos no longer
    // shown are removed. A new style starts without images, so the bookkeeping is per style.
    val density = LocalDensity.current
    val markerPx = with(density) { MAP_PHOTO_DIAMETER.roundToPx() }
    val borderPx = with(density) { MAP_PHOTO_BORDER.toPx() }
    val photoImages = remember(style) { mutableMapOf<Long, ImageBitmap?>() }
    LaunchedEffect(style, overlay.photos) {
        val loaded = style ?: return@LaunchedEffect
        val wanted = overlay.photos.associateBy { it.photoId }
        // New images before the source names them, stale ones only after it no longer does.
        wanted.values.forEach { photo ->
            if (photoImages.containsKey(photo.photoId) && photoImages[photo.photoId] === photo.thumbnail) return@forEach
            loaded.addImage(photoImageName(photo.photoId), photoMarker(photo.thumbnail, markerPx, borderPx))
            photoImages[photo.photoId] = photo.thumbnail
        }
        loaded.getSourceAs<GeoJsonSource>(PHOTOS_SOURCE)?.setGeoJson(photosGeoJson(overlay.photos))
        (photoImages.keys - wanted.keys).forEach { id ->
            loaded.removeImage(photoImageName(id))
            photoImages.remove(id)
        }
    }

    // Frame the requested points once the style (and so the map size) is ready.
    LaunchedEffect(style, fit) {
        val m = map ?: return@LaunchedEffect
        if (style == null || fit == null || fit.points.isEmpty()) return@LaunchedEffect
        val single = fit.points.first()
        try {
            if (fit.points.size == 1) {
                m.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(single.lat, single.lon),
                        m.cameraPosition.zoom.coerceAtLeast(14.0).coerceAtMost(fit.maxZoom),
                    ),
                    FIT_ANIMATION_MS,
                )
            } else {
                val bounds = LatLngBounds.Builder().apply {
                    fit.points.forEach { include(LatLng(it.lat, it.lon)) }
                }.build()
                val pad = fit.padding
                val update = if (pad == null) {
                    CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX)
                } else {
                    with(density) {
                        CameraUpdateFactory.newLatLngBounds(
                            bounds, pad.start.roundToPx(), pad.top.roundToPx(), pad.end.roundToPx(), pad.bottom.roundToPx(),
                        )
                    }
                }
                m.animateCamera(update, FIT_ANIMATION_MS)
            }
        } catch (e: IllegalStateException) {
            // Degenerate bounds (identical points) or a not-yet-sized map: leave the camera alone.
        }
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
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "지도를 불러오지 못했습니다")
                TextButton(onClick = { styleAttempt++ }) { Text("다시 시도") }
            }
        }
    }
}

private fun installOverlayLayers(style: Style) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE, EMPTY_COLLECTION))
    style.addSource(GeoJsonSource(STOPS_SOURCE, EMPTY_COLLECTION))
    style.addSource(GeoJsonSource(USER_SOURCE, EMPTY_COLLECTION))
    style.addSource(GeoJsonSource(RIDES_SOURCE, EMPTY_COLLECTION))
    style.addSource(GeoJsonSource(TRACK_SOURCE, EMPTY_COLLECTION))
    style.addSource(GeoJsonSource(PHOTOS_SOURCE, EMPTY_COLLECTION))

    style.addLayer(
        LineLayer("$ROUTE_SOURCE-line", ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(Color.parseColor(ROUTE_COLOR)),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    // The rides: above the planned route, below the track; each its own colour, width and opacity ([ridesGeoJson]),
    // the highlighted one on top.
    style.addLayer(
        LineLayer(RIDES_LAYER, RIDES_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get(LINE_COLOR))),
            PropertyFactory.lineWidth(Expression.toNumber(Expression.get(RIDE_WIDTH_KEY))),
            PropertyFactory.lineOpacity(Expression.toNumber(Expression.get(RIDE_OPACITY_KEY))),
            // Butt ends and mitred corners: round ones overlap the see-through line itself and show as darker beads.
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_MITER),
        ),
    )
    // The recorded path: above the planned route, below the stops and the user dot; each line its own colour.
    style.addLayer(
        LineLayer("$TRACK_SOURCE-line", TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(
                Expression.toColor(Expression.coalesce(Expression.get(LINE_COLOR), Expression.literal(TRACK_COLOR))),
            ),
            PropertyFactory.lineWidth(5f),
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
    // Photo previews: above the line and the stops, below the user dot; all of them always drawn.
    style.addLayer(
        SymbolLayer(PHOTOS_LAYER, PHOTOS_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get(PHOTO_IMAGE)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
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

private fun routeGeoJson(route: Route?): String = lineGeoJson(route?.points.orEmpty())

/** What the track source draws (compared by value, so an unchanged overlay is not drawn again). */
internal data class TrackDrawing(val track: List<GeoPoint>, val colors: List<String>?, val lines: List<MapLine>)

/**
 * Whether [next] is drawn at once instead of waiting for the track throttle: anything but the live track itself
 * changing (its colours, or the separate lines).
 */
internal fun redrawAtOnce(last: TrackDrawing, next: TrackDrawing): Boolean =
    next.colors != last.colors || next.lines != last.lines

private const val LINE_COLOR = "color"

/**
 * The recorded path as a LineString ([lon,lat] pairs); fewer than 2 points draw nothing. With [colors] or [lines]: a
 * collection of LineStrings with a "color" each — the track and then every line, each cut where its colour changes
 * (the point where it changes ends one piece and starts the next).
 */
internal fun trackGeoJson(
    points: List<GeoPoint>,
    colors: List<String>? = null,
    lines: List<MapLine> = emptyList(),
): String {
    if (colors == null && lines.isEmpty()) return lineGeoJson(points)
    val features = (listOf(MapLine(points, colors)) + lines).flatMap(::colorRuns)
    if (features.isEmpty()) return EMPTY_COLLECTION
    return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

/** [line] as one LineString feature per run of one colour; nothing below 2 points. */
private fun colorRuns(line: MapLine): List<String> {
    val points = line.points
    if (points.size < 2) return emptyList()
    val colors = line.colors?.takeIf { it.size == points.size } ?: return listOf(colorFeature(points, TRACK_COLOR))
    val features = mutableListOf<String>()
    var start = 0
    for (i in 1 until points.size) {
        if (colors[i] != colors[start]) {
            features += colorFeature(points.subList(start, i + 1), colors[start])
            start = i
        }
    }
    if (start < points.size - 1) features += colorFeature(points.subList(start, points.size), colors[start])
    return features
}

private fun colorFeature(points: List<GeoPoint>, color: String): String {
    val coords = points.joinToString(",", transform = ::coordinate)
    return """{"type":"Feature","properties":{"$LINE_COLOR":"$color"},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
}

private const val RIDE_ID = "segmentId"
private const val RIDE_SELECTED = "selected"
private const val RIDE_WIDTH_KEY = "width"
private const val RIDE_OPACITY_KEY = "opacity"

/**
 * [rides] (newest first) as one LineString feature each with its segmentId, colour, whether it is [selected], and its
 * width and opacity: 4 and 0.8 with nothing selected; with a selection 6 and 1 for it, 0.25 for the rest. Oldest first,
 * so newer rides are drawn over older ones, and the selected one last (on top). Rides of fewer than 2 points are left
 * out; empty if none is left.
 */
internal fun ridesGeoJson(rides: List<RideLine>, selected: Long?): String {
    val drawn = rides.filter { it.points.size >= 2 }
    if (drawn.isEmpty()) return EMPTY_COLLECTION
    val ordered = drawn.asReversed().filter { it.segmentId != selected } + drawn.filter { it.segmentId == selected }
    val out = StringBuilder("""{"type":"FeatureCollection","features":[""")
    ordered.forEachIndexed { i, ride ->
        val isSelected = ride.segmentId == selected
        val width = if (isSelected) SELECTED_RIDE_WIDTH else RIDE_WIDTH
        val opacity = when {
            selected == null -> RIDE_OPACITY
            isSelected -> SELECTED_RIDE_OPACITY
            else -> DIMMED_RIDE_OPACITY
        }
        if (i > 0) out.append(',')
        out.append("""{"type":"Feature","properties":{"$RIDE_ID":${ride.segmentId},"$LINE_COLOR":"${ride.color}",""")
        out.append(""""$RIDE_SELECTED":$isSelected,"$RIDE_WIDTH_KEY":$width,"$RIDE_OPACITY_KEY":$opacity},""")
        out.append(""""geometry":{"type":"LineString","coordinates":[""")
        ride.points.forEachIndexed { j, p ->
            if (j > 0) out.append(',')
            out.append(coordinate(p))
        }
        out.append("]}}")
    }
    return out.append("]}").toString()
}

/**
 * Which ride a tap means, given the rides found around it ([hitSegmentIds]): the highlighted one ([selected]) if it is
 * among them, since it is drawn on top; otherwise the newest of them by [rideOrder] (newest first, as drawn: newer
 * over older). Null for a spot with no ride.
 */
internal fun pickRide(hitSegmentIds: List<Long>, selected: Long?, rideOrder: List<Long>): Long? {
    if (hitSegmentIds.isEmpty()) return null
    if (selected != null && selected in hitSegmentIds) return selected
    return rideOrder.firstOrNull { it in hitSegmentIds } ?: hitSegmentIds.first()
}

private fun lineGeoJson(points: List<GeoPoint>): String {
    if (points.size < 2) return EMPTY_COLLECTION
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

private const val PHOTO_ID = "photoId"
private const val PHOTO_IMAGE = "image"

private fun photoImageName(photoId: Long) = "photo-$photoId"

/** The photos as points with their id and style image name ([photoImageName]). */
internal fun photosGeoJson(photos: List<MapPhoto>): String {
    if (photos.isEmpty()) return EMPTY_COLLECTION
    val features = photos.joinToString(",") {
        """{"type":"Feature","properties":{"$PHOTO_ID":${it.photoId},"$PHOTO_IMAGE":"${photoImageName(it.photoId)}"},""" +
            """"geometry":{"type":"Point","coordinates":${coordinate(it.point)}}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/** A [diameterPx] disc: white, with [thumbnail] centre-cropped inside a [borderPx] white ring (grey without one). */
private fun photoMarker(thumbnail: ImageBitmap?, diameterPx: Int, borderPx: Float): Bitmap {
    val marker = Bitmap.createBitmap(diameterPx, diameterPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(marker)
    val radius = diameterPx / 2f
    val inner = radius - borderPx
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    canvas.drawCircle(radius, radius, radius, paint)
    val source = thumbnail?.asAndroidBitmap()?.let {
        // A hardware bitmap cannot be drawn into a software canvas.
        if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
    }
    if (source == null || source.width == 0 || source.height == 0) {
        paint.color = Color.parseColor(PHOTO_PLACEHOLDER_COLOR)
    } else {
        val scale = 2 * inner / minOf(source.width, source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(radius - source.width * scale / 2, radius - source.height * scale / 2)
        }
        paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
    }
    canvas.drawCircle(radius, radius, inner, paint)
    return marker
}

package com.cowork.bikerecoder.ui.pick

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.ui.common.GlyphIcon

object MapPickTags {
    /** The area the map fills; the camera center is its center. */
    const val MAP = "map-pick-map"

    /** The fixed pin; its bottom edge (the tip) is the map center. */
    const val PIN = "map-pick-pin"
    const val ADDRESS = "map-pick-address"
    const val CONFIRM = "map-pick-confirm"
}

private val PIN_WIDTH = 36.dp
private val PIN_HEIGHT = 48.dp
private const val PICK_ZOOM = 16.0

/**
 * "지도에서 선택" (spec §8.5): the user moves [map] under a fixed 📍 whose tip is the map center; the card shows that
 * point's address ([MapPickState.title]) and [이 위치로 설정], which only works on a stopped map.
 *
 * [map] draws the map into the given modifier (a slot so tests can render the card without MapLibre).
 */
@Composable
fun MapPickScreen(
    state: MapPickState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    map: @Composable (Modifier) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
            ) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Text("지도에서 선택", style = MaterialTheme.typography.titleLarge)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(MapPickTags.MAP)) {
                map(Modifier.fillMaxSize())
                // Centered, then lifted by half its height: its bottom edge, where the tip is drawn, is the center.
                CenterPin(Modifier.align(Alignment.Center).offset(y = -PIN_HEIGHT / 2).testTag(MapPickTags.PIN))
            }
            Surface(tonalElevation = 2.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        state.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(MapPickTags.ADDRESS),
                    )
                    Button(
                        onClick = onConfirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.fillMaxWidth().testTag(MapPickTags.CONFIRM),
                    ) { Text("이 위치로 설정") }
                }
            }
        }
    }
}

/** A 📍-style pin drawn so that its tip is exactly the bottom center of its bounds (an emoji glyph is not). */
@Composable
private fun CenterPin(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(PIN_WIDTH, PIN_HEIGHT).semantics { contentDescription = "📍" }) {
        val r = size.width / 2
        val cx = size.width / 2
        val tip = Offset(cx, size.height)
        val head = Path().apply {
            moveTo(tip.x, tip.y)
            // Two straight sides from the tip to the widest part of the head, closed over the top by the circle.
            lineTo(cx - r * 0.87f, r * 1.5f)
            lineTo(cx + r * 0.87f, r * 1.5f)
            close()
        }
        val red = Color(0xFFE53935)
        drawPath(head, red)
        drawCircle(red, radius = r, center = Offset(cx, r))
        drawCircle(Color(0xFF8E0000), radius = r * 0.36f, center = Offset(cx, r))
    }
}

/**
 * The map of the pick screen: free camera starting at [initialCenter], the user dot, and the camera's moves and stops
 * reported to [onMoveStarted] / [onCameraIdle].
 */
@Composable
fun MapPickMap(
    tileSource: TileSource,
    user: LocationFix?,
    initialCenter: GeoPoint?,
    onMoveStarted: () -> Unit,
    onCameraIdle: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    BikeMap(
        tileSource = tileSource,
        overlay = MapOverlay(route = null, stops = emptyList(), user = user),
        cameraMode = CameraMode.FREE,
        onLongPress = {},
        onUserGesture = {},
        modifier = modifier,
        initialCenter = initialCenter,
        initialZoom = PICK_ZOOM,
        onCameraIdle = onCameraIdle,
        onCameraMoveStarted = onMoveStarted,
    )
}

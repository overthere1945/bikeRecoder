package com.cowork.bikerecoder.ui.elevation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.elevation.GradeBand
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapLine
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource

/**
 * What the "고도" screen's map shows (V1.7.0 spec §2.2): the [paths] in their grade colours ([colors], one list per
 * path), the start and end of the whole line ([endpoints]) and the scrubbed spot ([marker], null before scrubbing).
 */
class ElevationMapContent(
    val paths: List<List<GeoPoint>>,
    val colors: List<List<String>>,
    val endpoints: Pair<GeoPoint, GeoPoint>?,
    val marker: GeoPoint?,
)

/** The "고도" screen's MapLibre map for [content]: framed on the whole line once, then free to pan and zoom. */
@Composable
fun ElevationMap(content: ElevationMapContent, tileSource: TileSource, modifier: Modifier) {
    val all = remember(content.paths) { content.paths.flatten() }
    val fit = remember(all) { all.takeIf { it.isNotEmpty() }?.let { CameraFit(it) } }
    val lines = remember(content.paths, content.colors) {
        content.paths.mapIndexed { i, path -> MapLine(path, content.colors.getOrNull(i)) }
    }
    BikeMap(
        tileSource = tileSource,
        overlay = MapOverlay(
            route = null, stops = emptyList(), user = null, lines = lines, endpoints = content.endpoints,
            marker = content.marker,
        ),
        cameraMode = CameraMode.FREE,
        onLongPress = {},
        onUserGesture = {},
        modifier = modifier,
        initialCenter = all.firstOrNull(),
        initialZoom = 13.0,
        fit = fit,
    )
}

/**
 * The "고도" map screen (V1.7.0 spec §2.2): the map on the top 60% with the line in the grade colours (FLAT the table's
 * blue), and under it a panel with the title, [×], the big chart and either the 7-band legend or, once scrubbed, the
 * "거리 · 고도 · 경사" line; scrubbing moves a dot on the map (the map does not follow it). With nothing to show it goes
 * back ([onClose], once).
 */
@Composable
fun ElevationMapScreen(
    viewModel: ElevationMapViewModel,
    tileSource: TileSource,
    onClose: () -> Unit,
    map: @Composable (ElevationMapContent, Modifier) -> Unit = { content, modifier -> ElevationMap(content, tileSource, modifier) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)
    when (val s = state) {
        ElevationMapUiState.Closed -> LaunchedEffect(Unit) { currentOnClose() }
        ElevationMapUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is ElevationMapUiState.Ready -> ElevationMapLayout(s, onScrub = viewModel::scrub, onClose = onClose, map = map)
    }
}

@Composable
private fun ElevationMapLayout(
    state: ElevationMapUiState.Ready,
    onScrub: (Double) -> Unit,
    onClose: () -> Unit,
    map: @Composable (ElevationMapContent, Modifier) -> Unit,
) {
    val profile = state.profile
    val endpoints = remember(state.paths) {
        val first = state.paths.firstOrNull { it.isNotEmpty() }?.first()
        val last = state.paths.lastOrNull { it.isNotEmpty() }?.last()
        if (first != null && last != null) first to last else null
    }
    val marker = state.scrubM?.let { profile.pointAt(it) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(MAP_FRACTION).testTag(ElevationTags.MAP)) {
            map(ElevationMapContent(state.paths, state.colors, endpoints, marker), Modifier.fillMaxSize())
        }
        Surface(Modifier.fillMaxWidth().weight(1f), tonalElevation = 2.dp) {
            Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ElevationText.TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.testTag(ElevationTags.CLOSE)) {
                        Icon(Icons.Filled.Close, contentDescription = ElevationText.CLOSE)
                    }
                }
                Column(Modifier.padding(end = 12.dp)) {
                    ElevationChart(profile, height = 160.dp, scrubbable = true, onScrub = onScrub)
                    Spacer(Modifier.height(8.dp))
                    val d = state.scrubM
                    if (d == null) {
                        Legend()
                    } else {
                        Text(
                            ElevationText.scrubLine(d, profile.elevationAt(d), profile.gradeAt(d)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag(ElevationTags.SCRUB_TEXT),
                        )
                    }
                }
            }
        }
    }
}

/** The 7 grade bands' colours and legend texts (spec §1.3), the longer texts given more room. */
@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth()) {
        GradeBand.entries.forEach { band ->
            Column(
                Modifier.weight(legendWeight(band)).padding(horizontal = 1.dp).testTag(ElevationTags.LEGEND_CELL),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(band.color()))
                Text(
                    ElevationText.legend(band),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
        }
    }
}

private fun legendWeight(band: GradeBand): Float = when (band) {
    GradeBand.STEEP_DOWN -> 1.5f
    GradeBand.FLAT -> 1.7f
    else -> 1f
}

/** The map's share of the screen height. */
private const val MAP_FRACTION = 0.6f

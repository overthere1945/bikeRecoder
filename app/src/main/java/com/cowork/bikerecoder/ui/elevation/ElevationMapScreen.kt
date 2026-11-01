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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.elevation.GradeBand
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapLine
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource

/**
 * What the big map screen's map shows (V1.7.0 spec §2.2, V1.8.0 spec §2.4): the [paths] in the current tab's colours
 * ([colors], one list per path), the start and end of the whole line ([endpoints]) and the scrubbed spot ([marker],
 * null before scrubbing).
 */
class ElevationMapContent(
    val paths: List<List<GeoPoint>>,
    val colors: List<List<String>>,
    val endpoints: Pair<GeoPoint, GeoPoint>?,
    val marker: GeoPoint?,
)

/** The big map screen's MapLibre map for [content]: framed on the whole line once, then free to pan and zoom. */
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
 * The big map screen (V1.7.0 spec §2.2, V1.8.0 spec §2.4): the map on the top 60% with the line in the current tab's
 * colours, and under it a panel with the [고도][속도][심박] tabs (only those with data; one alone is a title instead),
 * [×], the tab's big chart and either its legend (7 grade bands or 5 speed / heart-rate bands) or, once scrubbed, the
 * line of what is there; scrubbing moves a dot on the map (the map does not follow it) and stays where it is when the
 * tab changes. With nothing to show it goes back ([onClose], once).
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
        is ElevationMapUiState.Ready -> ElevationMapLayout(
            s, onScrub = viewModel::scrub, onSelectTab = viewModel::selectTab, onClose = onClose, map = map,
        )
    }
}

@Composable
private fun ElevationMapLayout(
    state: ElevationMapUiState.Ready,
    onScrub: (Double) -> Unit,
    onSelectTab: (ChartTab) -> Unit,
    onClose: () -> Unit,
    map: @Composable (ElevationMapContent, Modifier) -> Unit,
) {
    val endpoints = remember(state.paths) {
        val first = state.paths.firstOrNull { it.isNotEmpty() }?.first()
        val last = state.paths.lastOrNull { it.isNotEmpty() }?.last()
        if (first != null && last != null) first to last else null
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(MAP_FRACTION).testTag(ElevationTags.MAP)) {
            map(ElevationMapContent(state.paths, state.colors, endpoints, state.marker), Modifier.fillMaxSize())
        }
        Surface(Modifier.fillMaxWidth().weight(1f), tonalElevation = 2.dp) {
            Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.tabs.size > 1) {
                        ChartTabs(state.tabs, state.tab, onSelectTab, Modifier.weight(1f))
                    } else {
                        Text(state.tab.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag(ElevationTags.CLOSE)) {
                        Icon(Icons.Filled.Close, contentDescription = ElevationText.CLOSE)
                    }
                }
                Column(Modifier.padding(end = 12.dp)) {
                    TabChart(state, onScrub)
                    Spacer(Modifier.height(8.dp))
                    val d = state.scrubM
                    if (d == null) {
                        when (state.tab) {
                            ChartTab.ELEVATION -> Legend()
                            ChartTab.SPEED -> MetricLegend(MetricKind.SPEED)
                            ChartTab.HEART_RATE -> MetricLegend(MetricKind.HEART_RATE)
                        }
                    } else {
                        Text(
                            scrubText(state, d),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag(ElevationTags.SCRUB_TEXT),
                        )
                    }
                }
            }
        }
    }
}

/** The [tabs] as one row of segmented buttons, [selected] marked; picking one calls [onSelect]. */
@Composable
private fun ChartTabs(tabs: List<ChartTab>, selected: ChartTab, onSelect: (ChartTab) -> Unit, modifier: Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.padding(end = 8.dp).testTag(ChartTab.ROW_TAG)) {
        tabs.forEachIndexed { i, tab ->
            SegmentedButton(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = tabs.size),
                modifier = Modifier.testTag(tab.tag),
            ) {
                Text(tab.title)
            }
        }
    }
}

/** The current tab's big chart, scrubbable, its scrub line at the state's [ElevationMapUiState.Ready.scrubM]. */
@Composable
private fun TabChart(state: ElevationMapUiState.Ready, onScrub: (Double) -> Unit) {
    when (state.tab) {
        ChartTab.ELEVATION ->
            ElevationChart(state.profile, height = CHART_HEIGHT, scrubbable = true, onScrub = onScrub, scrubM = state.scrubM)
        ChartTab.SPEED -> MetricChart(
            state.speed, MetricKind.SPEED, state.speedDescription,
            height = CHART_HEIGHT, scrubbable = true, onScrub = onScrub, scrubM = state.scrubM,
        )
        ChartTab.HEART_RATE -> MetricChart(
            state.heartRate, MetricKind.HEART_RATE, state.heartDescription,
            height = CHART_HEIGHT, scrubbable = true, onScrub = onScrub, scrubM = state.scrubM,
        )
    }
}

/** What is at [d] on the current tab: "거리 · 고도 · 경사", "거리 · km/h" or "거리 · bpm" ("--" where there is none). */
private fun scrubText(state: ElevationMapUiState.Ready, d: Double): String = when (state.tab) {
    ChartTab.ELEVATION -> {
        val profile = requireNotNull(state.profile)
        ElevationText.scrubLine(d, profile.elevationAt(d), profile.gradeAt(d))
    }
    ChartTab.SPEED -> ChartText.scrubLine(MetricKind.SPEED, d, state.speed?.valueAt(d))
    ChartTab.HEART_RATE -> ChartText.scrubLine(MetricKind.HEART_RATE, d, state.heartRate?.valueAt(d))
}

/** The 7 grade bands' colours and legend texts (spec §1.3), the longer texts given more room. */
@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth()) {
        GradeBand.entries.forEach { band ->
            LegendCell(band.color(), ElevationText.legend(band), Modifier.weight(legendWeight(band)))
        }
    }
}

/** The 5 speed or heart-rate bands' colours and legend texts ([metricLegend]), evenly spread. */
@Composable
private fun MetricLegend(kind: MetricKind) {
    val cells = remember(kind) { metricLegend(kind).map { (text, hex) -> text to colorOf(hex) } }
    Row(Modifier.fillMaxWidth()) {
        cells.forEach { (text, color) -> LegendCell(color, text, Modifier.weight(1f)) }
    }
}

/** A legend cell: a bar of [color] over [text]. */
@Composable
private fun LegendCell(color: Color, text: String, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 1.dp).testTag(ElevationTags.LEGEND_CELL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(color))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
    }
}

private fun legendWeight(band: GradeBand): Float = when (band) {
    GradeBand.STEEP_DOWN -> 1.5f
    GradeBand.FLAT -> 1.7f
    else -> 1f
}

/** The map's share of the screen height. */
private const val MAP_FRACTION = 0.6f

/** The big chart's height, the same on every tab. */
private val CHART_HEIGHT = 160.dp

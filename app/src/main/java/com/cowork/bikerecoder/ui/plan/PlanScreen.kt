package com.cowork.bikerecoder.ui.plan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.export.ExportFormat
import com.cowork.bikerecoder.export.ExportMenu
import com.cowork.bikerecoder.export.ExportOutcome
import com.cowork.bikerecoder.export.ExportProgress
import com.cowork.bikerecoder.export.ExportState
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.ui.common.GlyphIcon
import com.cowork.bikerecoder.ui.common.PressedPlaceSheet
import com.cowork.bikerecoder.ui.elevation.ElevationChart
import com.cowork.bikerecoder.ui.elevation.ElevationText

/** Test tags used by the Compose test. */
object PlanTags {
    const val STOP_NAME = "plan_stop_name"
    const val SUMMARY = "plan_summary"
    const val START = "plan_start"
    const val USUAL_SPEED = "plan_usual_speed"
    const val CHART_SLOT = "plan_chart_slot"
}

internal fun RouteProfile.labelKo() = when (this) {
    RouteProfile.CYCLEWAY_FIRST -> "자전거도로 최우선"
    RouteProfile.BALANCED -> "균형"
    RouteProfile.SHORTEST -> "최단거리"
}

/**
 * Route planning: map on top, stop list / profile chips / summary / start button below.
 *
 * @param map renders the plan map (BikeMap in the app, an empty box in tests).
 * @param onOpenElevation opens the "고도" map screen of the plan's route, after a tap on the elevation chart has handed
 *   the route over ([PlanViewModel.openElevation]).
 * While the route is ready, a [⋮] right of the summary exports it as GPX or KML (V1.7.0 spec §4.1, §4.2).
 */
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    map: @Composable (state: PlanUiState, modifier: Modifier) -> Unit,
    onBack: () -> Unit,
    onAddStop: () -> Unit,
    onStartNavigation: () -> Unit,
    onSaveFavorite: ((FavoritePlace) -> Unit)? = null,
    onOpenElevation: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pressed by viewModel.pressed.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    PlanContent(
        state = state,
        map = map,
        onBack = onBack,
        onAddStop = onAddStop,
        onStartNavigation = onStartNavigation,
        onRemove = viewModel::remove,
        onMove = viewModel::move,
        onSetProfile = viewModel::setProfile,
        onOpenElevation = { if (viewModel.openElevation()) onOpenElevation() },
        onExport = if (viewModel.canExport) viewModel::export else null,
        exporting = exportState == ExportState.Running,
    )
    ExportOutcome(exportState, onClear = viewModel::clearExport)
    pressed?.let { place ->
        PressedPlaceSheet(
            pressed = place,
            onSetDestination = { name, point ->
                viewModel.dismissPressed()
                viewModel.setDestination(name, point)
            },
            onAddWaypoint = { name, point ->
                viewModel.dismissPressed()
                viewModel.addWaypoint(name, point)
            },
            onSaveFavorite = onSaveFavorite?.let { save ->
                { favorite ->
                    viewModel.dismissPressed()
                    save(favorite)
                }
            },
            onDismiss = viewModel::dismissPressed,
        )
    }
}

@Composable
private fun PlanContent(
    state: PlanUiState,
    map: @Composable (PlanUiState, Modifier) -> Unit,
    onBack: () -> Unit,
    onAddStop: () -> Unit,
    onStartNavigation: () -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSetProfile: (RouteProfile) -> Unit,
    onOpenElevation: () -> Unit,
    onExport: ((ExportFormat) -> Unit)?,
    exporting: Boolean,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Text("경로 계획", style = MaterialTheme.typography.titleLarge)
            }
            map(state, Modifier.weight(1f).fillMaxWidth())
            Box(modifier = Modifier.fillMaxWidth()) {
                // Scrolls when many stops or a large font make it taller than the screen, so [안내 시작] stays reachable.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "출발: 현재 위치",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StopList(state.stops, onRemove, onMove)
                    TextButton(onClick = onAddStop, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+ 경유지 추가") }
                    ProfileChips(state.profile, onSetProfile)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) { Summary(state) }
                        if (onExport != null && state.route is RouteUiState.Ready) ExportMenu(onExport, running = exporting)
                    }
                    ChartSlot(state, onOpenElevation)
                    Button(
                        onClick = onStartNavigation,
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth().testTag(PlanTags.START),
                    ) { Text("안내 시작") }
                }
                // Over the panel while the route is exported.
                if (exporting) ExportProgress(Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun ProfileChips(selected: RouteProfile, onSelect: (RouteProfile) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RouteProfile.entries.forEach { profile ->
            FilterChip(
                selected = profile == selected,
                onClick = { onSelect(profile) },
                label = { Text(profile.labelKo()) },
            )
        }
    }
}

/**
 * Three lines: distance and time (at the usual speed, corrected for the climbs: V1.7.0 spec §2.5), ascent and cycleway
 * ratio, then "평소 속도 18 km/h 기준"; or the loading/error/idle text (the other lines kept blank, so nothing jumps).
 */
@Composable
private fun Summary(state: PlanUiState) {
    Column(modifier = Modifier.fillMaxWidth().testTag(PlanTags.SUMMARY)) {
        when (val route = state.route) {
            RouteUiState.Idle -> {
                Text("목적지를 정해 주세요")
                Text(" ")
                BlankSmallLine()
            }
            RouteUiState.Loading -> {
                Text("경로를 계산하는 중…")
                Text(" ")
                BlankSmallLine()
            }
            is RouteUiState.Error -> {
                Text(route.message, color = MaterialTheme.colorScheme.error)
                Text(" ")
                BlankSmallLine()
            }
            is RouteUiState.Ready -> {
                val s = route.route.summary
                val time = state.durationSeconds?.let(SummaryFormatter::durationOf)
                    ?: SummaryFormatter.duration(s.totalDistanceM, state.usualSpeedMps * 3.6)
                Text(
                    "${SummaryFormatter.distance(s.totalDistanceM)} · $time",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(SummaryFormatter.ascentAndRatio(s.ascentM, s.cyclewayRatio))
                Text(
                    ElevationText.usualSpeedLine(state.usualSpeedMps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(PlanTags.USUAL_SPEED),
                )
            }
        }
    }
}

@Composable
private fun BlankSmallLine() {
    Text(" ", style = MaterialTheme.typography.bodySmall)
}

/**
 * The route's small elevation chart (100dp, a tick at every waypoint; V1.7.0 spec §2.5): a tap opens the "고도" map
 * screen. "고도 정보가 없습니다" for a route without elevation; an empty slot of the same height while there is no route.
 */
@Composable
private fun ChartSlot(state: PlanUiState, onOpenElevation: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(PLAN_CHART_HEIGHT).testTag(PlanTags.CHART_SLOT)) {
        if (state.route is RouteUiState.Ready) {
            ElevationChart(state.elevation, height = PLAN_CHART_HEIGHT, onOpen = onOpenElevation)
        }
    }
}

private val PLAN_CHART_HEIGHT = 100.dp

/**
 * The stop list. Reordering: long-press the "≡" handle and drag, or use the handle's accessibility
 * actions "위로 이동" / "아래로 이동". The last row is the destination.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StopList(stops: List<PlannedStop>, onRemove: (Long) -> Unit, onMove: (Int, Int) -> Unit) {
    val listState = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Key order we expect the list to have once our last move() has been laid out (null = current order).
    var expectedKeys by remember { mutableStateOf<List<Long>?>(null) }
    val currentStops by rememberUpdatedState(stops)
    val currentOnMove by rememberUpdatedState(onMove)

    fun onDragBy(dy: Float) {
        val key = draggingKey ?: return
        dragOffset += dy
        val order = expectedKeys ?: currentStops.map { it.key }
        val info = listState.layoutInfo.visibleItemsInfo
        val cur = info.firstOrNull { it.key == key } ?: return
        // The layout still shows the order before our last move; wait for it to catch up.
        if (cur.index != order.indexOf(key)) return
        val center = cur.offset + cur.size / 2f + dragOffset
        val target = info.firstOrNull { it.key != key && center >= it.offset && center < it.offset + it.size } ?: return
        currentOnMove(cur.index, target.index)
        dragOffset -= (target.offset - cur.offset)
        expectedKeys = order.toMutableList().also { it.add(target.index, it.removeAt(cur.index)) }
    }

    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 232.dp)) {
        itemsIndexed(stops, key = { _, stop -> stop.key }) { index, stop ->
            val isDragging = draggingKey == stop.key
            val isLast = index == stops.lastIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isDragging) {
                            Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                        } else {
                            Modifier.animateItem()
                        },
                    ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "순서 변경: ${stop.name}"
                            customActions = listOf(
                                CustomAccessibilityAction("위로 이동") {
                                    if (index > 0) { currentOnMove(index, index - 1); true } else false
                                },
                                CustomAccessibilityAction("아래로 이동") {
                                    if (index < currentStops.lastIndex) { currentOnMove(index, index + 1); true } else false
                                },
                            )
                        }
                        .pointerInput(stop.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = stop.key
                                    dragOffset = 0f
                                    expectedKeys = null
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDragBy(amount.y)
                                },
                                onDragEnd = { draggingKey = null; dragOffset = 0f; expectedKeys = null },
                                onDragCancel = { draggingKey = null; dragOffset = 0f; expectedKeys = null },
                            )
                        },
                ) { Text("≡", style = MaterialTheme.typography.titleLarge) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isLast) "목적지" else "경유지",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stop.name, maxLines = 1, modifier = Modifier.testTag(PlanTags.STOP_NAME))
                }
                IconButton(onClick = { onRemove(stop.key) }) {
                    GlyphIcon("✕", "삭제: ${stop.name}")
                }
            }
        }
    }
}

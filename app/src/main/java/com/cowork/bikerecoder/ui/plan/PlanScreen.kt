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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import com.cowork.bikerecoder.ui.common.GlyphIcon

/** Test tags used by the Compose test. */
object PlanTags {
    const val STOP_NAME = "plan_stop_name"
    const val SUMMARY = "plan_summary"
    const val START = "plan_start"
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
 */
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    map: @Composable (state: PlanUiState, modifier: Modifier) -> Unit,
    onBack: () -> Unit,
    onAddStop: () -> Unit,
    onStartNavigation: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    )
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
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Text("경로 계획", style = MaterialTheme.typography.titleLarge)
            }
            map(state, Modifier.weight(1f).fillMaxWidth())
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                Summary(state)
                Button(
                    onClick = onStartNavigation,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth().testTag(PlanTags.START),
                ) { Text("안내 시작") }
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

/** Two lines: distance and time, then ascent and cycleway ratio; or the loading/error/idle text. */
@Composable
private fun Summary(state: PlanUiState) {
    Column(modifier = Modifier.fillMaxWidth().testTag(PlanTags.SUMMARY)) {
        when (val route = state.route) {
            RouteUiState.Idle -> {
                Text("목적지를 정해 주세요")
                Text(" ")
            }
            RouteUiState.Loading -> {
                Text("경로를 계산하는 중…")
                Text(" ")
            }
            is RouteUiState.Error -> {
                Text(route.message, color = MaterialTheme.colorScheme.error)
                Text(" ")
            }
            is RouteUiState.Ready -> {
                val s = route.route.summary
                Text(
                    "${SummaryFormatter.distance(s.totalDistanceM)} · ${SummaryFormatter.duration(s.totalDistanceM)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(SummaryFormatter.ascentAndRatio(s.ascentM, s.cyclewayRatio))
            }
        }
    }
}

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

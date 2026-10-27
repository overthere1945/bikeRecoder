package com.cowork.bikerecoder.ui.cumulative

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeTotals
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.RideLine
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.ui.history.HistoryScaffold
import java.time.LocalDate

const val CUMULATIVE_EMPTY = "아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다"
const val CUMULATIVE_PERIOD_EMPTY = "이 기간에 기록이 없습니다"

object CumulativeMapTags {
    const val ALL = "cumulative-all"
    const val YEAR = "cumulative-year"
    const val MONTH = "cumulative-month"
    const val WEEK = "cumulative-week"
    const val THIS_WEEK = "cumulative-this-week"
    const val THIS_MONTH = "cumulative-this-month"
    const val RIDES = "cumulative-rides"

    fun ride(segmentId: Long) = "cumulative-ride-$segmentId"
}

private val KOREA_CENTER = GeoPoint(36.5, 127.8)

/** The summary line (spec §4): "{총 거리} · 주행 {n}회 · {총 이동 시간}", e.g. "35.00km · 주행 3회 · 1시간 45분". */
fun cumulativeSummaryText(totals: CumulativeTotals): String =
    "${RideFormat.distance(totals.distanceM)} · 주행 ${totals.rides}회 · ${RideFormat.duration(totals.movingTimeMs)}"

/**
 * [map]: the map of the rides, told to call onUserGesture when the user moves it and onRideClick when a ride's line
 * (or an empty spot) is tapped (replaceable in tests). [onOpenRide]: [기록 보기], with the ride's segment id.
 */
@Composable
fun CumulativeMapScreen(
    viewModel: CumulativeMapViewModel,
    tileSource: TileSource,
    onBack: () -> Unit,
    onOpenRide: (Long) -> Unit,
    map: @Composable (
        rides: List<RideLine>,
        selected: Long?,
        fit: CameraFit?,
        onUserGesture: () -> Unit,
        onRideClick: (Long?) -> Unit,
        modifier: Modifier,
    ) -> Unit = { rides, selected, fit, onUserGesture, onRideClick, modifier ->
        CumulativeMap(rides, selected, fit, tileSource, onUserGesture, onRideClick, modifier)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fit by viewModel.fit.collectAsStateWithLifecycle()
    CumulativeMapContent(
        state = state,
        actions = CumulativeMapActions(
            showAll = viewModel::showAll,
            selectYear = viewModel::selectYear,
            selectMonth = viewModel::selectMonth,
            selectWeek = viewModel::selectWeek,
            thisWeek = viewModel::thisWeek,
            thisMonth = viewModel::thisMonth,
            selectRide = viewModel::select,
            openRide = onOpenRide,
        ),
        onBack = onBack,
        map = { modifier -> map(state.lines, state.selected, fit, viewModel::onMapMoved, viewModel::select, modifier) },
    )
}

/**
 * What the cumulative map screen's controls do: the period chips ([selectMonth] and [selectWeek] take null for the
 * whole year / month), a tap on a ride's row ([selectRide]) and [기록 보기] ([openRide]).
 */
class CumulativeMapActions(
    val showAll: () -> Unit = {},
    val selectYear: (Int) -> Unit = {},
    val selectMonth: (Int?) -> Unit = {},
    val selectWeek: (LocalDate?) -> Unit = {},
    val thisWeek: () -> Unit = {},
    val thisMonth: () -> Unit = {},
    val selectRide: (Long) -> Unit = {},
    val openRide: (Long) -> Unit = {},
)

/**
 * "누적 지도" (spec §4, §6.4–§6.6): the summary of the chosen period; [전체] [연도 ▾] [월 ▾] [주 ▾] ([월 ▾] only once a
 * year is chosen, [주 ▾] once a month is; their "전체" is the whole year / month) and below them [이번 주] [이번 달];
 * then [map] (about 55% of the screen) and the list of the period's rides, newest first, each with its colour. The
 * highlighted ride's row has [기록 보기]. With no ride at all ([CumulativeMapState.empty]), only [CUMULATIVE_EMPTY];
 * with none in the period, [CUMULATIVE_PERIOD_EMPTY] in place of the list. No summary while nothing is read yet, nor
 * while there is no route but a pass may still bring some.
 */
@Composable
fun CumulativeMapContent(
    state: CumulativeMapState,
    actions: CumulativeMapActions,
    onBack: () -> Unit,
    map: @Composable (Modifier) -> Unit,
) {
    HistoryScaffold(title = "누적 지도", onBack = onBack) {
        if (state.empty) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(CUMULATIVE_EMPTY, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.loaded && (state.settled || state.years.isNotEmpty())) {
                    Text(
                        cumulativeSummaryText(state.totals),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                PeriodRow(state, actions)
                QuickRow(state, actions)
                map(Modifier.fillMaxWidth().weight(0.7f))
                RideList(state, actions, Modifier.fillMaxWidth().weight(0.3f))
            }
        }
    }
}

@Composable
private fun PeriodRow(state: CumulativeMapState, actions: CumulativeMapActions) {
    val year = state.period.year
    val month = state.period.month
    val weekStart = state.period.weekStart
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = year == null && weekStart == null,
            onClick = actions.showAll,
            label = { Text("전체") },
            modifier = Modifier.testTag(CumulativeMapTags.ALL),
        )
        MenuChip(
            label = if (year == null) "연도 ▾" else "${year}년 ▾",
            selected = year != null,
            enabled = true,
            tag = CumulativeMapTags.YEAR,
            items = state.years.map { "${it}년" to { actions.selectYear(it) } },
        )
        MenuChip(
            label = if (month == null) "월 ▾" else "${month}월 ▾",
            selected = month != null,
            enabled = year != null,
            tag = CumulativeMapTags.MONTH,
            items = listOf("전체" to { actions.selectMonth(null) }) + state.months.map { "${it}월" to { actions.selectMonth(it) } },
        )
        MenuChip(
            label = weekChipLabel(state),
            selected = weekStart != null,
            enabled = year != null && month != null,
            tag = CumulativeMapTags.WEEK,
            items = listOf("전체" to { actions.selectWeek(null) }) + state.weeks.map { it.label to { actions.selectWeek(it.start) } },
        )
    }
}

/** "주 ▾", or with a week chosen its number in its month, e.g. "4주 ▾" (also for a week without rides). */
private fun weekChipLabel(state: CumulativeMapState): String {
    val week = state.week ?: return "주 ▾"
    return "${week.number}주 ▾"
}

/** [이번 주] [이번 달], under the period chips. */
@Composable
private fun QuickRow(state: CumulativeMapState, actions: CumulativeMapActions) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.quick == QuickPeriod.THIS_WEEK,
            onClick = actions.thisWeek,
            label = { Text("이번 주") },
            modifier = Modifier.testTag(CumulativeMapTags.THIS_WEEK),
        )
        FilterChip(
            selected = state.quick == QuickPeriod.THIS_MONTH,
            onClick = actions.thisMonth,
            label = { Text("이번 달") },
            modifier = Modifier.testTag(CumulativeMapTags.THIS_MONTH),
        )
    }
}

/**
 * The period's rides, newest first: a colour dot and the row text; a tap highlights the ride (or lets go of it). The
 * highlighted row is shaded, has [기록 보기], and is scrolled into view (it may have been tapped on the map).
 */
@Composable
private fun RideList(state: CumulativeMapState, actions: CumulativeMapActions, modifier: Modifier) {
    if (state.periodEmpty) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(CUMULATIVE_PERIOD_EMPTY, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.selected) {
        val index = state.rides.indexOfFirst { it.segmentId == state.selected }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyColumn(state = listState, modifier = modifier.testTag(CumulativeMapTags.RIDES)) {
        items(state.rides, key = { it.segmentId }) { row ->
            val selected = row.segmentId == state.selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { actions.selectRide(row.segmentId) }
                    .padding(horizontal = 16.dp)
                    .testTag(CumulativeMapTags.ride(row.segmentId)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(row.color))),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(row.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (selected) {
                    TextButton(onClick = { actions.openRide(row.segmentId) }) { Text("기록 보기") }
                }
            }
        }
    }
}

/** A chip that opens a menu of [items] (text and what choosing it does). */
@Composable
private fun MenuChip(label: String, selected: Boolean, enabled: Boolean, tag: String, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            items.forEach { (text, choose) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    choose()
                })
            }
        }
    }
}

/** The rides in their colours ([MapOverlay.rides]), [selected] highlighted, framed by [fit]; a tap picks a ride. */
@Composable
internal fun CumulativeMap(
    rides: List<RideLine>,
    selected: Long?,
    fit: CameraFit?,
    tileSource: TileSource,
    onUserGesture: () -> Unit,
    onRideClick: (Long?) -> Unit,
    modifier: Modifier,
) {
    BikeMap(
        tileSource = tileSource,
        overlay = MapOverlay(route = null, stops = emptyList(), user = null, rides = rides, selectedRide = selected),
        cameraMode = CameraMode.FREE,
        onLongPress = {},
        onUserGesture = onUserGesture,
        modifier = modifier,
        initialCenter = KOREA_CENTER,
        initialZoom = 7.0,
        fit = fit,
        onRideClick = onRideClick,
    )
}

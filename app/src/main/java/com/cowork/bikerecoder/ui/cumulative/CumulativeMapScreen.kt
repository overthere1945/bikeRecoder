package com.cowork.bikerecoder.ui.cumulative

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.ui.history.HistoryScaffold

const val CUMULATIVE_EMPTY = "아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다"

object CumulativeMapTags {
    const val ALL = "cumulative-all"
    const val YEAR = "cumulative-year"
    const val MONTH = "cumulative-month"
}

private val KOREA_CENTER = GeoPoint(36.5, 127.8)

/** The summary line (spec §4): "{총 거리} · 주행 {n}회 · {총 이동 시간}", e.g. "35.00km · 주행 3회 · 1시간 45분". */
fun cumulativeSummaryText(totals: CumulativeTotals): String =
    "${RideFormat.distance(totals.distanceM)} · 주행 ${totals.rides}회 · ${RideFormat.duration(totals.movingTimeMs)}"

/** [map]: the map of the routes (replaceable in tests). */
@Composable
fun CumulativeMapScreen(
    viewModel: CumulativeMapViewModel,
    tileSource: TileSource,
    onBack: () -> Unit,
    map: @Composable (lines: List<List<GeoPoint>>, fit: CameraFit?, modifier: Modifier) -> Unit = { lines, fit, modifier ->
        CumulativeMap(lines, fit, tileSource, modifier)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fit by viewModel.fit.collectAsStateWithLifecycle()
    CumulativeMapContent(
        state = state,
        onShowAll = viewModel::showAll,
        onSelectYear = viewModel::selectYear,
        onSelectMonth = viewModel::selectMonth,
        onBack = onBack,
        map = { modifier -> map(state.lines, fit, modifier) },
    )
}

/**
 * "누적 지도" (spec §4): the summary of the chosen period, [전체] [연도 ▾] [월 ▾] ([월 ▾] only once a year is chosen; its
 * "전체" is the whole year), then [map]. With no ride at all, only [CUMULATIVE_EMPTY].
 */
@Composable
fun CumulativeMapContent(
    state: CumulativeMapState,
    onShowAll: () -> Unit,
    onSelectYear: (Int) -> Unit,
    onSelectMonth: (Int?) -> Unit,
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
                if (state.loaded) {
                    Text(
                        cumulativeSummaryText(state.totals),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                PeriodRow(state, onShowAll, onSelectYear, onSelectMonth)
                map(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun PeriodRow(
    state: CumulativeMapState,
    onShowAll: () -> Unit,
    onSelectYear: (Int) -> Unit,
    onSelectMonth: (Int?) -> Unit,
) {
    val year = state.period.year
    val month = state.period.month
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = year == null,
            onClick = onShowAll,
            label = { Text("전체") },
            modifier = Modifier.testTag(CumulativeMapTags.ALL),
        )
        MenuChip(
            label = if (year == null) "연도 ▾" else "${year}년 ▾",
            selected = year != null,
            enabled = true,
            tag = CumulativeMapTags.YEAR,
            items = state.years.map { "${it}년" to { onSelectYear(it) } },
        )
        MenuChip(
            label = if (month == null) "월 ▾" else "${month}월 ▾",
            selected = month != null,
            enabled = year != null,
            tag = CumulativeMapTags.MONTH,
            items = listOf("전체" to { onSelectMonth(null) }) + state.months.map { "${it}월" to { onSelectMonth(it) } },
        )
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

/** The routes drawn faint on top of one another ([MapOverlay.faintLines]), framed by [fit]. */
@Composable
internal fun CumulativeMap(lines: List<List<GeoPoint>>, fit: CameraFit?, tileSource: TileSource, modifier: Modifier) {
    BikeMap(
        tileSource = tileSource,
        overlay = MapOverlay(route = null, stops = emptyList(), user = null, faintLines = lines),
        cameraMode = CameraMode.FREE,
        onLongPress = {},
        onUserGesture = {},
        modifier = modifier,
        initialCenter = KOREA_CENTER,
        initialZoom = 7.0,
        fit = fit,
    )
}

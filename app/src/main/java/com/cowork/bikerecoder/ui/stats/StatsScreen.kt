package com.cowork.bikerecoder.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.ui.feed.kmText
import com.cowork.bikerecoder.ui.feed.meterText
import kotlinx.coroutines.launch
import java.time.LocalDate

object StatsTags {
    const val LIST = "stats-list"
    const val GOAL_CARD = "stats-goal-card"
    const val GOAL_SET = "stats-goal-set"
    const val WEEKLY_RING = "stats-goal-ring"
    const val YEARLY_TEXT = "stats-yearly-text"
    const val YEARLY_REACHED = "stats-yearly-reached"
    const val YEARLY_BAR = "stats-yearly-bar"
    const val PROJECTION = "stats-projection"
    const val STREAK = "stats-streak"
    const val TITLE = "stats-title"
    const val PREVIOUS = "stats-previous"
    const val NEXT = "stats-next"
    const val DISTANCE = "stats-distance"
    const val COMPARISON = "stats-comparison"
    const val RIDES = "stats-rides"
    const val TIME = "stats-time"
    const val ASCENT = "stats-ascent"
    const val CHART = "stats-chart"
    const val BAR_VALUE = "stats-bar-value"
    const val BAR_UNIT = "stats-bar-unit"
    const val RECORDS = "stats-records"
    const val CUMULATIVE = "stats-cumulative"
    const val CUMULATIVE_CHART = "stats-cumulative-chart"
    const val CUMULATIVE_PLOT = "stats-cumulative-plot"
    const val CUMULATIVE_LEGEND = "stats-cumulative-legend"
    const val HELP_TEXT = "stats-help-text"

    fun kind(kind: PeriodKind) = "stats-kind-${kind.name}"
    fun metric(metric: StatMetric) = "stats-metric-${metric.name}"
    fun bar(index: Int) = "stats-bar-$index"
    fun barShort(index: Int) = "stats-bar-short-$index"
    fun record(index: Int) = "stats-record-$index"
    fun help(topic: String) = "stats-help-$topic"
}

const val STATS_RECORDS_TITLE = "개인 기록"

private fun kindLabel(kind: PeriodKind): String = when (kind) {
    PeriodKind.WEEK -> "주"
    PeriodKind.MONTH -> "월"
    PeriodKind.YEAR -> "년"
    PeriodKind.ALL -> "전체"
}

internal fun metricLabel(metric: StatMetric): String = when (metric) {
    StatMetric.DISTANCE -> "거리"
    StatMetric.TIME -> "시간"
    StatMetric.ASCENT -> "상승 고도"
}

/** "누적 지도 · 이 기간 7회 ›". */
fun cumulativeEntryText(rides: Int): String = "누적 지도 · 이 기간 ${rides}회 ›"

/** Where the period block sits in the list: a record that points at a week or month scrolls up to it. */
private const val PERIOD_ITEM = 2

/**
 * The stats tab (V1.4.0 spec §3): the goal card, the streak, the statistics of a period (week, month, year or all) with
 * its bar chart, the personal records and the entry to the cumulative map. A record of a segment opens it
 * ([onOpenSegment]); one of a week or month shows that period here. The entry calls [onOpenCumulative] with the period's
 * filter. [scrollToTop] counts the taps on the tab that is already open: each new value scrolls the list to the top.
 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onOpenSegment: (Long) -> Unit,
    onOpenCumulative: (PeriodFilter) -> Unit,
    scrollToTop: Int,
) {
    val loaded by viewModel.state.collectAsStateWithLifecycle()
    // A day may have passed (a new week or month).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var goalDialog by rememberSaveable { mutableStateOf(false) }
    // The topic of the "?" bubble that is open (one at a time). Plain remember: leaving the tab or turning the screen closes it.
    var openTip by remember { mutableStateOf<String?>(null) }
    // Nothing until the rides and the goals are read (review M2); so the goal window starts from the stored goals.
    val state = loaded ?: return
    StatsContent(
        state = state,
        scrollToTop = scrollToTop,
        openTip = openTip,
        onToggleTip = { topic -> openTip = if (openTip == topic) null else topic },
        onEditGoals = { goalDialog = true },
        onSelectKind = viewModel::selectKind,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onSelectMetric = viewModel::selectMetric,
        onSelectBar = viewModel::selectBar,
        onShowPeriod = viewModel::show,
        onOpenSegment = onOpenSegment,
        onOpenCumulative = { onOpenCumulative(state.cumulativeFilter) },
    )
    if (goalDialog) {
        GoalDialog(
            weeklyKm = state.weeklyGoal?.goalKm,
            yearlyKm = state.yearlyGoal?.goalKm,
            onSave = { weekly, yearly ->
                viewModel.saveGoals(weekly, yearly)
                goalDialog = false
            },
            onDismiss = { goalDialog = false },
        )
    }
}

@Composable
internal fun StatsContent(
    state: StatsState,
    scrollToTop: Int,
    openTip: String?,
    onToggleTip: (String) -> Unit,
    onEditGoals: () -> Unit,
    onSelectKind: (PeriodKind) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectMetric: (StatMetric) -> Unit,
    onSelectBar: (Int?) -> Unit,
    onShowPeriod: (PeriodKind, LocalDate) -> Unit,
    onOpenSegment: (Long) -> Unit,
    onOpenCumulative: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstScrollToTop = remember { scrollToTop }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != firstScrollToTop) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().clearsBarOnOutsideTap { onSelectBar(null) }.testTag(StatsTags.LIST),
    ) {
        item(key = "goals") {
            GoalCard(state.weeklyGoal, state.yearlyGoal, state.projectionText, onEditGoals, openTip = openTip, onToggleTip = onToggleTip)
        }
        item(key = "streak") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.streakText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp).testTag(StatsTags.STREAK),
                )
                HelpTip(StatsHelp.STREAK_TOPIC, StatsHelp.STREAK, openTip == StatsHelp.STREAK_TOPIC, onToggle = { onToggleTip(StatsHelp.STREAK_TOPIC) })
            }
        }
        item(key = "period") {
            PeriodBlock(state, openTip, onToggleTip, onSelectKind, onPrevious, onNext, onSelectMetric, onSelectBar)
        }
        if (state.records.isNotEmpty()) {
            item(key = "records") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        STATS_RECORDS_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag(StatsTags.RECORDS),
                    )
                    HelpTip(StatsHelp.RECORDS_TOPIC, StatsHelp.RECORDS, openTip == StatsHelp.RECORDS_TOPIC, onToggle = { onToggleTip(StatsHelp.RECORDS_TOPIC) })
                }
            }
            itemsIndexed(state.records, key = { _, row -> row.label }) { index, row ->
                RecordLine(row, index) { target ->
                    when (target) {
                        is RecordTarget.Segment -> onOpenSegment(target.id)
                        is RecordTarget.Period -> {
                            onShowPeriod(target.kind, target.start)
                            scope.launch { listState.animateScrollToItem(PERIOD_ITEM) }
                        }
                    }
                }
            }
        }
        item(key = "cumulative") { CumulativeEntry(state.cumulativeRides, onOpenCumulative) }
    }
}

/**
 * Calls [onTap] for a tap that nothing took (spec §3.3: "다른 곳을 누르면 사라진다"). The main pass reaches this parent after the
 * children, so a tap on a bar, chip or button (which consume it) and a scroll or drag (which cancel it) do not count.
 */
private fun Modifier.clearsBarOnOutsideTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null && !up.isConsumed) onTap()
    }
}

@Composable
private fun PeriodBlock(
    state: StatsState,
    openTip: String?,
    onToggleTip: (String) -> Unit,
    onSelectKind: (PeriodKind) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectMetric: (StatMetric) -> Unit,
    onSelectBar: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in PeriodKind.entries) {
                FilterChip(
                    selected = state.period.kind == kind,
                    onClick = { onSelectKind(kind) },
                    label = { Text(kindLabel(kind)) },
                    modifier = Modifier.testTag(StatsTags.kind(kind)),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.period.kind != PeriodKind.ALL) {
                IconButton(onClick = onPrevious, enabled = state.canPrevious, modifier = Modifier.testTag(StatsTags.PREVIOUS)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 기간")
                }
            }
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).testTag(StatsTags.TITLE),
            )
            if (state.period.kind != PeriodKind.ALL) {
                IconButton(onClick = onNext, enabled = state.canNext, modifier = Modifier.testTag(StatsTags.NEXT)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 기간")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                kmText(state.totals.distanceM),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(StatsTags.DISTANCE),
            )
            state.comparisonText?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false).testTag(StatsTags.COMPARISON),
                    )
                    HelpTip(StatsHelp.COMPARISON_TOPIC, StatsHelp.COMPARISON, openTip == StatsHelp.COMPARISON_TOPIC, onToggle = { onToggleTip(StatsHelp.COMPARISON_TOPIC) })
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            HelpTip(StatsHelp.TOTALS_TOPIC, StatsHelp.TOTALS, openTip == StatsHelp.TOTALS_TOPIC, onToggle = { onToggleTip(StatsHelp.TOTALS_TOPIC) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TotalCell("주행", "${state.totals.rides}회", StatsTags.RIDES, Modifier.weight(1f))
            TotalCell("이동 시간", RideFormat.duration(state.totals.movingTimeMs), StatsTags.TIME, Modifier.weight(1f))
            TotalCell("상승 고도", meterText(state.totals.ascentM), StatsTags.ASCENT, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The chips give way to the "?" (which keeps its 48 dp), not the other way round.
            Row(modifier = Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (metric in StatMetric.entries) {
                    FilterChip(
                        selected = state.metric == metric,
                        onClick = { onSelectMetric(metric) },
                        label = { Text(metricLabel(metric)) },
                        modifier = Modifier.testTag(StatsTags.metric(metric)),
                    )
                }
            }
            HelpTip(StatsHelp.CHART_TOPIC, StatsHelp.CHART, openTip == StatsHelp.CHART_TOPIC, onToggle = { onToggleTip(StatsHelp.CHART_TOPIC) })
        }
        BarChart(state.bars, state.selectedBar, state.unitText, onSelectBar)
        state.cumulative?.let { CumulativeChart(it, state.period.kind, state.metric, state.unitText) }
    }
}

@Composable
private fun TotalCell(label: String, value: String, tag: String, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.testTag(tag),
            )
        }
    }
}

@Composable
private fun RecordLine(row: RecordRow, index: Int, onOpen: (RecordTarget) -> Unit) {
    val target = row.target
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (target != null) Modifier.clickable(role = Role.Button) { onOpen(target) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(StatsTags.record(index)),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
            if (target != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun CumulativeEntry(rides: Int, onOpen: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(StatsTags.CUMULATIVE),
    ) {
        Text(
            cumulativeEntryText(rides),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpen).fillMaxWidth().padding(16.dp),
        )
    }
}

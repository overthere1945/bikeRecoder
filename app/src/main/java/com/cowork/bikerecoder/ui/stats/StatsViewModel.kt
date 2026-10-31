package com.cowork.bikerecoder.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.stats.Bucket
import com.cowork.bikerecoder.core.stats.GoalInput
import com.cowork.bikerecoder.core.stats.Goals
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideRecords
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import com.cowork.bikerecoder.core.stats.StatTotals
import com.cowork.bikerecoder.core.stats.StatsCalculator
import com.cowork.bikerecoder.core.stats.StatsPeriod
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.ui.feed.kmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The weekly and yearly distance goals in km; null = no such goal. */
data class GoalKm(val weekly: Int?, val yearly: Int?)

/** Where the goals live ([SettingsRepository] in the app). */
interface GoalSettings {
    val goals: Flow<GoalKm>

    suspend fun setGoals(weeklyKm: Int?, yearlyKm: Int?)
}

private class RepositoryGoals(private val settings: SettingsRepository) : GoalSettings {
    override val goals: Flow<GoalKm> =
        settings.settings.map { GoalKm(it.weeklyGoalKm, it.yearlyGoalKm) }.distinctUntilChanged()

    override suspend fun setGoals(weeklyKm: Int?, yearlyKm: Int?) = settings.setGoals(weeklyKm, yearlyKm)
}

/** A goal and how far it is: [distanceM] is this week's (or this year's) distance. */
data class GoalProgress(val goalKm: Int, val distanceM: Double) {
    val reached: Boolean get() = distanceM >= goalKm * 1000.0

    /** The ring or bar fills this much and stops at 1 (spec §3.1). */
    val fraction: Float get() = (distanceM / (goalKm * 1000.0)).coerceIn(0.0, 1.0).toFloat()
}

/**
 * A bar: [label] under it, [value] its height in the unit of the metric, [valueText] the line shown when it is tapped,
 * [shortText] the short value written above it ("" for an empty bar or one that has not come yet).
 */
data class BarState(val label: String, val value: Double, val valueText: String, val upcoming: Boolean, val shortText: String)

/**
 * The cumulative line chart: [current] and [previous] are the running totals per day in the unit of the metric (km, hours, m),
 * [goal] the end value of the goal pace line (a distance goal of a week or year; null otherwise), [length] the days of the
 * axis, [currentDays] the days of the shown period (where the goal pace reaches the goal), [endText] the last value of
 * [current] as [cumulativeText] (the bubble), [description] what a screen reader says (the values in the words of [metricText]).
 */
data class CumulativeChartState(
    val current: List<Double>,
    val previous: List<Double>,
    val goal: Double?,
    val length: Int,
    val currentDays: Int,
    val endText: String,
    val description: String,
    val currentName: String,
    val previousName: String,
)

/**
 * What the stats tab shows (V1.4.0 spec §3). [records] is empty under two rides; [cumulativeRides] and [cumulativeFilter]
 * are what the entry to the cumulative map shows and opens.
 */
data class StatsState(
    val period: StatsPeriod,
    val title: String,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val totals: StatTotals,
    val comparisonText: String?,
    val bars: List<BarState>,
    val selectedBar: Int?,
    val metric: StatMetric,
    val unitText: String,
    val weeklyGoal: GoalProgress?,
    val yearlyGoal: GoalProgress?,
    val projectionText: String?,
    val streakText: String,
    val records: List<RecordRow>,
    /** Null for [PeriodKind.ALL]. */
    val cumulative: CumulativeChartState?,
    val cumulativeRides: Int,
    val cumulativeFilter: PeriodFilter,
)

/**
 * The stats tab (V1.4.0 spec §3). It starts on this month's distance. [previous] and [next] page through the periods from the
 * first ride's to this one; [show] jumps (also to a period the records point at). Everything is computed from [rides] each
 * time; "today" is [today] (Asia/Seoul unless told otherwise), read again by [refresh] so a process that lives into a new
 * week or month moves on.
 */
class StatsViewModel(
    rides: RideStatsSource,
    private val goals: GoalSettings,
    private val today: () -> LocalDate = { LocalDate.now(SEOUL) },
    private val log: WarnLog = WarnLog { _, _ -> },
    /** Where the state is computed: off the main thread (review M4). */
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** What the user picked: [anchor] a date of the shown period (null = the period of today), [bar] the tapped bar. */
    private data class Selection(
        val kind: PeriodKind = PeriodKind.MONTH,
        val anchor: LocalDate? = null,
        val metric: StatMetric = StatMetric.DISTANCE,
        val bar: Int? = null,
    )

    private val selection = MutableStateFlow(Selection())
    private val day = MutableStateFlow(today())

    /** Null until both the rides and the goals have been read (review M2: no zeros or "no goal" before them). */
    val state: StateFlow<StatsState?> = combine(
        rides.observeRides().catch { e ->
            log.warn("Could not follow the ride stats", e)
            emit(emptyList())
        },
        goals.goals.catch { e ->
            log.warn("Could not follow the goals", e)
            emit(GoalKm(null, null))
        },
        selection,
        day,
    ) { all, goalKm, sel, today -> build(all, goalKm, sel, today) }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Takes today's date again (every resume): a new day may be a new week. */
    fun refresh() {
        day.value = today()
    }

    fun selectKind(kind: PeriodKind) {
        selection.update { if (it.kind == kind) it else it.copy(kind = kind, anchor = null, bar = null) }
    }

    fun previous() = page(-1)

    fun next() = page(1)

    private fun page(step: Int) {
        val current = state.value ?: return
        if (if (step < 0) !current.canPrevious else !current.canNext) return
        val target = if (step < 0) StatsCalculator.previous(current.period) else StatsCalculator.next(current.period)
        val thisStart = StatsCalculator.periodOf(target.kind, day.value, emptyList(), day.value).start
        selection.update { it.copy(anchor = target.start.takeIf { start -> start != thisStart }, bar = null) }
    }

    /** The [kind] period that [start] is in (a week's Monday, a month's first day, any day), or the nearest one to page to. */
    fun show(kind: PeriodKind, start: LocalDate) {
        selection.update { it.copy(kind = kind, anchor = start, bar = null) }
    }

    /** This Monday-to-Sunday week (the summary tab's week strip), by the date read now and following it (a new week moves on). */
    fun showThisWeek() {
        day.value = today()
        selection.update { it.copy(kind = PeriodKind.WEEK, anchor = null, bar = null) }
    }

    fun selectMetric(metric: StatMetric) {
        selection.update { if (it.metric == metric) it else it.copy(metric = metric, bar = null) }
    }

    /** Taps a bar: its value shows; the same bar again hides it. A bar that is not there or has not come yet is ignored. */
    fun selectBar(index: Int?) {
        if (index != null) {
            val bar = state.value?.bars?.getOrNull(index)
            if (bar == null || bar.upcoming) return
        }
        selection.update { it.copy(bar = if (index == null || index == it.bar) null else index) }
    }

    /**
     * Writes both goals: [GoalInput.Valid] sets it, [GoalInput.Empty] removes it. If either is [GoalInput.Invalid] nothing is
     * written (the dialog does not let that through).
     */
    fun saveGoals(weekly: GoalInput, yearly: GoalInput) {
        if (weekly is GoalInput.Invalid || yearly is GoalInput.Invalid) return
        viewModelScope.launch {
            try {
                goals.setGoals((weekly as? GoalInput.Valid)?.km, (yearly as? GoalInput.Valid)?.km)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not save the goals", e)
            }
        }
    }

    private fun build(all: List<RideStat>, goalKm: GoalKm, sel: Selection, today: LocalDate): StatsState {
        val rides = StatsCalculator.rides(all)
        val kind = sel.kind
        val bounds = StatsCalculator.bounds(kind, rides, today)
        val period = if (kind == PeriodKind.ALL) {
            StatsCalculator.periodOf(kind, today, rides, today)
        } else {
            val start = sel.anchor?.let { StatsCalculator.periodOf(kind, it, rides, today).start }
                ?: bounds.endInclusive // the period of today
            StatsCalculator.periodOf(kind, start.coerceIn(bounds.start, bounds.endInclusive), rides, today)
        }
        val paged = kind != PeriodKind.ALL
        val totals = StatsCalculator.totals(rides, period.start, period.endInclusive)
        val bars = StatsCalculator.buckets(rides, period, today).map { barOf(it, sel.metric, period) }
        val weekStart = mondayOf(today)
        val yearStart = today.withDayOfYear(1)
        val yearDistance = StatsCalculator.totals(rides, yearStart, yearStart.plusYears(1).minusDays(1)).distanceM
        val weeklyGoal = goalKm.weekly?.let {
            GoalProgress(it, StatsCalculator.totals(rides, weekStart, weekStart.plusDays(6)).distanceM)
        }
        val yearlyGoal = goalKm.yearly?.let { GoalProgress(it, yearDistance) }
        return StatsState(
            period = period,
            title = statsPeriodTitle(period),
            canPrevious = paged && period.start > bounds.start,
            canNext = paged && period.start < bounds.endInclusive,
            totals = totals,
            comparisonText = StatsCalculator.comparison(rides, period, today)?.let { comparisonText(it, period) },
            bars = bars,
            selectedBar = sel.bar?.takeIf { bars.getOrNull(it)?.upcoming == false },
            metric = sel.metric,
            unitText = metricUnitText(sel.metric),
            weeklyGoal = weeklyGoal,
            yearlyGoal = yearlyGoal,
            projectionText = if (yearlyGoal == null) null else Goals.yearProjectionKm(yearDistance, today)?.let(::projectionText),
            streakText = streakText(RideRecords.streak(rides, today)),
            records = recordRows(rides, today),
            cumulative = cumulativeOf(rides, period, sel.metric, goalKm, today),
            cumulativeRides = totals.rides,
            cumulativeFilter = filterOf(period),
        )
    }

    private fun barOf(bucket: Bucket, metric: StatMetric, period: StatsPeriod): BarState = BarState(
        label = barAxisLabel(period.kind, bucket.start, period),
        value = if (bucket.upcoming) 0.0 else metricValue(metric, bucket.totals),
        valueText = "${barCaptionName(period.kind, bucket.start, period)} · ${metricText(metric, bucket.totals)}",
        upcoming = bucket.upcoming,
        shortText = if (bucket.upcoming) "" else barShortText(metric, bucket.totals),
    )

    private fun cumulativeOf(rides: List<RideStat>, period: StatsPeriod, metric: StatMetric, goalKm: GoalKm, today: LocalDate): CumulativeChartState? {
        val series = StatsCalculator.cumulative(rides, period, today) ?: return null
        // The last day shown (today, or the end of a finished period); the earlier period on that same day, or its end if shorter.
        val last = series.current.lastIndex
        val previousAt = series.previous.getOrElse(last) { series.previous.last() }
        val goal = when {
            metric != StatMetric.DISTANCE -> null
            period.kind == PeriodKind.WEEK -> goalKm.weekly
            period.kind == PeriodKind.YEAR -> goalKm.yearly
            else -> null
        }
        val (currentName, previousName) = cumulativeNames(period, today)
        return CumulativeChartState(
            current = series.current.map { cumulativeValue(metric, it) },
            previous = series.previous.map { cumulativeValue(metric, it) },
            goal = goal?.toDouble(),
            length = series.length,
            currentDays = (ChronoUnit.DAYS.between(period.start, period.endInclusive) + 1).toInt(),
            endText = cumulativeText(metric, series.current.last()),
            description = "누적 $currentName ${metricText(metric, series.current.last())}, $previousName ${metricText(metric, previousAt)}",
            currentName = currentName,
            previousName = previousName,
        )
    }

    /** The legend names of the shown period and the one before it: "이번 달" / "지난달", or "9월" / "8월" for a period gone by. */
    private fun cumulativeNames(period: StatsPeriod, today: LocalDate): Pair<String, String> {
        val thisOne = period.start <= today && today <= period.endInclusive
        val before = StatsCalculator.previous(period).start
        return when (period.kind) {
            PeriodKind.WEEK -> if (thisOne) "이번 주" to "지난주" else "이 주" to "전 주"
            PeriodKind.MONTH -> if (thisOne) "이번 달" to "지난달" else "${period.start.monthValue}월" to "${before.monthValue}월"
            else -> if (thisOne) "올해" to "작년" else "${period.start.year}년" to "${before.year}년"
        }
    }

    private fun filterOf(period: StatsPeriod): PeriodFilter = when (period.kind) {
        PeriodKind.WEEK -> PeriodFilter(weekStart = period.start)
        PeriodKind.MONTH -> PeriodFilter(year = period.start.year, month = period.start.monthValue)
        PeriodKind.YEAR -> PeriodFilter(year = period.start.year)
        PeriodKind.ALL -> PeriodFilter()
    }

    private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel(container.rideStats, RepositoryGoals(container.settings), log = androidWarnLog("Stats")) }
        }
    }
}

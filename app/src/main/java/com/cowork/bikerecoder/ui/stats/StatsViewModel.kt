package com.cowork.bikerecoder.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.activity.ActivityFormat
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.stats.Bucket
import com.cowork.bikerecoder.core.stats.GoalInput
import com.cowork.bikerecoder.core.stats.Goals
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideRecords
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import com.cowork.bikerecoder.core.stats.CumulativeSeries
import com.cowork.bikerecoder.core.stats.StatTotals
import com.cowork.bikerecoder.core.stats.StoredBestEffort
import com.cowork.bikerecoder.core.stats.StatsCalculator
import com.cowork.bikerecoder.core.stats.StatsPeriod
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.data.goals
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.feed.kmText
import com.cowork.bikerecoder.ui.feed.meterText
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

/** Where the goals live ([SettingsRepository] in the app): one [GoalKm] per activity that has a goal (V1.9.0 spec §4.2). */
interface GoalSettings {
    val goals: Flow<Map<ActivityType, GoalKm>>

    suspend fun setGoals(activity: ActivityType, weeklyKm: Int?, yearlyKm: Int?)
}

/** Where the stats tab's activity chip is remembered (`stats_activity`); null = [전체]. */
interface StatsChipSettings {
    val activity: Flow<ActivityType?>

    suspend fun setActivity(type: ActivityType?)
}

private class RepositoryGoals(private val settings: SettingsRepository) : GoalSettings {
    override val goals: Flow<Map<ActivityType, GoalKm>> = settings.settings.map { it.goals }.distinctUntilChanged()

    override suspend fun setGoals(activity: ActivityType, weeklyKm: Int?, yearlyKm: Int?) =
        settings.setGoals(activity, weeklyKm, yearlyKm)
}

private class RepositoryChip(private val settings: SettingsRepository) : StatsChipSettings {
    override val activity: Flow<ActivityType?> = settings.settings.map { it.statsActivity }.distinctUntilChanged()

    override suspend fun setActivity(type: ActivityType?) = settings.setStatsActivity(type)
}

/** The chip kept in memory only: where nothing is stored. */
private class MemoryChip : StatsChipSettings {
    private val chip = MutableStateFlow<ActivityType?>(null)
    override val activity: Flow<ActivityType?> = chip

    override suspend fun setActivity(type: ActivityType?) {
        chip.value = type
    }
}

/** A goal and how far it is: [distanceM] is this week's (or this year's) distance. */
data class GoalProgress(val goalKm: Int, val distanceM: Double) {
    val reached: Boolean get() = distanceM >= goalKm * 1000.0

    /** The ring or bar fills this much and stops at 1 (spec §3.1). */
    val fraction: Float get() = (distanceM / (goalKm * 1000.0)).coerceIn(0.0, 1.0).toFloat()
}

/** One activity's share of a stacked bar: [value] in the unit of the metric. */
data class BarPart(val activity: ActivityType, val value: Double)

/**
 * A bar: [label] under it, [value] its height in the unit of the metric, [valueText] the line shown when it is tapped,
 * [shortText] the short value written above it ("" for an empty bar or one that has not come yet). Under the [전체] chip
 * [parts] are the activities that make up [value], bottom to top (bike, walk, run, hike); empty = one plain bar.
 */
data class BarState(
    val label: String,
    val value: Double,
    val valueText: String,
    val upcoming: Boolean,
    val shortText: String,
    val parts: List<BarPart> = emptyList(),
)

/** Which cell of the totals row: tags and wording follow it. */
enum class StatCellKind { RIDES, TIME, ELAPSED, ASCENT, PACE }

/** One cell of the totals row under the distance (spec §4.1): [label] over [value]. */
data class StatCell(val kind: StatCellKind, val label: String, val value: String)

/** One activity's goal row under the [전체] chip: its weekly and yearly goal progress (null = no such goal). */
data class ActivityGoal(val activity: ActivityType, val weekly: GoalProgress?, val yearly: GoalProgress?)

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
 * What the stats tab shows (V1.4.0 spec §3, V1.9.0 §4). [activity] is the chip (null = [전체]); everything below is of that
 * activity's rides. [cells] are the totals row under the distance. [weeklyGoal] and [yearlyGoal] are the chip's own goals;
 * under [전체] they are null and [goalRows] has a row for each activity with a goal ([goals] has them all, for the goal
 * window). [records] is empty under two rides; [cumulativeRides], [cumulativeFilter] and [cumulativeActivity] are what the
 * entry to the cumulative map shows and opens.
 */
data class StatsState(
    val activity: ActivityType?,
    val period: StatsPeriod,
    val title: String,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val totals: StatTotals,
    val cells: List<StatCell>,
    val comparisonText: String?,
    val bars: List<BarState>,
    val selectedBar: Int?,
    val metric: StatMetric,
    val unitText: String,
    val weeklyGoal: GoalProgress?,
    val yearlyGoal: GoalProgress?,
    val goalRows: List<ActivityGoal>,
    val goals: Map<ActivityType, GoalKm>,
    val projectionText: String?,
    val streakText: String,
    val records: List<RecordRow>,
    /** Null for [PeriodKind.ALL]. */
    val cumulative: CumulativeChartState?,
    val cumulativeRides: Int,
    val cumulativeFilter: PeriodFilter,
    val cumulativeActivity: ActivityType?,
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
    /** Where the activity chip is remembered; in memory only by default. */
    private val chip: StatsChipSettings = MemoryChip(),
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

    /** Null until the rides, the goals and the chip have been read (review M2: no zeros or "no goal" before them). */
    val state: StateFlow<StatsState?> = combine(
        rides.observeRides().catch { e ->
            log.warn("Could not follow the ride stats", e)
            emit(emptyList())
        },
        rides.observeBestEfforts().catch { e ->
            log.warn("Could not follow the best efforts", e)
            emit(emptyList())
        },
        goals.goals.catch { e ->
            log.warn("Could not follow the goals", e)
            emit(emptyMap())
        },
        chip.activity.catch { e ->
            log.warn("Could not follow the stats activity", e)
            emit(null)
        },
        combine(selection, day) { sel, today -> sel to today },
    ) { all, efforts, goalMap, activity, (sel, today) -> build(all, efforts, goalMap, activity, sel, today) }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Takes today's date again (every resume): a new day may be a new week. */
    fun refresh() {
        day.value = today()
    }

    /** The activity chip: [type] or null for [전체]. It is remembered, and a tapped bar is let go. */
    fun selectActivity(type: ActivityType?) {
        selection.update { it.copy(bar = null) }
        viewModelScope.launch {
            try {
                chip.setActivity(type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not save the stats activity", e)
            }
        }
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

    /** Writes the bike's goals ([saveGoals] for [ActivityType.BIKE]): the week strip's goal window. */
    fun saveGoals(weekly: GoalInput, yearly: GoalInput) = saveGoals(ActivityType.BIKE, weekly, yearly)

    /**
     * Writes both goals of [activity]: [GoalInput.Valid] sets it, [GoalInput.Empty] removes it. If either is [GoalInput.Invalid]
     * nothing is written (the dialog does not let that through).
     */
    fun saveGoals(activity: ActivityType, weekly: GoalInput, yearly: GoalInput) {
        if (weekly is GoalInput.Invalid || yearly is GoalInput.Invalid) return
        viewModelScope.launch {
            try {
                goals.setGoals(activity, (weekly as? GoalInput.Valid)?.km, (yearly as? GoalInput.Valid)?.km)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not save the goals", e)
            }
        }
    }

    private fun build(
        all: List<RideStat>,
        efforts: List<StoredBestEffort>,
        goalMap: Map<ActivityType, GoalKm>,
        chip: ActivityType?,
        sel: Selection,
        today: LocalDate,
    ): StatsState {
        val everyRide = StatsCalculator.rides(all)
        // The chip filters before anything is counted; [전체] counts everything.
        val rides = if (chip == null) everyRide else everyRide.filter { it.activity == chip }
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
        val byActivity = if (chip == null) everyRide.groupBy { it.activity } else emptyMap()
        val bars = StatsCalculator.buckets(rides, period, today).map { barOf(it, sel.metric, period, chip, byActivity) }
        val goal = chip?.let { goalMap[it] }
        val weeklyGoal = goal?.weekly?.let { GoalProgress(it, distanceOfWeek(rides, today)) }
        val yearDistance = distanceOfYear(rides, today)
        val yearlyGoal = goal?.yearly?.let { GoalProgress(it, yearDistance) }
        return StatsState(
            activity = chip,
            period = period,
            title = statsPeriodTitle(period),
            canPrevious = paged && period.start > bounds.start,
            canNext = paged && period.start < bounds.endInclusive,
            totals = totals,
            cells = cellsOf(chip, totals),
            comparisonText = StatsCalculator.comparison(rides, period, today)?.let { comparisonText(it, period) },
            bars = bars,
            selectedBar = sel.bar?.takeIf { bars.getOrNull(it)?.upcoming == false },
            metric = sel.metric,
            unitText = metricUnitText(sel.metric),
            weeklyGoal = weeklyGoal,
            yearlyGoal = yearlyGoal,
            goalRows = if (chip == null) goalRowsOf(goalMap, everyRide, today) else emptyList(),
            goals = goalMap,
            projectionText = if (yearlyGoal == null) null else Goals.yearProjectionKm(yearDistance, today)?.let(::projectionText),
            streakText = streakText(RideRecords.streak(rides, today)),
            records = recordRows(all, today, efforts, chip),
            cumulative = cumulativeOf(rides, period, sel.metric, goal, chip, today),
            cumulativeRides = totals.rides,
            cumulativeFilter = filterOf(period),
            cumulativeActivity = chip,
        )
    }

    private fun distanceOfWeek(rides: List<RideStat>, today: LocalDate): Double {
        val weekStart = mondayOf(today)
        return StatsCalculator.totals(rides, weekStart, weekStart.plusDays(6)).distanceM
    }

    private fun distanceOfYear(rides: List<RideStat>, today: LocalDate): Double {
        val yearStart = today.withDayOfYear(1)
        return StatsCalculator.totals(rides, yearStart, yearStart.plusYears(1).minusDays(1)).distanceM
    }

    /** A row for each activity with a goal, in the order bike, walk, run, hike, measured on that activity's own rides. */
    private fun goalRowsOf(goalMap: Map<ActivityType, GoalKm>, everyRide: List<RideStat>, today: LocalDate): List<ActivityGoal> =
        ActivityType.entries.mapNotNull { type ->
            val goal = goalMap[type]?.takeIf { it.weekly != null || it.yearly != null } ?: return@mapNotNull null
            val own = everyRide.filter { it.activity == type }
            ActivityGoal(
                type,
                goal.weekly?.let { GoalProgress(it, distanceOfWeek(own, today)) },
                goal.yearly?.let { GoalProgress(it, distanceOfYear(own, today)) },
            )
        }

    /**
     * The cells under the distance (spec §4.1): [전체] 횟수 · 이동 시간; a bike (as it was) 횟수 · 이동 시간 · 상승 고도;
     * a walk or run 횟수 · 이동 시간 · 평균 페이스; a hike 횟수 · 산행 시간 · 누적 상승.
     */
    private fun cellsOf(chip: ActivityType?, totals: StatTotals): List<StatCell> {
        val rides = StatCell(StatCellKind.RIDES, ActivityText.statsRidesWord(chip), "${totals.rides}회")
        val time = StatCell(StatCellKind.TIME, "이동 시간", RideFormat.duration(totals.movingTimeMs))
        return when (chip) {
            null -> listOf(rides, time)
            ActivityType.BIKE -> listOf(rides, time, StatCell(StatCellKind.ASCENT, "상승 고도", meterText(totals.ascentM)))
            ActivityType.WALK, ActivityType.RUN -> listOf(
                rides,
                time,
                StatCell(StatCellKind.PACE, ActivityText.LABEL_AVG_PACE, "${ActivityFormat.paceOf(totals.distanceM, totals.movingTimeMs)}/km"),
            )
            ActivityType.HIKE -> listOf(
                rides,
                StatCell(StatCellKind.ELAPSED, ActivityText.LABEL_HIKE_TIME, RideFormat.duration(totals.elapsedMs)),
                StatCell(StatCellKind.ASCENT, ActivityText.LABEL_TOTAL_ASCENT, meterText(totals.ascentM)),
            )
        }
    }

    /** What the metrics read of [totals]: a hike's time is its elapsed time (rests included, spec §2.2), as in its cell. */
    private fun StatTotals.shown(chip: ActivityType?): StatTotals =
        if (chip == ActivityType.HIKE) copy(movingTimeMs = elapsedMs) else this

    private fun barOf(
        bucket: Bucket,
        metric: StatMetric,
        period: StatsPeriod,
        chip: ActivityType?,
        byActivity: Map<ActivityType, List<RideStat>>,
    ): BarState {
        val totals = bucket.totals.shown(chip)
        val parts = if (chip != null || bucket.upcoming) {
            emptyList()
        } else {
            ActivityType.entries.mapNotNull { type ->
                val own = byActivity[type] ?: return@mapNotNull null
                val value = metricValue(metric, StatsCalculator.totals(own, bucket.start, bucket.endInclusive))
                if (value > 0.0) BarPart(type, value) else null
            }
        }
        return BarState(
            label = barAxisLabel(period.kind, bucket.start, period),
            value = if (bucket.upcoming) 0.0 else metricValue(metric, totals),
            valueText = "${barCaptionName(period.kind, bucket.start, period)} · ${metricText(metric, totals)}",
            upcoming = bucket.upcoming,
            shortText = if (bucket.upcoming) "" else barShortText(metric, totals),
            parts = parts,
        )
    }

    private fun cumulativeOf(
        rides: List<RideStat>,
        period: StatsPeriod,
        metric: StatMetric,
        goal: GoalKm?,
        chip: ActivityType?,
        today: LocalDate,
    ): CumulativeChartState? {
        val raw = StatsCalculator.cumulative(rides, period, today) ?: return null
        val series = CumulativeSeries(raw.current.map { it.shown(chip) }, raw.previous.map { it.shown(chip) }, raw.length)
        // What the comparison sentence compares with: the earlier period up to the same day while this one is in progress, all of
        // it once this one is finished.
        val previousAt = StatsCalculator.comparison(rides, period, today)?.previous?.shown(chip)
            ?: series.previous.getOrElse(series.current.lastIndex) { series.previous.last() }
        val goalKm = when {
            metric != StatMetric.DISTANCE -> null
            period.kind == PeriodKind.WEEK -> goal?.weekly
            period.kind == PeriodKind.YEAR -> goal?.yearly
            else -> null
        }
        val (currentName, previousName) = cumulativeNames(period, today)
        return CumulativeChartState(
            current = series.current.map { cumulativeValue(metric, it) },
            previous = series.previous.map { cumulativeValue(metric, it) },
            goal = goalKm?.toDouble(),
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
            initializer {
                StatsViewModel(
                    container.rideStats,
                    RepositoryGoals(container.settings),
                    log = androidWarnLog("Stats"),
                    chip = RepositoryChip(container.settings),
                )
            }
        }
    }
}

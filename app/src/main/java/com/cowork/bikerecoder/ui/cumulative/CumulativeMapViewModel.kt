package com.cowork.bikerecoder.ui.cumulative

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.CumulativeSummary
import com.cowork.bikerecoder.core.post.CumulativeTotals
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.post.WeekOption
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.RideLine
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.ui.history.cardDateText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** [이번 주] / [이번 달] (spec §6.6), the quick period chosen last; null once another period is chosen. */
enum class QuickPeriod { THIS_WEEK, THIS_MONTH }

/**
 * One ride of the list (spec §6.8): its colour on the map, [date] "10월 25일 (일)" and [detail] "1.58km · 12분" (the
 * row's two lines); [text] "10월 25일 (일) · 1.58km · 12분" is the map's info card.
 */
data class RideRow(val segmentId: Long, val color: String, val date: String, val detail: String) {
    val text: String get() = "$date · $detail"
}

/**
 * What the cumulative map shows (spec §4, §6). Only rides of at least [MIN_RIDE_DISTANCE_M][com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M]
 * count anywhere. [entries]: the rides of [period], newest first; [rides]: their rows, each with its colour
 * ([RideColors], newest first); [lines]: the same rides as drawn (those with at least 2 points); [selected]: the
 * highlighted ride, one of [rides] ([selectedRow]); [totals]: their distance, count and moving time. [years]: every year with a ride,
 * newest first; [months]: the months with a ride in [period]'s year (none without a year); [weeks]: the weeks with a
 * ride of [period]'s month (none without a month); [week]: [period]'s week as a week of its month, rides or not
 * (null without a week, or without its month). [quick]: the quick period chosen, if that is what [period] is.
 * [loaded]: the routes have been read at least once. [settled]: they were last read with no post-processing pass
 * running or waiting, so what is missing is not just still being simplified.
 */
data class CumulativeMapState(
    val loaded: Boolean = false,
    val settled: Boolean = false,
    val period: PeriodFilter = PeriodFilter(),
    val quick: QuickPeriod? = null,
    val entries: List<CumulativeEntry> = emptyList(),
    val rides: List<RideRow> = emptyList(),
    val lines: List<RideLine> = emptyList(),
    val selected: Long? = null,
    val totals: CumulativeTotals = CumulativeTotals(0.0, 0, 0L),
    val years: List<Int> = emptyList(),
    val months: List<Int> = emptyList(),
    val weeks: List<WeekOption> = emptyList(),
    val week: WeekOption? = null,
) {
    /**
     * No ride recorded at all ("아직 기록이 없습니다…"). Never while the routes are still being read or a pass may
     * still add some (the first pass after an upgrade simplifies every earlier ride).
     */
    val empty: Boolean get() = loaded && settled && years.isEmpty()

    /** There are rides, but none in [period] ("이 기간에 기록이 없습니다"); same wait as [empty]. */
    val periodEmpty: Boolean get() = loaded && settled && years.isNotEmpty() && entries.isEmpty()

    /** The row of the highlighted ride, for the info card under the map (spec §6.8); null with none highlighted. */
    val selectedRow: RideRow? get() = selected?.let { id -> rides.firstOrNull { it.segmentId == id } }
}

/**
 * Every finished segment's simplified route on one map (spec §4), read from [postStore] only (never the full tracks).
 * A segment shorter than MIN_RIDE_DISTANCE_M (standing still, GPS jitter; spec §6.3) is not shown, framed nor counted.
 *
 * Opening the screen asks for a post-processing pass. The routes are read again whenever a pass ends and whenever a
 * route is simplified during one (the pass goes newest first, so they appear as they come), at most once per
 * [REREAD_SPACING_MS]. A read that fails is retried on the next of these, and every [RETRY_MS] while nothing has been
 * read yet, so the screen never stays without routes for good.
 *
 * [fit] frames every route; it is renewed whenever more routes are shown, until the user moves the map
 * ([onMapMoved]) or highlights a ride ([select], which frames that ride) — after that the camera is theirs.
 * [today] dates [이번 주] and [이번 달].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CumulativeMapViewModel(
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    /** Every ride ([CumulativeSummary.rides], newest first) and whether no pass ran or waited when read; null until read. */
    private val read = MutableStateFlow<Read?>(null)
    private val choice = MutableStateFlow(Choice(PeriodFilter(), null))
    private val selection = MutableStateFlow<Long?>(null)

    val state: StateFlow<CumulativeMapState> = combine(read, choice, selection) { read, choice, selected ->
        Triple(read, choice, selected)
    }
        .mapLatest { (read, choice, selected) ->
            if (read == null) {
                CumulativeMapState(period = choice.period, quick = choice.quick)
            } else {
                withContext(compute) { cumulativeStateOf(read.rides, read.idle, choice.period, choice.quick, selected) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CumulativeMapState())

    private val _fit = MutableStateFlow<CameraFit?>(null)
    val fit: StateFlow<CameraFit?> = _fit.asStateFlow()

    /** Routes with points that [fit] frames. */
    private var framed = 0
    private var userMoved = false

    /** Bumped to read again after a first read failed. */
    private val retry = MutableStateFlow(0)
    private var retryJob: Job? = null

    init {
        // Asked first, so the first read already sees the pass as waiting.
        postProcessor.request()
        viewModelScope.launch {
            combine(postProcessor.finishedRuns, postProcessor.routesSaved, retry) { _, _, _ -> }
                .conflate()
                .collect {
                    load()
                    delay(REREAD_SPACING_MS)
                }
        }
    }

    /** [전체]. */
    fun showAll() = choose(PeriodFilter())

    /** [연도 ▾] → [year]: the whole year (the month and week are cleared). */
    fun selectYear(year: Int) = choose(PeriodFilter(year))

    /** [월 ▾] → [month] of the chosen year, or null for the whole year (the week is cleared); nothing without a year. */
    fun selectMonth(month: Int?) {
        val year = choice.value.period.year ?: return
        choose(PeriodFilter(year, month))
    }

    /** [주 ▾] → the week starting on Monday [start], or null for the whole month; nothing without a month. */
    fun selectWeek(start: LocalDate?) {
        val period = choice.value.period
        if (period.year == null || period.month == null) return
        choose(PeriodFilter(period.year, period.month, start))
    }

    /** [이번 주]: today's Monday-to-Sunday week, shown as a week of today's month. */
    fun thisWeek() {
        val day = today()
        choose(CumulativeSummary.thisWeek(day).copy(year = day.year, month = day.monthValue), QuickPeriod.THIS_WEEK)
    }

    /** [이번 달]: today's month. */
    fun thisMonth() = choose(CumulativeSummary.thisMonth(today()), QuickPeriod.THIS_MONTH)

    /**
     * A tap on a ride's line on the map (a row opens the record instead, spec §6.8): highlights it and frames the map
     * on it; the highlighted ride again, or null (an empty spot on the map), lets go of the highlight and leaves the map
     * where it is. A ride not listed lets go of the highlight too.
     */
    fun select(segmentId: Long?) {
        val shown = state.value
        val ride = shown.entries.firstOrNull { it.segmentId == segmentId }
        if (ride == null || segmentId == shown.selected) {
            selection.value = null
            return
        }
        selection.value = segmentId
        // The camera is on this ride now: routes still coming in no longer reframe everything.
        userMoved = true
        fitOf(listOf(ride))?.let { _fit.value = it }
    }

    private fun choose(period: PeriodFilter, quick: QuickPeriod? = null) {
        selection.value = null
        choice.value = Choice(period, quick)
    }

    /** The user panned, zoomed, rotated or tilted the map: [fit] no longer follows new routes. */
    fun onMapMoved() {
        userMoved = true
    }

    private suspend fun load() {
        val idle = !postProcessor.busy.value
        val entries = try {
            postStore.cumulativeEntries()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the simplified routes", e)
            if (read.value == null) retryLater()
            return
        }
        val rides = withContext(compute) { CumulativeSummary.rides(entries) }
        val withPoints = rides.count { it.points.isNotEmpty() }
        if (!userMoved && withPoints > framed) {
            withContext(compute) { fitOf(rides) }?.let { _fit.value = it }
            framed = withPoints
        }
        read.value = Read(rides, idle)
    }

    private fun retryLater() {
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(RETRY_MS)
            retry.update { it + 1 }
        }
    }

    companion object {
        /** Least time between two reads while routes keep coming. */
        const val REREAD_SPACING_MS = 500L

        /** Wait before reading again after a first read failed (sooner if a pass ends or a route comes). */
        const val RETRY_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CumulativeMapViewModel(container.postStore, container.postProcessor, androidWarnLog("CumulativeMap"))
            }
        }
    }
}

private class Read(val rides: List<CumulativeEntry>, val idle: Boolean)

private data class Choice(val period: PeriodFilter, val quick: QuickPeriod?)

/**
 * The screen for [rides] (already [CumulativeSummary.rides]: at least the minimum distance, newest first, worked out
 * once per read) in [period]. [selected] is kept only if it is a ride of the period.
 */
internal fun cumulativeStateOf(
    rides: List<CumulativeEntry>,
    idle: Boolean,
    period: PeriodFilter,
    quick: QuickPeriod?,
    selected: Long?,
): CumulativeMapState {
    val entries = CumulativeSummary.filter(rides, period)
    val colors = entries.indices.map(RideColors::colorAt)
    val year = period.year
    val month = period.month
    return CumulativeMapState(
        loaded = true,
        settled = idle,
        period = period,
        quick = quick,
        entries = entries,
        rides = entries.mapIndexed { i, e -> rideRowOf(e, colors[i]) },
        lines = entries.mapIndexedNotNull { i, e -> if (e.points.size >= 2) RideLine(e.segmentId, e.points, colors[i]) else null },
        selected = selected?.takeIf { id -> entries.any { it.segmentId == id } },
        totals = CumulativeSummary.totals(entries),
        years = CumulativeSummary.years(rides),
        months = year?.let { CumulativeSummary.months(rides, it) }.orEmpty(),
        weeks = if (year != null && month != null) CumulativeSummary.weeks(rides, year, month) else emptyList(),
        week = period.weekStart?.let { start ->
            if (year != null && month != null) CumulativeSummary.weekOf(start, year, month) else null
        },
    )
}

/** [entry]'s row in [color] (spec §6.8): "M월 d일 (E)", e.g. "10월 25일 (일)", and "{거리} · {이동 시간}", e.g. "1.58km · 12분". */
internal fun rideRowOf(entry: CumulativeEntry, color: String): RideRow = RideRow(
    segmentId = entry.segmentId,
    color = color,
    date = cardDateText(entry.localDate),
    detail = "${RideFormat.distance(entry.distanceM)} · ${RideFormat.duration(entry.movingTimeMs)}",
)

/** The south-west and north-east corners of every point of [entries] (one point if they are the same); null if none. */
internal fun fitOf(entries: List<CumulativeEntry>): CameraFit? {
    var minLat = Double.POSITIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    for (entry in entries) {
        for (p in entry.points) {
            minLat = minOf(minLat, p.lat)
            minLon = minOf(minLon, p.lon)
            maxLat = maxOf(maxLat, p.lat)
            maxLon = maxOf(maxLon, p.lon)
        }
    }
    if (minLat > maxLat) return null
    val southWest = GeoPoint(minLat, minLon)
    val northEast = GeoPoint(maxLat, maxLon)
    return CameraFit(if (southWest == northEast) listOf(southWest) else listOf(southWest, northEast))
}

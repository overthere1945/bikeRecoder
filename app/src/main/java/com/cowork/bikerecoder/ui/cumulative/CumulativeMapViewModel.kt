package com.cowork.bikerecoder.ui.cumulative

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.CumulativeSummary
import com.cowork.bikerecoder.core.post.CumulativeTotals
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
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

/**
 * What the cumulative map shows (spec §4). [entries]: the routes of [period]; [lines]: their points (those with at
 * least 2), drawn on top of one another; [totals]: their distance, count and moving time. [years]: every year with a
 * ride, newest first; [months]: the months with a ride in [period]'s year (none without a year). [loaded]: the routes
 * have been read at least once. [settled]: they were last read with no post-processing pass running or waiting, so
 * what is missing is not just still being simplified.
 */
data class CumulativeMapState(
    val loaded: Boolean = false,
    val settled: Boolean = false,
    val period: PeriodFilter = PeriodFilter(),
    val entries: List<CumulativeEntry> = emptyList(),
    val lines: List<List<GeoPoint>> = emptyList(),
    val totals: CumulativeTotals = CumulativeTotals(0.0, 0, 0L),
    val years: List<Int> = emptyList(),
    val months: List<Int> = emptyList(),
) {
    /**
     * No ride recorded at all ("아직 기록이 없습니다…"). Never while the routes are still being read or a pass may
     * still add some (the first pass after an upgrade simplifies every earlier ride).
     */
    val empty: Boolean get() = loaded && settled && years.isEmpty()
}

/**
 * Every finished segment's simplified route on one map (spec §4), read from [postStore] only (never the full tracks).
 * A segment that never moved (distanceM == 0, left out of the history lists too, ruling R12) is not shown nor counted.
 *
 * Opening the screen asks for a post-processing pass. The routes are read again whenever a pass ends and whenever a
 * route is simplified during one (the pass goes newest first, so they appear as they come), at most once per
 * [REREAD_SPACING_MS]. A read that fails is retried on the next of these, and every [RETRY_MS] while nothing has been
 * read yet, so the screen never stays without routes for good.
 *
 * [fit] frames every route; it is renewed whenever more routes are shown, until the user moves the map
 * ([onMapMoved]) — after that the camera is theirs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CumulativeMapViewModel(
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** Every shown route, oldest first, and whether no pass ran or waited when it was read; null until read. */
    private val read = MutableStateFlow<Read?>(null)
    private val period = MutableStateFlow(PeriodFilter())

    val state: StateFlow<CumulativeMapState> = combine(read, period) { read, period -> read to period }
        .mapLatest { (read, period) ->
            if (read == null) CumulativeMapState(period = period) else withContext(compute) { stateOf(read, period) }
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
    fun showAll() {
        period.value = PeriodFilter()
    }

    /** [연도 ▾] → [year]: the whole year (the month is cleared). */
    fun selectYear(year: Int) {
        period.value = PeriodFilter(year)
    }

    /** [월 ▾] → [month] of the chosen year, or null for the whole year; nothing without a year. */
    fun selectMonth(month: Int?) {
        period.update { current -> current.year?.let { PeriodFilter(it, month) } ?: current }
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
        val shown = entries.filterNot { it.distanceM <= 0.0 }
        val withPoints = shown.count { it.points.isNotEmpty() }
        if (!userMoved && withPoints > framed) {
            withContext(compute) { fitOf(shown) }?.let { _fit.value = it }
            framed = withPoints
        }
        read.value = Read(shown, idle)
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

private class Read(val entries: List<CumulativeEntry>, val idle: Boolean)

private fun stateOf(read: Read, period: PeriodFilter): CumulativeMapState {
    val all = read.entries
    val entries = CumulativeSummary.filter(all, period)
    return CumulativeMapState(
        loaded = true,
        settled = read.idle,
        period = period,
        entries = entries,
        lines = entries.map { it.points }.filter { it.size >= 2 },
        totals = CumulativeSummary.totals(entries),
        years = CumulativeSummary.years(all),
        months = period.year?.let { CumulativeSummary.months(all, it) }.orEmpty(),
    )
}

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

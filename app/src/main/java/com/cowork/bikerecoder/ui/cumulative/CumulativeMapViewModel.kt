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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the cumulative map shows (spec §4). [entries]: the routes of [period]; [lines]: their points (those with at
 * least 2), drawn on top of one another; [totals]: their distance, count and moving time. [years]: every year with a
 * ride, newest first; [months]: the months with a ride in [period]'s year (none without a year). [loaded]: the routes
 * have been read at least once.
 */
data class CumulativeMapState(
    val loaded: Boolean = false,
    val period: PeriodFilter = PeriodFilter(),
    val entries: List<CumulativeEntry> = emptyList(),
    val lines: List<List<GeoPoint>> = emptyList(),
    val totals: CumulativeTotals = CumulativeTotals(0.0, 0, 0L),
    val years: List<Int> = emptyList(),
    val months: List<Int> = emptyList(),
) {
    /** No ride recorded at all ("아직 기록이 없습니다…"); not while the routes are still being read. */
    val empty: Boolean get() = loaded && years.isEmpty()
}

/**
 * Every finished segment's simplified route on one map (spec §4), read from [postStore] only (never the full tracks).
 * A segment that never moved (distanceM == 0, left out of the history lists too) is not shown nor counted as a ride.
 * Opening the screen asks for a post-processing pass; the routes are read again whenever a pass ends, so routes
 * simplified meanwhile appear. [fit] frames every route once, the first time there is one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CumulativeMapViewModel(
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** Every shown route, oldest first; null until read. */
    private val all = MutableStateFlow<List<CumulativeEntry>?>(null)
    private val period = MutableStateFlow(PeriodFilter())

    val state: StateFlow<CumulativeMapState> = combine(all, period) { all, period -> all to period }
        .mapLatest { (all, period) -> if (all == null) CumulativeMapState(period = period) else withContext(compute) { stateOf(all, period) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CumulativeMapState())

    private val _fit = MutableStateFlow<CameraFit?>(null)
    val fit: StateFlow<CameraFit?> = _fit.asStateFlow()

    init {
        // The current count reads at once; every pass that ends reads again (a newer end cancels a read in progress).
        viewModelScope.launch { postProcessor.finishedRuns.collectLatest { load() } }
        postProcessor.request()
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

    private suspend fun load() {
        val entries = try {
            postStore.cumulativeEntries()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the simplified routes", e)
            return
        }
        val shown = entries.filterNot { it.distanceM <= 0.0 }
        if (_fit.value == null) _fit.value = withContext(compute) { fitOf(shown) }
        all.value = shown
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CumulativeMapViewModel(container.postStore, container.postProcessor, androidWarnLog("CumulativeMap"))
            }
        }
    }
}

private fun stateOf(all: List<CumulativeEntry>, period: PeriodFilter): CumulativeMapState {
    val entries = CumulativeSummary.filter(all, period)
    return CumulativeMapState(
        loaded = true,
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

package com.cowork.bikerecoder.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TripDeleteCondition
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.SegmentCard
import com.cowork.bikerecoder.ride.TripDeleter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.YearMonth

/** The dialog over the history list or the trip detail. */
sealed interface HistoryDialog {
    /** Why it cannot be deleted. */
    data class Message(val text: String) : HistoryDialog

    /** "Delete?" for a trip or a segment id. */
    data class ConfirmDelete(val id: Long) : HistoryDialog

    /** "짧은 기록 {count}개를 삭제할까요?" for [정리] (spec §6.10). */
    data class ConfirmCleanup(val count: Int) : HistoryDialog
}

/**
 * A trip's card in the history (spec §6.11): [date] "M월 d일 (E)" of its first day, [badge] "당일" / "여러 날 · N일",
 * [line2] "{거리} · {이동 시간} · {평균}km/h", the photos not hidden ([photoCount]), the average heart rate weighted by
 * moving time ([avgBpm], null if no segment has one), and each segment's simplified route with the colour of its place
 * in the trip ([routes]; segments without a route yet are left out).
 */
data class TripCard(
    val tripId: Long,
    val date: String,
    val badge: String,
    val line2: String,
    val photoCount: Int,
    val avgBpm: Int?,
    val routes: List<Pair<List<GeoPoint>, String>>,
)

/** A month of cards under "YYYY년 M월 · {거리} · {n}회" (spec §6.11). */
data class MonthGroup(val header: String, val cards: List<TripCard>)

/**
 * The ride history (spec §3.5): trips with a recording, newest first. A trip in progress (ACTIVE, or still being
 * recorded) cannot be deleted; otherwise deleting asks first and then removes the trip with its stops, segments and
 * points, and its offline maps (whose refs have no foreign key to the trip).
 *
 * Short trips (spec §6.10: under [MIN_RIDE_DISTANCE_M] in all, or all 0 m (R21), not in progress) are not listed but
 * counted ([hiddenShortCount]); [정리] deletes them after asking. Nothing is deleted without asking, and every delete is
 * conditional in the store ([TripDeleter], ruling R20).
 *
 * The listed trips are also shown as [months] of cards (spec §6.11), whose routes, photos and heart rate come from
 * one read of [postStore] per load (not one per trip); if that read fails the cards are shown without them.
 */
class HistoryViewModel(
    private val rideStore: RideStore,
    private val postStore: PostStore,
    private val offlineMaps: OfflineMapController,
    private val log: WarnLog,
) : ViewModel() {

    /** Every trip delete here goes through it: conditional, then the offline maps (ruling R20). */
    private val deleter = TripDeleter(rideStore, offlineMaps, log)

    private val _trips = MutableStateFlow<List<TripRideSummary>>(emptyList())
    val trips: StateFlow<List<TripRideSummary>> = _trips.asStateFlow()

    private val _months = MutableStateFlow<List<MonthGroup>>(emptyList())

    /** The listed trips' cards by month of their first date, newest month first; the cards newest first. */
    val months: StateFlow<List<MonthGroup>> = _months.asStateFlow()

    /** The short trips not listed, as last read. */
    private var hiddenShort: List<Long> = emptyList()

    private val _hiddenShortCount = MutableStateFlow(0)

    /** "짧은 기록 N개 숨김": the short trips not listed. */
    val hiddenShortCount: StateFlow<Int> = _hiddenShortCount.asStateFlow()

    /** The short trips the cleanup question counted (only these may be deleted by [confirmCleanup]). */
    private var cleanupIds: List<Long> = emptyList()

    private val _dialog = MutableStateFlow<HistoryDialog?>(null)
    val dialog: StateFlow<HistoryDialog?> = _dialog.asStateFlow()

    /** One store operation at a time, so a reload never overwrites a newer list. */
    private val mutex = Mutex()

    init {
        refresh()
    }

    /** Reads the list again (e.g. back from a trip whose segments were deleted). */
    fun refresh() {
        viewModelScope.launch { mutex.withLock { load() } }
    }

    fun requestDelete(tripId: Long) {
        viewModelScope.launch {
            mutex.withLock {
                val inProgress = guarded("Could not check trip $tripId") { inProgress(tripId) } ?: return@withLock
                _dialog.value = if (inProgress) {
                    HistoryDialog.Message(MSG_ACTIVE_TRIP_NOT_DELETABLE)
                } else {
                    HistoryDialog.ConfirmDelete(tripId)
                }
            }
        }
    }

    /** [삭제] on the confirmation; checked again, as the trip may have started meanwhile. */
    fun confirmDelete() {
        val tripId = (_dialog.value as? HistoryDialog.ConfirmDelete)?.id ?: return
        _dialog.value = null
        viewModelScope.launch {
            mutex.withLock {
                val inProgress = guarded("Could not check trip $tripId") { inProgress(tripId) } ?: return@withLock
                if (inProgress) {
                    _dialog.value = HistoryDialog.Message(MSG_ACTIVE_TRIP_NOT_DELETABLE)
                    return@withLock
                }
                // In one statement, only if still COMPLETED and not recording (it may have started meanwhile).
                val deleted = guarded("Could not delete trip $tripId") {
                    deleter.delete(tripId, TripDeleteCondition.NOT_RECORDING)
                }
                if (deleted == false) _dialog.value = HistoryDialog.Message(MSG_ACTIVE_TRIP_NOT_DELETABLE)
                load()
            }
        }
    }

    /** [정리]: asks "짧은 기록 N개를 삭제할까요?" when short trips are hidden. */
    fun requestCleanup() {
        val ids = hiddenShort
        if (ids.isEmpty()) return
        cleanupIds = ids
        _dialog.value = HistoryDialog.ConfirmCleanup(ids.size)
    }

    /**
     * [삭제] on the cleanup question: deletes the short trips it counted that are still short and not in progress (checked
     * again — one may have started meanwhile), with their offline maps, then reads the list again.
     */
    fun confirmCleanup() {
        if (_dialog.value !is HistoryDialog.ConfirmCleanup) return
        _dialog.value = null
        val counted = cleanupIds.toSet()
        cleanupIds = emptyList()
        viewModelScope.launch {
            mutex.withLock {
                val now = guarded("Could not read the ride history") { split() } ?: return@withLock
                for (tripId in now.hidden.filter { it in counted }) {
                    // Checked again in the deleting statement: COMPLETED, nothing open, still under 10 m.
                    guarded("Could not delete trip $tripId") {
                        deleter.delete(tripId, TripDeleteCondition.SHORT_NOT_RECORDING)
                    }
                }
                load()
            }
        }
    }

    fun dismiss() {
        _dialog.value = null
    }

    /** ACTIVE, or a segment of it is still open (being recorded, e.g. guidance arrival completed the trip). */
    private suspend fun inProgress(tripId: Long): Boolean {
        val summary = rideStore.tripSummaries().find { it.trip.id == tripId }
        return summary?.trip?.status == TripStatus.ACTIVE || rideStore.openSegments().any { it.tripId == tripId }
    }

    private suspend fun load() {
        guarded("Could not read the ride history") {
            val split = split()
            val segments = guarded("Could not read the history cards' routes, photos and heart rate") {
                postStore.segmentCards()
            }.orEmpty()
            _trips.value = split.shown
            _months.value = historyMonths(split.shown, segments)
            hiddenShort = split.hidden
            _hiddenShortCount.value = split.hidden.size
        }
    }

    /**
     * The listed trips and the short ones hidden: under [MIN_RIDE_DISTANCE_M] in all, not ACTIVE, nothing open — also
     * the trips whose segments are all 0 m, which are never listed (ruling R21).
     */
    private suspend fun split(): HistorySplit {
        val all = rideStore.tripSummaries()
        val empty = rideStore.emptyTrips()
        val open = rideStore.openSegments().mapTo(HashSet()) { it.tripId }
        fun finished(trip: Trip) = trip.status != TripStatus.ACTIVE && trip.id !in open
        val (hidden, shown) = all.partition { it.distanceM < MIN_RIDE_DISTANCE_M && finished(it.trip) }
        val hiddenEmpty = empty.filter(::finished).map { it.id }
        return HistorySplit(shown, hidden.map { it.trip.id } + hiddenEmpty)
    }

    private class HistorySplit(val shown: List<TripRideSummary>, val hidden: List<Long>)

    /** [block]'s result, or null when it failed (logged). */
    private suspend fun <T> guarded(message: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(message, e)
        null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(container.rideStore, container.postStore, container.offline, androidWarnLog("History"))
            }
        }
    }
}

/**
 * [shown] (newest first) as cards grouped by the month of their first date, newest month first (a trip that began in
 * an earlier month stays there even if it is newer than the next month's trips). [segments]: the listed segments of
 * every trip in trip and start order ([PostStore.segmentCards]).
 */
internal fun historyMonths(shown: List<TripRideSummary>, segments: List<SegmentCard>): List<MonthGroup> {
    val byTrip = segments.groupBy { it.tripId }
    return shown.groupBy { YearMonth.from(it.firstDate) }.toSortedMap(reverseOrder())
        .map { (month, trips) ->
            MonthGroup(
                header = monthHeaderText(month.year, month.monthValue, trips.sumOf { it.distanceM }, trips.size),
                cards = trips.map { tripCard(it, byTrip[it.trip.id].orEmpty()) },
            )
        }
}

private fun tripCard(summary: TripRideSummary, segments: List<SegmentCard>) = TripCard(
    tripId = summary.trip.id,
    date = cardDateText(summary.firstDate),
    badge = cardBadgeText(summary),
    line2 = cardLine2Text(summary),
    photoCount = segments.sumOf { it.photoCount },
    avgBpm = HeartRateSummary.tripAverage(segments.mapNotNull { s -> s.heartRate?.let { it to s.movingTimeMs } }),
    routes = segments.mapIndexedNotNull { index, s -> s.route?.let { it to RideColors.colorAt(index) } },
)

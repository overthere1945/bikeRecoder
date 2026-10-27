package com.cowork.bikerecoder.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.TripStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * The ride history (spec §3.5): trips with a recording, newest first. A trip in progress (ACTIVE, or still being
 * recorded) cannot be deleted; otherwise deleting asks first and then removes the trip with its stops, segments and
 * points, and its offline maps (whose refs have no foreign key to the trip).
 *
 * Short trips (spec §6.10: under [MIN_RIDE_DISTANCE_M] in all, not in progress) are not listed but counted
 * ([hiddenShortCount]); [정리] deletes them after asking. Nothing is deleted without asking.
 */
class HistoryViewModel(
    private val rideStore: RideStore,
    private val offlineMaps: OfflineMapController,
    private val log: WarnLog,
) : ViewModel() {

    private val _trips = MutableStateFlow<List<TripRideSummary>>(emptyList())
    val trips: StateFlow<List<TripRideSummary>> = _trips.asStateFlow()

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
                guarded("Could not delete the offline maps of trip $tripId") { offlineMaps.deleteForTrip(tripId) }
                guarded("Could not delete trip $tripId") { rideStore.deleteTrip(tripId) }
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
                    guarded("Could not delete the offline maps of trip $tripId") { offlineMaps.deleteForTrip(tripId) }
                    guarded("Could not delete trip $tripId") { rideStore.deleteTrip(tripId) }
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
            _trips.value = split.shown
            hiddenShort = split.hidden
            _hiddenShortCount.value = split.hidden.size
        }
    }

    /** The listed trips and the short ones hidden (under [MIN_RIDE_DISTANCE_M] in all, not ACTIVE, nothing open). */
    private suspend fun split(): HistorySplit {
        val all = rideStore.tripSummaries()
        val open = rideStore.openSegments().mapTo(HashSet()) { it.tripId }
        val (hidden, shown) = all.partition {
            it.distanceM < MIN_RIDE_DISTANCE_M && it.trip.status != TripStatus.ACTIVE && it.trip.id !in open
        }
        return HistorySplit(shown, hidden.map { it.trip.id })
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
            initializer { HistoryViewModel(container.rideStore, container.offline, androidWarnLog("History")) }
        }
    }
}

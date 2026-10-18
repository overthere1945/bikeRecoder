package com.cowork.bikerecoder.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
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
}

/**
 * The ride history (spec §3.5): trips with a recording, newest first. A trip in progress (ACTIVE, or still being
 * recorded) cannot be deleted; otherwise deleting asks first and then removes the trip with its stops, segments and
 * points, and its offline maps (whose refs have no foreign key to the trip).
 */
class HistoryViewModel(
    private val rideStore: RideStore,
    private val offlineMaps: OfflineMapController,
    private val log: WarnLog,
) : ViewModel() {

    private val _trips = MutableStateFlow<List<TripRideSummary>>(emptyList())
    val trips: StateFlow<List<TripRideSummary>> = _trips.asStateFlow()

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

    fun dismiss() {
        _dialog.value = null
    }

    /** ACTIVE, or a segment of it is still open (being recorded, e.g. guidance arrival completed the trip). */
    private suspend fun inProgress(tripId: Long): Boolean {
        val summary = rideStore.tripSummaries().find { it.trip.id == tripId }
        return summary?.trip?.status == TripStatus.ACTIVE || rideStore.openSegments().any { it.tripId == tripId }
    }

    private suspend fun load() {
        guarded("Could not read the ride history") { _trips.value = rideStore.tripSummaries() }
    }

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

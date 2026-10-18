package com.cowork.bikerecoder.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.RidePhoto
import com.cowork.bikerecoder.ui.photo.RidePhotos
import com.cowork.bikerecoder.ui.photo.observeRidePhotos
import com.cowork.bikerecoder.ui.photo.setPhotoHidden
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * One trip's segments by date (oldest first) and the trip's totals (spec §3.5), without empty segments
 * ([RideSegment.isEmpty]). An open segment (still being recorded,
 * endedAt == null) cannot be deleted; a closed one is deleted after confirmation.
 * The map shows the listed segments' tracks joined in order ([track]) and the photos of every day ([photos], spec §2.3).
 * The first load drops the photos deleted from the gallery and asks for a post-processing pass, once per screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModel(
    private val rideStore: RideStore,
    private val tripId: Long,
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _segmentsByDate = MutableStateFlow<Map<LocalDate, List<RideSegment>>>(emptyMap())
    val segmentsByDate: StateFlow<Map<LocalDate, List<RideSegment>>> = _segmentsByDate.asStateFlow()

    private val _total = MutableStateFlow(SegmentTotals.ZERO)
    val total: StateFlow<SegmentTotals> = _total.asStateFlow()

    private val _dialog = MutableStateFlow<HistoryDialog?>(null)
    val dialog: StateFlow<HistoryDialog?> = _dialog.asStateFlow()

    private val _track = MutableStateFlow<List<GeoPoint>>(emptyList())
    val track: StateFlow<List<GeoPoint>> = _track.asStateFlow()

    /** The listed segments' ids; null until the first load. */
    private val segmentIds = MutableStateFlow<List<Long>?>(null)

    val photos: StateFlow<RidePhotos> = segmentIds.filterNotNull()
        .flatMapLatest { ids -> observeRidePhotos(postStore.observePhotos(ids), log) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RidePhotos())

    private val mutex = Mutex()

    /** The segments [track] was read for (it is read again only when they change, e.g. a recording went on). */
    private var trackOf: List<RideSegment>? = null
    private var opened = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { mutex.withLock { load() } }
    }

    fun requestDeleteSegment(segmentId: Long) {
        viewModelScope.launch {
            mutex.withLock {
                val open = guarded("Could not read segment $segmentId") { isOpen(segmentId) } ?: return@withLock
                _dialog.value = if (open) {
                    HistoryDialog.Message(MSG_OPEN_SEGMENT_NOT_DELETABLE)
                } else {
                    HistoryDialog.ConfirmDelete(segmentId)
                }
            }
        }
    }

    /** [삭제] on the confirmation; checked again, as a recording may have reopened meanwhile. */
    fun confirmDelete() {
        val segmentId = (_dialog.value as? HistoryDialog.ConfirmDelete)?.id ?: return
        _dialog.value = null
        viewModelScope.launch {
            mutex.withLock {
                val open = guarded("Could not read segment $segmentId") { isOpen(segmentId) } ?: return@withLock
                if (open) {
                    _dialog.value = HistoryDialog.Message(MSG_OPEN_SEGMENT_NOT_DELETABLE)
                    return@withLock
                }
                guarded("Could not delete segment $segmentId") { rideStore.deleteSegment(segmentId) }
                load()
            }
        }
    }

    fun dismiss() {
        _dialog.value = null
    }

    /** [숨기기] ([hidden] = true) / [다시 보이기] in the full-screen view. */
    fun setHidden(photo: RidePhoto, hidden: Boolean) {
        viewModelScope.launch { setPhotoHidden(postStore, photo, hidden, log) }
    }

    /** A segment already gone counts as closed (deleting it again is harmless). */
    private suspend fun isOpen(segmentId: Long): Boolean = rideStore.segment(segmentId)?.let { it.endedAt == null } ?: false

    private suspend fun load() {
        guarded("Could not read the segments of trip $tripId") {
            // Empty segments are kept in the store but not listed (nor counted in the totals).
            val segments = rideStore.segments(tripId).filterNot { it.isEmpty }
            _segmentsByDate.value = segments.groupBy { it.localDate }.toSortedMap()
            _total.value = tripTotals(segments)
            val ids = segments.map { it.id }
            segmentIds.value = ids
            if (!opened) {
                opened = true
                viewModelScope.launch { postProcessor.pruneDeletedPhotos(ids) }
                postProcessor.request()
            }
            if (segments != trackOf) {
                // In the order listed (by date, then start); the segments' points are read one after another.
                val ordered = _segmentsByDate.value.values.flatten()
                val points = ordered.map { rideStore.points(it.id) }
                _track.value = withContext(compute) { points.flatMap { list -> list.map { it.point } } }
                trackOf = segments
            }
        }
    }

    private suspend fun <T> guarded(message: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(message, e)
        null
    }

    companion object {
        const val ARG_TRIP = "tripId"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val tripId = requireNotNull(createSavedStateHandle().get<Long>(ARG_TRIP)) { "missing $ARG_TRIP" }
                TripDetailViewModel(
                    container.rideStore, tripId, container.postStore, container.postProcessor, androidWarnLog("TripDetail"),
                )
            }
        }
    }
}

/** The trip's totals: sums, except the highest speed and the altitude range (over the segments that know them). */
internal fun tripTotals(segments: List<RideSegment>): SegmentTotals {
    if (segments.isEmpty()) return SegmentTotals.ZERO
    val totals = segments.map { it.totals }
    return SegmentTotals(
        distanceM = totals.sumOf { it.distanceM },
        movingTimeMs = totals.sumOf { it.movingTimeMs },
        elapsedMs = totals.sumOf { it.elapsedMs },
        maxSpeedMps = totals.maxOf { it.maxSpeedMps },
        ascentM = totals.sumOf { it.ascentM },
        descentM = totals.sumOf { it.descentM },
        minAltitudeM = totals.mapNotNull { it.minAltitudeM }.minOrNull(),
        maxAltitudeM = totals.mapNotNull { it.maxAltitudeM }.maxOrNull(),
    )
}

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
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.post.HeartRateStats
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.map.MapLine
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.RidePhoto
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ui.health.TrackColorMode
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
 * The map shows each listed segment's track as its own line, in order ([lines], ruling R10), coloured in
 * [TrackColorMode.HEART_RATE] by that segment's own samples, and the photos of every day ([photos], spec §2.3).
 * [heartRate]: the trip's time-weighted average, highest heart rate and calories, and each day's average (spec §3.3).
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

    /** The listed segments' ids and track points, in the order listed. */
    private val tracks = MutableStateFlow<List<Pair<Long, List<TrackPoint>>>>(emptyList())

    /** The listed segments' ids; null until the first load. */
    private val segmentIds = MutableStateFlow<List<Long>?>(null)

    val photos: StateFlow<RidePhotos> = segmentIds.filterNotNull()
        .flatMapLatest { ids -> observeRidePhotos(postStore.observePhotos(ids), log) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RidePhotos())

    private val extras: StateFlow<List<SegmentExtras>> = segmentIds.filterNotNull()
        .flatMapLatest { ids ->
            postStore.observeExtras(ids).catch { e ->
                log.warn("Could not read the heart rate of trip $tripId", e)
                emit(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val heartRate: StateFlow<TripHeartRate> = combine(_segmentsByDate, extras, ::tripHeartRate)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TripHeartRate())

    private val _colorMode = MutableStateFlow(TrackColorMode.DEFAULT)

    /** [기본] first, every time the screen opens (not remembered). */
    val colorMode: StateFlow<TrackColorMode> = _colorMode.asStateFlow()

    /** When each READY segment's samples were stored: its samples are read again when this changes. */
    private val readyAt = extras
        .map { list -> list.filter { it.hrStatus == HrStatus.READY }.associate { it.segmentId to it.hrLastTriedAt } }
        .distinctUntilChanged()

    /**
     * One line per listed segment; in [TrackColorMode.HEART_RATE] each coloured by its own samples — only while [심박]
     * is offered ([TripHeartRate.canColor]), so lines are never coloured without the toggle and the key.
     */
    val lines: StateFlow<List<MapLine>> = combine(
        tracks,
        colorMode,
        readyAt,
        heartRate.map { it.canColor }.distinctUntilChanged(),
    ) { tracks, mode, _, canColor -> tracks to (if (canColor) mode else TrackColorMode.DEFAULT) }
        .mapLatest { (tracks, mode) ->
            if (mode == TrackColorMode.DEFAULT) {
                withContext(compute) { tracks.map { (_, points) -> MapLine(points.map { it.point }) } }
            } else {
                val samples = tracks.map { (id, _) -> guarded("Could not read the samples of segment $id") { postStore.samples(id) } }
                withContext(compute) {
                    tracks.mapIndexed { i, (_, points) ->
                        MapLine(
                            points.map { it.point },
                            HeartRateColor.bands(points, samples[i].orEmpty()).map { it.colorHex },
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _refetching = MutableStateFlow(false)

    /** True while [refetchHeartRate] goes through the trip's segments ([다시 가져오기] is disabled meanwhile). */
    val refetching: StateFlow<Boolean> = _refetching.asStateFlow()

    private val mutex = Mutex()

    /** The segments [lines] were read for (read again only when they change, e.g. a recording went on). */
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

    fun setColorMode(mode: TrackColorMode) {
        _colorMode.value = mode
    }

    /**
     * The trip's [다시 가져오기] (spec §6.9): every closed segment's heart rate is read again, one after another, by
     * [PostProcessor.refetchHeartRate] (which keeps stored heart rate when nothing better comes, rulings R7, R17). Ignored
     * while a pass is running; a segment that fails is skipped. The totals follow the stored extras.
     */
    fun refetchHeartRate() {
        if (!_refetching.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val ids = guarded("Could not read the segments of trip $tripId") {
                    rideStore.segments(tripId).filter { it.endedAt != null }.map { it.id }
                }.orEmpty()
                for (id in ids) {
                    guarded("Heart rate refetch failed for segment $id") { postProcessor.refetchHeartRate(id) }
                }
            } finally {
                _refetching.value = false
            }
        }
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
                tracks.value = ordered.map { it.id to rideStore.points(it.id) }
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

/**
 * The trip's heart rate (spec §3.3) over its READY segments: average weighted by moving time
 * ([HeartRateSummary.tripAverage]), the highest, the calories that are known summed (null if none is), and each date's
 * average. [canColor]: some segment has detailed heart rate, so [심박] is offered on the map.
 */
data class TripHeartRate(
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    val calories: Double? = null,
    val avgByDate: Map<LocalDate, Int> = emptyMap(),
    val canColor: Boolean = false,
)

internal fun tripHeartRate(segmentsByDate: Map<LocalDate, List<RideSegment>>, extras: List<SegmentExtras>): TripHeartRate {
    val byId = extras.filter { it.hrStatus == HrStatus.READY }.associateBy { it.segmentId }
    fun parts(segments: List<RideSegment>) = segments.mapNotNull { s ->
        val e = byId[s.id] ?: return@mapNotNull null
        val avg = e.avgBpm ?: return@mapNotNull null
        val max = e.maxBpm ?: return@mapNotNull null
        Triple(HeartRateStats(avg, max, e.hrDetailed), s.totals.movingTimeMs, e.calories)
    }
    val all = parts(segmentsByDate.values.flatten())
    if (all.isEmpty()) return TripHeartRate()
    return TripHeartRate(
        avgBpm = HeartRateSummary.tripAverage(all.map { it.first to it.second }),
        maxBpm = all.maxOf { it.first.maxBpm },
        calories = all.mapNotNull { it.third }.takeIf { it.isNotEmpty() }?.sum(),
        avgByDate = segmentsByDate.mapNotNull { (date, segments) ->
            HeartRateSummary.tripAverage(parts(segments).map { it.first to it.second })?.let { date to it }
        }.toMap(),
        canColor = all.any { it.first.detailed },
    )
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

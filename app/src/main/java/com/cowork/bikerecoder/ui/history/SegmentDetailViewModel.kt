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
import com.cowork.bikerecoder.core.post.HeartRateCharts
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.ChartPoint
import com.cowork.bikerecoder.core.recording.RideCharts
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TrackPoint
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SegmentDetailState {
    data object Loading : SegmentDetailState

    /** Deleted meanwhile, or could not be read. */
    data object Missing : SegmentDetailState

    data class Loaded(
        val segment: RideSegment,
        val track: List<GeoPoint>,
        val altitude: List<ChartPoint>,
        val speed: List<ChartPoint>,
    ) : SegmentDetailState
}

/**
 * One segment (spec §3.5): its track for the map and the altitude/speed charts over distance, its photos (spec §2.3)
 * and its heart rate (spec §3.3: the row, the chart over the same distance axis, the [기본]/[심박] track colours).
 * Opening it drops the photos deleted from the gallery and asks for a post-processing pass, once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SegmentDetailViewModel(
    private val rideStore: RideStore,
    private val segmentId: Long,
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<SegmentDetailState>(SegmentDetailState.Loading)
    val state: StateFlow<SegmentDetailState> = _state.asStateFlow()

    val photos: StateFlow<RidePhotos> = observeRidePhotos(postStore.observePhotos(listOf(segmentId)), log)
        .stateIn(viewModelScope, SharingStarted.Eagerly, RidePhotos())

    /** The track with its times (for the heart-rate colours and chart); null until loaded. */
    private val trackPoints = MutableStateFlow<List<TrackPoint>?>(null)

    /** The segment's heart rate as stored (null while nothing is). */
    val heartRate: StateFlow<SegmentExtras?> = postStore.observeExtras(listOf(segmentId))
        .map { it.firstOrNull() }
        .catch { e ->
            log.warn("Could not read the heart rate of segment $segmentId", e)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The stored samples (empty unless READY), read again whenever a read stores new ones. */
    private val samples: StateFlow<List<HeartRateSample>> = heartRate
        .map { extras -> extras?.takeIf { it.hrStatus == HrStatus.READY }?.let { it.hrLastTriedAt ?: 0L } }
        .distinctUntilChanged()
        .mapLatest { readyAt -> if (readyAt == null) emptyList() else readSamples() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The heart-rate chart over the same distance axis as the speed and altitude charts (ruling P1). */
    val heartRateChart: StateFlow<List<ChartPoint>> = combine(trackPoints, samples) { points, samples ->
        if (points == null || samples.isEmpty()) emptyList() else HeartRateCharts.bpm(points, samples)
    }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** [심박] is offered only with detailed heart rate (spec §3.3). */
    val canColorByHeartRate: StateFlow<Boolean> = heartRate
        .map { it?.hrStatus == HrStatus.READY && it.hrDetailed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _colorMode = MutableStateFlow(TrackColorMode.DEFAULT)

    /** [기본] first, every time the screen opens (not remembered). */
    val colorMode: StateFlow<TrackColorMode> = _colorMode.asStateFlow()

    /** The track's colours, one per track point, in [TrackColorMode.HEART_RATE] with detailed heart rate; else null. */
    val trackColors: StateFlow<List<String>?> =
        combine(trackPoints, samples, colorMode, canColorByHeartRate) { points, samples, mode, can ->
            if (points == null || mode != TrackColorMode.HEART_RATE || !can) {
                null
            } else {
                HeartRateColor.bands(points, samples).map { it.colorHex }
            }
        }
            .flowOn(compute)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { _state.value = load() }
        viewModelScope.launch { postProcessor.pruneDeletedPhotos(listOf(segmentId)) }
        postProcessor.request()
    }

    fun setColorMode(mode: TrackColorMode) {
        _colorMode.value = mode
    }

    /** [다시 가져오기]: reads the heart rate again now (spec §3.2). */
    fun refetchHeartRate() {
        viewModelScope.launch { postProcessor.refetchHeartRate(segmentId) }
    }

    /** [숨기기] ([hidden] = true) / [다시 보이기] in the full-screen view. */
    fun setHidden(photo: RidePhoto, hidden: Boolean) {
        viewModelScope.launch { setPhotoHidden(postStore, photo, hidden, log) }
    }

    private suspend fun load(): SegmentDetailState = try {
        val segment = rideStore.segment(segmentId)
        if (segment == null) {
            SegmentDetailState.Missing
        } else {
            val points = rideStore.points(segmentId)
            trackPoints.value = points
            withContext(compute) {
                SegmentDetailState.Loaded(
                    segment = segment,
                    track = points.map { it.point },
                    altitude = RideCharts.altitude(points),
                    speed = RideCharts.speedKmh(points),
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read segment $segmentId", e)
        SegmentDetailState.Missing
    }

    private suspend fun readSamples(): List<HeartRateSample> = try {
        postStore.samples(segmentId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read the heart-rate samples of segment $segmentId", e)
        emptyList()
    }

    companion object {
        const val ARG_SEGMENT = "segmentId"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val segmentId = requireNotNull(createSavedStateHandle().get<Long>(ARG_SEGMENT)) { "missing $ARG_SEGMENT" }
                SegmentDetailViewModel(
                    container.rideStore, segmentId, container.postStore, container.postProcessor, androidWarnLog("SegmentDetail"),
                )
            }
        }
    }
}

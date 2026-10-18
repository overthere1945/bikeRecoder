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
import com.cowork.bikerecoder.core.recording.ChartPoint
import com.cowork.bikerecoder.core.recording.RideCharts
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.RidePhoto
import com.cowork.bikerecoder.ui.photo.RidePhotos
import com.cowork.bikerecoder.ui.photo.observeRidePhotos
import com.cowork.bikerecoder.ui.photo.setPhotoHidden
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * One segment (spec §3.5): its track for the map and the altitude/speed charts over distance, and its photos (spec
 * §2.3). Opening it drops the photos deleted from the gallery and asks for a post-processing pass, once.
 */
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

    init {
        viewModelScope.launch { _state.value = load() }
        viewModelScope.launch { postProcessor.pruneDeletedPhotos(listOf(segmentId)) }
        postProcessor.request()
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

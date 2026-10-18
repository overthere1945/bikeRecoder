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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** One segment (spec §3.5): its track for the map and the altitude/speed charts over distance. */
class SegmentDetailViewModel(
    private val rideStore: RideStore,
    private val segmentId: Long,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<SegmentDetailState>(SegmentDetailState.Loading)
    val state: StateFlow<SegmentDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = load() }
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
                SegmentDetailViewModel(container.rideStore, segmentId, androidWarnLog("SegmentDetail"))
            }
        }
    }
}

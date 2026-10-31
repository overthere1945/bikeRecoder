package com.cowork.bikerecoder.ui.elevation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.map.pathColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ElevationMapUiState {
    data object Loading : ElevationMapUiState

    /**
     * The [profile] with the line it runs along: one path per segment (a trip of several days has several), each with
     * its band colours ([pathColors], FLAT the table's blue). [scrubM]: where the chart was last scrubbed, null before.
     */
    data class Ready(
        val profile: ElevationProfile,
        val paths: List<List<GeoPoint>>,
        val colors: List<List<String>>,
        val scrubM: Double? = null,
    ) : ElevationMapUiState

    /** Nothing to show (the plan's handoff was already taken, no profile, or it could not be read): the screen goes back. */
    data object Closed : ElevationMapUiState
}

/**
 * The "고도" map screen (V1.7.0 spec §2.2): a trip's segments (in start order, empty ones left out) or one segment read
 * from [rideStore], or the plan preview's route taken from [handoff]; the profile and colours are computed on [compute].
 */
class ElevationMapViewModel(
    private val source: ElevationSource,
    private val rideStore: RideStore,
    private val handoff: ElevationHandoff,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ElevationMapUiState>(ElevationMapUiState.Loading)
    val uiState: StateFlow<ElevationMapUiState> = _uiState.asStateFlow()

    init {
        // Taken at once (not in the coroutine): a second screen must not find it again.
        val handedOver = if (source == ElevationSource.Plan) handoff.take() else null
        viewModelScope.launch { _uiState.value = load(handedOver) }
    }

    /** The chart was scrubbed to [distanceM] (held within the profile). */
    fun scrub(distanceM: Double) {
        _uiState.update { state ->
            if (state is ElevationMapUiState.Ready) state.copy(scrubM = distanceM.coerceIn(0.0, state.profile.totalM)) else state
        }
    }

    private suspend fun load(handedOver: Pair<ElevationProfile, List<GeoPoint>>?): ElevationMapUiState = try {
        when (source) {
            ElevationSource.Plan -> handedOver?.let { (profile, path) ->
                withContext(compute) { ready(profile, listOf(path)) }
            } ?: ElevationMapUiState.Closed
            is ElevationSource.Trip -> {
                val segments = rideStore.segments(source.tripId).filterNot { it.isEmpty }.sortedBy { it.startedAt }
                fromTracks(segments.map { rideStore.points(it.id) })
            }
            is ElevationSource.Segment -> fromTracks(listOf(rideStore.points(source.segmentId)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read the elevation of $source", e)
        ElevationMapUiState.Closed
    }

    private suspend fun fromTracks(tracks: List<List<TrackPoint>>): ElevationMapUiState = withContext(compute) {
        val kept = tracks.filter { it.isNotEmpty() }
        val profile = ElevationProfile.fromSegments(kept)
        if (profile == null) ElevationMapUiState.Closed else ready(profile, kept.map { track -> track.map { it.point } })
    }

    private fun ready(profile: ElevationProfile, paths: List<List<GeoPoint>>) =
        ElevationMapUiState.Ready(profile, paths, pathColors(paths, profile))

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
        const val KIND_TRIP = "trip"
        const val KIND_SEGMENT = "segment"
        const val KIND_PLAN = "plan"

        /** [source] as the route's kind and id (plan: id 0). */
        fun kindAndId(source: ElevationSource): Pair<String, Long> = when (source) {
            is ElevationSource.Trip -> KIND_TRIP to source.tripId
            is ElevationSource.Segment -> KIND_SEGMENT to source.segmentId
            ElevationSource.Plan -> KIND_PLAN to 0L
        }

        /** The source a route's kind and id name; null for an unknown kind. */
        fun sourceOf(kind: String?, id: Long): ElevationSource? = when (kind) {
            KIND_TRIP -> ElevationSource.Trip(id)
            KIND_SEGMENT -> ElevationSource.Segment(id)
            KIND_PLAN -> ElevationSource.Plan
            else -> null
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val handle = createSavedStateHandle()
                val id = requireNotNull(handle.get<Long>(ARG_ID)) { "missing $ARG_ID" }
                val source = requireNotNull(sourceOf(handle.get<String>(ARG_KIND), id)) { "unknown $ARG_KIND" }
                ElevationMapViewModel(source, container.rideStore, container.elevationHandoff, androidWarnLog("ElevationMap"))
            }
        }
    }
}

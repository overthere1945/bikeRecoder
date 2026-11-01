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
import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.series.MetricSeries
import com.cowork.bikerecoder.map.pathColors
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.ui.history.TripHeartRate
import com.cowork.bikerecoder.ui.history.tripHeartRate
import com.cowork.bikerecoder.ui.history.tripTotals
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
     * What the screen shows (V1.8.0 spec §2.4): the elevation [profile] and the [speed] and [heartRate] series, each null
     * when there is none, giving the [tabs] (in that order, only those with data; the plan has only elevation) of which
     * [tab] is shown. The line it all runs along: one path per segment (a trip of several days has several) with the
     * current tab's colours per point ([colors]: grade bands with FLAT the table's blue, speed or heart-rate bands with
     * grey where there is no value). [scrubM]: where the chart was last scrubbed, null before; kept across tabs, as is
     * [marker], the spot on the line there. [speedDescription] and [heartDescription]: what those charts read out.
     */
    data class Ready(
        val profile: ElevationProfile?,
        val speed: MetricSeries?,
        val heartRate: MetricSeries?,
        val tabs: List<ChartTab>,
        val tab: ChartTab,
        val paths: List<List<GeoPoint>>,
        val colors: List<List<String>>,
        val scrubM: Double? = null,
        val marker: GeoPoint? = null,
        val speedDescription: String = "",
        val heartDescription: String = "",
    ) : ElevationMapUiState

    /** Nothing to show (the plan's handoff was already taken, no data, or it could not be read): the screen goes back. */
    data object Closed : ElevationMapUiState
}

/**
 * The big map screen (V1.7.0 spec §2.2, V1.8.0 spec §2.4) opened on [requestedTab] (or the first tab there is): a
 * trip's segments (in start order, empty ones left out) or one segment read from [rideStore] with their heart-rate
 * samples and stored heart rate from [postStore], or the plan preview's route taken from [handoff]; the profile, series
 * and every tab's colours are computed on [compute]. Heart rate that cannot be read is logged and left out.
 */
class ElevationMapViewModel(
    private val source: ElevationSource,
    private val rideStore: RideStore,
    private val postStore: PostStore,
    private val handoff: ElevationHandoff,
    private val requestedTab: ChartTab = ChartTab.ELEVATION,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ElevationMapUiState>(ElevationMapUiState.Loading)
    val uiState: StateFlow<ElevationMapUiState> = _uiState.asStateFlow()

    /** Every tab's line colours and where a distance is on the line, set with the first [ElevationMapUiState.Ready]. */
    private var tabColors: Map<ChartTab, List<List<String>>> = emptyMap()
    private var pointAt: (Double) -> GeoPoint = { error("not loaded") }

    init {
        // Taken at once (not in the coroutine): a second screen must not find it again.
        val handedOver = if (source == ElevationSource.Plan) handoff.take() else null
        viewModelScope.launch {
            val loaded = load(handedOver)
            if (loaded != null) {
                tabColors = loaded.colors
                pointAt = loaded.pointAt
            }
            _uiState.value = loaded?.state ?: ElevationMapUiState.Closed
        }
    }

    /** The chart was scrubbed to [distanceM] (held within the line). */
    fun scrub(distanceM: Double) {
        _uiState.update { state ->
            if (state is ElevationMapUiState.Ready) {
                val d = distanceM.coerceIn(0.0, state.totalM())
                state.copy(scrubM = d, marker = pointAt(d))
            } else {
                state
            }
        }
    }

    /** Shows [tab] (its chart, legend and line colours) if it is one of the tabs; the scrub stays where it is. */
    fun selectTab(tab: ChartTab) {
        _uiState.update { state ->
            if (state is ElevationMapUiState.Ready && tab in state.tabs) {
                state.copy(tab = tab, colors = tabColors.getValue(tab))
            } else {
                state
            }
        }
    }

    private class Loaded(
        val state: ElevationMapUiState.Ready,
        val colors: Map<ChartTab, List<List<String>>>,
        val pointAt: (Double) -> GeoPoint,
    )

    private suspend fun load(handedOver: Pair<ElevationProfile, List<GeoPoint>>?): Loaded? = try {
        when (source) {
            ElevationSource.Plan -> handedOver?.let { (profile, path) ->
                withContext(compute) { plan(profile, path) }
            }
            is ElevationSource.Trip -> {
                val segments = rideStore.segments(source.tripId).filterNot { it.isEmpty }.sortedBy { it.startedAt }
                val tracks = segments.map { rideStore.points(it.id) }
                fromTracks(tracks, heartInput(segments.map { it.id }, segments), tripTotals(segments))
            }
            is ElevationSource.Segment -> {
                val segment = rideStore.segment(source.segmentId)
                val track = rideStore.points(source.segmentId)
                val heart = heartInput(listOf(source.segmentId), listOfNotNull(segment))
                fromTracks(listOf(track), heart, segment?.totals ?: SegmentTotals.ZERO)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read the elevation of $source", e)
        null
    }

    /** [HeartInput] of [segmentIds] ([segments] their records); null (logged) when it cannot be read. */
    private suspend fun heartInput(segmentIds: List<Long>, segments: List<RideSegment>): HeartInput? = try {
        val samples = segmentIds.flatMap { postStore.samples(it) }
        val extras = segmentIds.mapNotNull { postStore.extras(it) }
        HeartInput(samples, tripHeartRate(segments.groupBy { it.localDate }, extras))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read the heart rate of $source", e)
        null
    }

    private fun plan(profile: ElevationProfile, path: List<GeoPoint>): Loaded {
        val paths = listOf(path)
        val colors = pathColors(paths, profile)
        val state = ElevationMapUiState.Ready(
            profile, speed = null, heartRate = null, tabs = listOf(ChartTab.ELEVATION), tab = ChartTab.ELEVATION,
            paths = paths, colors = colors,
        )
        return Loaded(state, mapOf(ChartTab.ELEVATION to colors), profile::pointAt)
    }

    /** Null when no tab has anything to draw. */
    private suspend fun fromTracks(
        tracks: List<List<TrackPoint>>,
        heart: HeartInput?,
        totals: SegmentTotals,
    ): Loaded? = withContext(compute) {
        val kept = tracks.filter { it.isNotEmpty() }
        val axis = TrackAxis.of(kept) ?: return@withContext null
        val profile = ElevationProfile.fromSegments(kept)
        val speed = MetricSeries.speed(axis, kept)
        val heartRate = heart?.let { MetricSeries.heartRate(axis, kept, it.samples) }
        val colors = buildMap {
            profile?.let { put(ChartTab.ELEVATION, pathColors(axis.paths, it)) }
            speed?.let { put(ChartTab.SPEED, pathColors(axis.paths, it::colorHexAt, axis)) }
            heartRate?.let { put(ChartTab.HEART_RATE, pathColors(axis.paths, it::colorHexAt, axis)) }
        }
        val tabs = ChartTab.entries.filter { it in colors }
        if (tabs.isEmpty()) return@withContext null
        val shown = requestedTab.takeIf { it in tabs } ?: tabs.first()
        val state = ElevationMapUiState.Ready(
            profile = profile,
            speed = speed,
            heartRate = heartRate,
            tabs = tabs,
            tab = shown,
            paths = axis.paths,
            colors = colors.getValue(shown),
            // Read out with the stored values (V1.8.0 spec §2.1).
            speedDescription = ChartText.speedDescription(totals.avgSpeedMps * MPS_TO_KMH, totals.maxSpeedMps * MPS_TO_KMH),
            heartDescription = if (heart != null && heartRate != null) heartDescription(heart) else "",
        )
        Loaded(state, colors, axis::pointAt)
    }

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
        const val ARG_TAB = "tab"
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
                val tab = ChartTab.of(handle.get<String>(ARG_TAB))
                ElevationMapViewModel(
                    source, container.rideStore, container.postStore, container.elevationHandoff, tab,
                    androidWarnLog("ElevationMap"),
                )
            }
        }
    }
}

/** The length of the line: the profile's, or the series' (the same track axis). */
private fun ElevationMapUiState.Ready.totalM(): Double = profile?.totalM ?: speed?.totalM ?: heartRate?.totalM ?: 0.0

private const val MPS_TO_KMH = 3.6

/** Segments' heart-rate samples one after another and what is stored of their heart rate. */
private class HeartInput(val samples: List<HeartRateSample>, val stored: TripHeartRate)

/**
 * The heart-rate chart's read-out: the stored average (weighted by moving time over a trip's segments) and highest
 * ([tripHeartRate]); without either, the samples' mean and highest.
 */
private fun heartDescription(heart: HeartInput): String {
    val avg = heart.stored.avgBpm
    val max = heart.stored.maxBpm
    if (avg != null && max != null) return ChartText.heartDescription(avg, max)
    val sampled = HeartRateSummary.of(heart.samples, movingTimeMs = 0L) ?: return ""
    return ChartText.heartDescription(sampled.avgBpm, sampled.maxBpm)
}

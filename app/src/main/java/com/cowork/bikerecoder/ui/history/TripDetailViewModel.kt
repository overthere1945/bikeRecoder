package com.cowork.bikerecoder.ui.history

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.activity.ActivityMix
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.export.ExportFormat
import com.cowork.bikerecoder.export.ExportResult
import com.cowork.bikerecoder.export.ExportRunner
import com.cowork.bikerecoder.export.ExportState
import com.cowork.bikerecoder.export.TripExporter
import com.cowork.bikerecoder.feed.TripRegions
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HeartRateStats
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.series.MetricSeries
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.core.video.RideTitle
import com.cowork.bikerecoder.map.MapLine
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.RidePhoto
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.elevation.ChartText
import com.cowork.bikerecoder.ui.elevation.SpeedChartStyle
import com.cowork.bikerecoder.ui.health.TrackColorMode
import com.cowork.bikerecoder.ui.photo.RidePhotos
import com.cowork.bikerecoder.ui.photo.observeRidePhotos
import com.cowork.bikerecoder.ui.photo.setPhotoHidden
import com.cowork.bikerecoder.ui.share.canShare
import com.cowork.bikerecoder.ui.video.canMakeMedia
import com.cowork.bikerecoder.ui.video.missingMedia
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripMediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** The track axis of a trip's non-empty segments in start order, their points ([tracks]), ids and activities, in that order. */
private class ChartInput(
    val axis: TrackAxis,
    val tracks: List<List<TrackPoint>>,
    val segmentIds: List<Long>,
    val activities: List<ActivityType>,
)

/** A heart-rate [series] (null: nothing to draw) and the [samples] it was made from (the read-out falls back to them). */
private class HeartChart(val series: MetricSeries?, val samples: List<HeartRateSample>)

/**
 * One trip's segments by date (oldest first) and the trip's totals (spec §3.5), without empty segments
 * ([RideSegment.isEmpty]). An open segment (still being recorded,
 * endedAt == null) cannot be deleted; a closed one is deleted after confirmation.
 * The map shows each listed segment's track as its own line, in order ([lines], ruling R10), coloured in
 * [TrackColorMode.HEART_RATE] by that segment's own samples, and the photos of every day ([photos], spec §2.3).
 * [heartRate]: the trip's time-weighted average, highest heart rate and calories, and each day's average (spec §3.3).
 * The first load drops the photos deleted from the gallery and asks for a post-processing pass, once per screen.
 *
 * V1.3.0 (spec §1.1, §5.1): [canMakeMedia] — a completed trip with a finished ride of 10 m or more offers [영상 만들기]
 * [이미지 만들기]; [media] — the videos and images made from it ("만든 영상·이미지"), newest first. Each load (opening the
 * screen, coming back to it) drops the made files the gallery no longer has: only when [mediaExists] says false, never
 * when the check fails (ruling V6).
 *
 * V1.7.0 (spec §1.5, §2.3): [total] carries the trip's descent (the segments' sum) and highest altitude (their maximum, null
 * when none knows it); [profile] is the elevation profile of the segments' points in start order (null without usable
 * elevation), computed on [compute] and read again with the tracks.
 *
 * V1.7.0 (spec §4.1, §4.2): [export] saves the trip as GPX or KML under the shown [title] through [exportTrip];
 * [exportState] is where that stands, and [recording] (some segment is still open) dims the export menu.
 *
 * V1.8.0 (spec §2.2): [speedSeries] and [heartSeries] lie on the same track axis of the segments in start order. The axis
 * and [speedSeries] are computed with [profile] on [compute] (and again when it is); [heartSeries] from each segment's
 * stored samples, read again whenever a READY segment's samples are stored (as [lines]), so heart rate that arrives after
 * the screen opened is charted too; left out (logged) when they cannot be read. Only segments with detailed heart rate
 * ([SegmentExtras.detailedHeartRate]) are charted and coloured on the map; the others have no value there. [heartDescription]
 * reads the heart-rate chart out with the stored figures ([heartRate]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModel(
    private val rideStore: RideStore,
    private val tripId: Long,
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val log: WarnLog = WarnLog { _, _ -> },
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val tripStore: TripStore? = null,
    private val tripMedia: TripMediaStore? = null,
    /** Whether a made file is still in the gallery; throws when that cannot be told. */
    private val mediaExists: suspend (uri: String) -> Boolean = { true },
    /** The trip's custom title and stored region (V1.4.0 spec §2.5); without it the screen has no title to change. */
    private val tripLabels: TripLabelStore? = null,
    /** Looks up the region once the first point is known; without it none is looked up. */
    private val tripRegions: TripRegions? = null,
    private val zone: ZoneId = SEOUL,
    /** The imported trips, id to file name (V1.6.0 spec §4.2). */
    imported: Flow<Map<Long, String>> = flowOf(emptyMap()),
    /** [com.cowork.bikerecoder.export.TripExporter.exportTrip] (V1.7.0 spec §4); without it the screen offers no export. */
    private val exportTrip: (suspend (tripId: Long, format: ExportFormat, title: String) -> ExportResult)? = null,
    /** Builds the elevation profile from the segments' points in start order; a seam for tests. */
    private val profileOf: (List<List<TrackPoint>>) -> ElevationProfile? = ElevationProfile.Companion::fromSegments,
    /** Builds the speed series on the axis of the segments' points and activities in start order; a seam for tests. */
    private val speedOf: (TrackAxis, List<List<TrackPoint>>, List<ActivityType>) -> MetricSeries? = MetricSeries.Companion::speed,
) : ViewModel() {

    private val _segmentsByDate = MutableStateFlow<Map<LocalDate, List<RideSegment>>>(emptyMap())
    val segmentsByDate: StateFlow<Map<LocalDate, List<RideSegment>>> = _segmentsByDate.asStateFlow()

    private val _total = MutableStateFlow(SegmentTotals.ZERO)
    val total: StateFlow<SegmentTotals> = _total.asStateFlow()

    private val _profile = MutableStateFlow<ElevationProfile?>(null)

    /** The trip's elevation profile (spec §2.3); null while it is computed and when the trip has no usable elevation. */
    val profile: StateFlow<ElevationProfile?> = _profile.asStateFlow()

    private val _profileLoaded = MutableStateFlow(false)

    /**
     * True once [profile] and [speedSeries] are computed (null then means "no data"), so the screen does not flash
     * "고도 정보가 없습니다" or "속도 정보가 없습니다". [heartSeries] follows on its own (no chart while it is null).
     */
    val profileLoaded: StateFlow<Boolean> = _profileLoaded.asStateFlow()

    private val _speedSeries = MutableStateFlow<MetricSeries?>(null)

    /** The trip's speed chart (V1.8.0 spec §2.2); null while it is computed, without speed, or when computing it failed. */
    val speedSeries: StateFlow<MetricSeries?> = _speedSeries.asStateFlow()

    private val _speedStyle = MutableStateFlow(SpeedChartStyle.BIKE)

    /**
     * How the speed chart is named and read (V1.9.0 spec §3.5, plan P7): 페이스 only when every segment on the chart is a
     * run or every one a walk; a trip of several activities shows 속도 in km/h, each segment in its own activity's colours.
     */
    val speedStyle: StateFlow<SpeedChartStyle> = _speedStyle.asStateFlow()

    /** What the heart-rate series is built on, set with [speedSeries]; null until then or when there is no axis. */
    private val chartInput = MutableStateFlow<ChartInput?>(null)

    /**
     * The rows of the totals table (V1.9.0 spec §3.2) as (label, value) cells: the rows of the activity every listed
     * segment has, a bike's when they differ.
     */
    val totalRows: StateFlow<List<List<Pair<String, String>>>> = combine(_segmentsByDate, _total) { byDate, total ->
        val activities = byDate.values.flatten().map { it.activity }.distinct()
        ActivityText.tripTotalRows(activities.singleOrNull() ?: ActivityType.BIKE, total)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ActivityText.tripTotalRows(ActivityType.BIKE, SegmentTotals.ZERO))

    private val _multiDay = MutableStateFlow<Boolean?>(null)

    /**
     * The trip spans several days (the screen's title is then "여행 기록", else "활동 기록"; V1.9.1 §C). Null until the trip
     * is read, so the title does not flash "활동 기록" before "여행 기록".
     */
    val multiDay: StateFlow<Boolean?> = _multiDay.asStateFlow()

    private val _canMakeMedia = MutableStateFlow(false)

    /** [영상 만들기] [이미지 만들기] are offered. */
    val canMakeMedia: StateFlow<Boolean> = _canMakeMedia.asStateFlow()

    private val _recording = MutableStateFlow(false)

    /** Some segment of the trip, listed or not, is still being recorded: it cannot be exported yet. */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val exporter = ExportRunner(viewModelScope, log)

    /** The export started from the [⋮] menu. */
    val exportState: StateFlow<ExportState> = exporter.state

    /** The [⋮] menu is offered. */
    val canExport: Boolean get() = exportTrip != null

    private val _canShare = MutableStateFlow(false)

    /** [공유] is offered (V1.5.0 spec §1.1): a finished ride of 10 m or more, also while the trip goes on. */
    val canShare: StateFlow<Boolean> = _canShare.asStateFlow()

    /** "만든 영상·이미지", newest first. */
    val media: StateFlow<List<TripMedia>> = (tripMedia?.observe(tripId) ?: emptyFlow())
        .catch { e ->
            log.warn("Could not read the made files of trip $tripId", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val label: StateFlow<TripLabel> = (tripLabels?.observeLabels() ?: emptyFlow())
        .map { it[tripId] ?: TripLabel() }
        .catch { e ->
            log.warn("Could not read the title of trip $tripId", e)
            emit(TripLabel())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TripLabel())

    /** The file the trip was imported from ("Relive_0514.gpx에서 가져옴", V1.6.0 spec §4.2); null for a trip recorded here. */
    val importedFrom: StateFlow<String?> = imported
        .map { it[tripId] }
        .catch { e ->
            log.warn("Could not read whether trip $tripId was imported", e)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The trip's own title, null while it has none. */
    val customTitle: StateFlow<String?> = label.map { it.customTitle?.takeIf { t -> t.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The title shown (spec §2.5): the custom title, else "저녁 라이딩 · 서울 강서구" (the region once it is known); null
     * while there is neither a custom title nor a finished ride to name it after.
     */
    val title: StateFlow<String?> = combine(label, _segmentsByDate) { label, byDate ->
        val rides = targetRides(byDate)
        if (rides.isEmpty()) label.customTitle?.takeIf { it.isNotBlank() } else shownTitle(label, rides)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The automatic title ("저녁 라이딩 · 서울 강서구") whether or not there is a custom title: what the title becomes when
     * the custom one is cleared, and the hint in "제목 바꾸기". Null while there is no finished ride to name it after.
     */
    val autoTitle: StateFlow<String?> = combine(label, _segmentsByDate) { label, byDate ->
        targetRides(byDate).takeIf { it.isNotEmpty() }?.let { shownTitle(label.copy(customTitle = null), it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun targetRides(byDate: Map<LocalDate, List<RideSegment>>) =
        byDate.values.flatten().filter { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }

    private fun shownTitle(label: TripLabel, rides: List<RideSegment>) =
        RideTitle.shown(
            label, rides.minOf { it.startedAt }, rides.map { it.localDate }.distinct().size, zone, ActivityMix.of(rides).dominant,
        )

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

    /** The segments with detailed heart rate ([SegmentExtras.detailedHeartRate]): only they are charted and coloured. */
    private val detailedIds = extras
        .map { list -> list.filter { it.detailedHeartRate }.mapTo(HashSet()) { it.segmentId } }
        .distinctUntilChanged()

    /**
     * The heart-rate series on [chartInput]'s axis and the samples it was made from, read again for a new axis and whenever
     * [readyAt] or [detailedIds] changes; null without an axis or when the samples cannot be read or charted (logged).
     */
    private val heartChart: StateFlow<HeartChart?> = combine(chartInput, readyAt, detailedIds) { input, _, detailed ->
        input?.let { it to detailed }
    }
        .mapLatest { pair ->
            if (pair == null) {
                null
            } else {
                val (input, detailed) = pair
                guarded("Could not chart the heart rate of trip $tripId") {
                    val samples = input.segmentIds.flatMap { postStore.samples(it) }
                    val mask = input.segmentIds.map { it in detailed }
                    HeartChart(withContext(compute) { MetricSeries.heartRate(input.axis, input.tracks, samples, mask) }, samples)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The trip's heart-rate chart; null while it is computed and when there is nothing to draw (no chart then). */
    val heartSeries: StateFlow<MetricSeries?> = heartChart.map { it?.series }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "심박 그래프, 평균 …bpm, 최고 …bpm" with [heartRate]'s stored figures; without them, the samples' (V1.8.0 spec §2.1). */
    val heartDescription: StateFlow<String> = combine(heartRate, heartChart) { hr, chart ->
        ChartText.heartDescription(hr.avgBpm, hr.maxBpm, chart?.samples.orEmpty())
    }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * One line per listed segment; in [TrackColorMode.HEART_RATE] each coloured by its own samples — only while [심박]
     * is offered ([TripHeartRate.canColor]), so lines are never coloured without the toggle and the key. A segment without
     * detailed heart rate ([detailedIds]) is grey there (no sample matched), as on the chart and the big map.
     */
    val lines: StateFlow<List<MapLine>> = combine(
        tracks,
        colorMode,
        readyAt,
        heartRate.map { it.canColor }.distinctUntilChanged(),
        detailedIds,
    ) { tracks, mode, _, canColor, detailed -> Triple(tracks, if (canColor) mode else TrackColorMode.DEFAULT, detailed) }
        .mapLatest { (tracks, mode, detailed) ->
            if (mode == TrackColorMode.DEFAULT) {
                withContext(compute) { tracks.map { (_, points) -> MapLine(points.map { it.point }) } }
            } else {
                val samples = tracks.map { (id, _) ->
                    if (id in detailed) guarded("Could not read the samples of segment $id") { postStore.samples(id) } else null
                }
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
    private var regionAsked = false

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

    /**
     * [저장] in "제목 바꾸기": [RideTitle.cleanCustom] of [input] is the new custom title; empty clears it, back to the
     * automatic title.
     */
    fun rename(input: String) {
        val store = tripLabels ?: return
        val cleaned = RideTitle.cleanCustom(input)
        viewModelScope.launch { guarded("Could not save the title of trip $tripId") { store.setCustomTitle(tripId, cleaned) } }
    }

    /**
     * "GPX로 내보내기" / "KML로 내보내기": the trip under its shown title; a trip without one yet (no finished ride of 10 m)
     * under [fallbackTitle].
     */
    fun export(format: ExportFormat) {
        val run = exportTrip ?: return
        val shown = title.value ?: fallbackTitle()
        exporter.run { run(tripId, format, shown) }
    }

    /**
     * The title rule (spec §2.5) over every listed segment, open or short ones too ("저녁 라이딩"); "bikeRecoder" when
     * none is listed.
     */
    private fun fallbackTitle(): String {
        val listed = _segmentsByDate.value.values.flatten()
        if (listed.isEmpty()) return label.value.customTitle?.takeIf { it.isNotBlank() } ?: TripExporter.TRIP_PREFIX
        return RideTitle.shown(
            label.value, listed.minOf { it.startedAt }, listed.map { it.localDate }.distinct().size, zone, ActivityMix.of(listed).dominant,
        )
    }

    /** The export's outcome has been shown (dialog closed, Toast posted). */
    fun clearExport() = exporter.clear()

    /** [숨기기] ([hidden] = true) / [다시 보이기] in the full-screen view. */
    fun setHidden(photo: RidePhoto, hidden: Boolean) {
        viewModelScope.launch { setPhotoHidden(postStore, photo, hidden, log) }
    }

    /** A segment already gone counts as closed (deleting it again is harmless). */
    private suspend fun isOpen(segmentId: Long): Boolean = rideStore.segment(segmentId)?.let { it.endedAt == null } ?: false

    private suspend fun load() {
        guarded("Could not read the segments of trip $tripId") {
            // The trip is read first: the screen's title waits for its type (a trip without a store is a single day).
            val trip = tripStore?.let { trips -> guarded("Could not read trip $tripId") { trips.trip(tripId) } }
            _multiDay.value = trip?.type == TripType.MULTI_DAY
            // Empty segments are kept in the store but not listed (nor counted in the totals).
            val all = rideStore.segments(tripId)
            val segments = all.filterNot { it.isEmpty }
            _canShare.value = canShare(all)
            _recording.value = all.any { it.endedAt == null }
            if (tripStore != null) _canMakeMedia.value = canMakeMedia(trip, all)
            pruneMedia()
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
                val read = ordered.map { it.id to rideStore.points(it.id) }
                tracks.value = read
                trackOf = segments
                val startOrder = ordered.indices.sortedBy { ordered[it].startedAt }.filter { read[it].second.isNotEmpty() }
                val byStart = startOrder.map { read[it].second }
                // A computation that throws (an Exception; [guarded] does not catch errors such as running out of
                // memory) must still end the loading state: no chart, no retry loop. Each chart fails on its own.
                _profile.value = guarded("Could not compute the elevation profile of trip $tripId") {
                    withContext(compute) { profileOf(byStart) }
                }
                val input = guarded("Could not build the track axis of trip $tripId") {
                    withContext(compute) {
                        TrackAxis.of(byStart)?.let {
                            ChartInput(it, byStart, startOrder.map { i -> ordered[i].id }, startOrder.map { i -> ordered[i].activity })
                        }
                    }
                }
                _speedSeries.value = input?.let {
                    guarded("Could not compute the speed series of trip $tripId") {
                        withContext(compute) { speedOf(it.axis, it.tracks, it.activities) }
                    }
                }
                _speedStyle.value = SpeedChartStyle.of(input?.activities.orEmpty())
                // The heart-rate series follows ([heartChart]), also when the speed series failed; without an axis there is none.
                chartInput.value = input
                _profileLoaded.value = true
            }
            askRegion(segments)
        }
    }

    /**
     * Once per screen, the region of the first finished ride's first point ([TripRegions.ensure]: the stored name first;
     * the lookup is not waited for here, the title follows the label table).
     */
    private fun askRegion(segments: List<RideSegment>) {
        val regions = tripRegions ?: return
        if (regionAsked) return
        val first = segments.filter { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }
            .minByOrNull { it.startedAt } ?: return
        val point = tracks.value.firstOrNull { it.first == first.id }?.second?.firstOrNull()?.point ?: return
        regionAsked = true
        viewModelScope.launch { guarded("Could not look up the region of trip $tripId") { regions.ensure(tripId, point) } }
    }

    /** The made files gone from the gallery leave "만든 영상·이미지". */
    private suspend fun pruneMedia() {
        val store = tripMedia ?: return
        guarded("Could not prune the made files of trip $tripId") {
            val gone = missingMedia(store.observe(tripId).first(), mediaExists, log)
            if (gone.isNotEmpty()) store.remove(gone)
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
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val tripId = requireNotNull(createSavedStateHandle().get<Long>(ARG_TRIP)) { "missing $ARG_TRIP" }
                TripDetailViewModel(
                    container.rideStore, tripId, container.postStore, container.postProcessor, androidWarnLog("TripDetail"),
                    tripStore = container.tripStore,
                    tripMedia = container.tripMedia,
                    tripLabels = container.tripLabels,
                    tripRegions = container.tripRegions,
                    imported = container.importStore.observeImported(),
                    mediaExists = { uri -> withContext(Dispatchers.IO) { container.mediaStoreWriter.exists(uri.toUri()) } },
                    exportTrip = container.tripExporter::exportTrip,
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

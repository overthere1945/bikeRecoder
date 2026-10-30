package com.cowork.bikerecoder.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.feed.FeedThumbKey
import com.cowork.bikerecoder.core.feed.ThumbSegment
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TripDeleteCondition
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideRecords
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import com.cowork.bikerecoder.core.stats.StatsCalculator
import com.cowork.bikerecoder.core.stats.Streak
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.video.RideTitle
import com.cowork.bikerecoder.feed.FeedThumbnails
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.SegmentCard
import com.cowork.bikerecoder.ride.TripDeleter
import com.cowork.bikerecoder.ui.history.HistoryDialog
import com.cowork.bikerecoder.ui.history.MSG_ACTIVE_TRIP_NOT_DELETABLE
import com.cowork.bikerecoder.ui.history.monthHeaderText
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripMediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The strip above the feed (V1.4.0 spec §2.1): this week (Monday to today) in [distanceM] and [rides], [diffM] against last
 * week up to the same weekday (null when neither week has a ride), the weekly goal in km if there is one, the running streak.
 */
data class WeekStripState(
    val distanceM: Double,
    val rides: Int,
    val diffM: Double?,
    val weeklyGoalKm: Int?,
    val streak: Streak,
)

/** A month of cards under "YYYY년 M월 · {거리} · {n}회" (spec §6.11 of V1.2). */
data class FeedMonth(val header: String, val cards: List<FeedCardState>)

/**
 * A trip's card (V1.4.0 spec §2.3). The numbers are the sums over the trip's finished segments of [MIN_RIDE_DISTANCE_M] or
 * more; [avgBpm] is weighted by moving time (null if no segment has one). [photoMediaIds] are the first three photos not
 * hidden, in the order taken, and [morePhotos] how many more there are. [video] is the newest video made of the trip.
 * [thumbKey] names the satellite picture to draw (null when it cannot be drawn: the trip is in progress or has no such
 * segment), [thumbFile] is that picture if it exists; without it the card draws [routes] (each segment's simplified route and
 * the colour of its place in the trip; segments without a route yet are left out). [regionKnown]: the label has a region.
 */
data class FeedCardState(
    val tripId: Long,
    val title: String,
    val dateLine: String,
    val inProgress: Boolean,
    val distanceM: Double,
    val movingTimeMs: Long,
    val ascentM: Double,
    val avgSpeedMps: Double,
    val avgBpm: Int?,
    val photoMediaIds: List<Long>,
    val morePhotos: Int,
    val video: TripMedia?,
    val trophy: Boolean,
    val thumbKey: String?,
    val thumbFile: File?,
    val routes: List<Pair<List<GeoPoint>, String>>,
    val regionKnown: Boolean = true,
) {
    val videoUri: String? get() = video?.uri
}

/** The satellite pictures as the feed uses them ([FeedThumbnails] in the app). */
interface FeedPictures {
    /** The newest picture of each trip. */
    val files: Flow<Map<Long, File>>

    fun want(tripId: Long, key: String)

    fun unwant(tripId: Long)

    suspend fun delete(tripId: Long)
}

private class FeedThumbnailsPictures(private val thumbnails: FeedThumbnails) : FeedPictures {
    override val files: Flow<Map<Long, File>> get() = thumbnails.files

    override fun want(tripId: Long, key: String) = thumbnails.want(tripId, key)

    override fun unwant(tripId: Long) = thumbnails.unwant(tripId)

    override suspend fun delete(tripId: Long) = thumbnails.delete(tripId)
}

/**
 * The summary tab's feed (V1.4.0 spec §2; before, the ride history of V1.2 spec §3.5): trips with a recording, newest first.
 * A trip in progress (ACTIVE, or still being recorded) cannot be deleted; otherwise deleting asks first and then removes the
 * trip with its stops, segments and points, its offline maps (whose refs have no foreign key to the trip) and its satellite
 * picture.
 *
 * Short trips (spec §6.10: under [MIN_RIDE_DISTANCE_M] in all, or all 0 m (R21), not in progress) are not listed but
 * counted ([hiddenShortCount]); [정리] deletes them after asking. Nothing is deleted without asking, and every delete is
 * conditional in the store ([TripDeleter], ruling R20).
 *
 * The listed trips are also shown as [months] of cards: the numbers come from the finished rides of the stats flow, the routes,
 * photos and heart rate from one read of [postStore] per load (not one per trip); if the extras cannot be read the cards are
 * shown without them. The list is read again when a [postProcessor] pass ends or saves a route, and on
 * [refresh]. What changes by itself — titles ([labels]), made videos, satellite pictures, the stats for 🏆 — is followed live.
 * The screen tells which cards are on view ([onCardShown], [onCardHidden]); those are the ones whose picture is asked for and
 * whose region is looked up.
 *
 * Days and weeks are those of [zone] (Asia/Seoul as everywhere in the titles); [clock] says what "today" is.
 */
class FeedViewModel(
    private val rideStore: RideStore,
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val offlineMaps: OfflineMapController,
    private val log: WarnLog,
    rideStats: RideStatsSource,
    labels: TripLabelStore,
    weeklyGoalKm: Flow<Int?>,
    tripMedia: TripMediaStore,
    private val pictures: FeedPictures,
    /** The region of the point for the trip, stored and returned; null if none ([com.cowork.bikerecoder.feed.TripRegions.ensure]). */
    private val ensureRegion: suspend (tripId: Long, start: GeoPoint) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = SEOUL,
) : ViewModel() {

    /** Every trip delete here goes through it: conditional, then the offline maps (ruling R20). */
    private val deleter = TripDeleter(rideStore, offlineMaps, log)

    private val _trips = MutableStateFlow<List<TripRideSummary>>(emptyList())
    val trips: StateFlow<List<TripRideSummary>> = _trips.asStateFlow()

    private val loaded = MutableStateFlow(Loaded.EMPTY)

    private val _ready = MutableStateFlow(false)

    /** True once the list has been read (or failed to be) for the first time: before it, "no trips" is not yet known. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private fun todayNow(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    /** Today in [zone], read again by [refresh] (every resume), so a process that lives into a new week shows the new week. */
    private val today = MutableStateFlow(todayNow())

    private val allRides: Flow<List<RideStat>> = rideStats.observeRides().safely(emptyList(), "ride stats")

    private val feed: StateFlow<FeedData> = combine(
        loaded,
        allRides,
        labels.observeLabels().safely(emptyMap(), "trip labels"),
        tripMedia.observeVideos().safely(emptyList(), "made videos"),
        pictures.files.safely(emptyMap(), "feed pictures"),
    ) { loaded, rides, labelMap, videos, files ->
        buildFeed(loaded, rides, labelMap, videos, files)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FeedData.EMPTY)

    /** The listed trips' cards by month of their first date, newest month first; the cards by first start, newest first. */
    val months: StateFlow<List<FeedMonth>> =
        feed.map { it.months }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Spec §2.1: this week, last week up to the same weekday, the goal and the streak. */
    val weekStrip: StateFlow<WeekStripState> = combine(allRides, weeklyGoalKm.safely(null, "weekly goal"), today) { rides, goal, today ->
        weekStripOf(rides, goal, today)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WeekStripState(0.0, 0, null, null, Streak(0, includesThisWeek = false)))

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

    /** The cards on view, with the key their picture was asked for with and the trips whose region was asked for (main thread). */
    private val onView = LinkedHashSet<Long>()
    private val wantedKeys = HashMap<Long, String>()
    private val regionAsked = HashSet<Long>()

    init {
        // The first value reads the list; each pass that ends (or route it saves) reads it again, so an open list
        // shows new previews, 📷 and ♥ like the cumulative map does — spaced, so a pass saving many routes is not
        // read for each one.
        viewModelScope.launch {
            combine(postProcessor.finishedRuns, postProcessor.routesSaved) { _, _ -> }
                .conflate()
                .collect {
                    mutex.withLock { load() }
                    delay(REREAD_SPACING_MS)
                }
        }
        // A card on view whose picture key changed (its trip got or lost a ride) or that has only now been listed.
        viewModelScope.launch { feed.collect { data -> onView.toList().forEach { sync(it, data.cards[it]) } } }
    }

    /** Reads the list again (e.g. back from a trip whose segments were deleted) and takes the date again (a new week). */
    fun refresh() {
        today.value = todayNow()
        viewModelScope.launch { mutex.withLock { load() } }
    }

    /** The card of [tripId] came into view: its picture is wanted, and its region looked up if it has none yet. */
    fun onCardShown(tripId: Long) {
        if (onView.add(tripId)) sync(tripId, feed.value.cards[tripId])
    }

    /** The card of [tripId] left the view: its picture is no longer wanted. */
    fun onCardHidden(tripId: Long) {
        if (!onView.remove(tripId)) return
        wantedKeys.remove(tripId)
        pictures.unwant(tripId)
    }

    private fun sync(tripId: Long, card: FeedCardState?) {
        card ?: return
        val key = card.thumbKey
        if (key != null && wantedKeys[tripId] != key) {
            wantedKeys[tripId] = key
            pictures.want(tripId, key)
        }
        if (!card.regionKnown && regionAsked.add(tripId)) {
            viewModelScope.launch {
                val start = guarded("Could not find where trip $tripId started") { startOf(tripId) }
                if (start == null) {
                    regionAsked.remove(tripId) // nothing to ask with yet (no route saved, no point): try again later
                    return@launch
                }
                guarded("Could not look up the region of trip $tripId") { ensureRegion(tripId, start) }
            }
        }
    }

    /** The first point of the trip: that of its first saved route, else the first recorded point of its first ride. */
    private suspend fun startOf(tripId: Long): GeoPoint? {
        val facts = loaded.value.facts[tripId] ?: return null
        facts.cards.firstNotNullOfOrNull { it.route?.firstOrNull() }?.let { return it }
        val ride = feed.value.firstRide[tripId] ?: return null
        return rideStore.points(ride).firstOrNull()?.point
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
                // In one statement, only if still COMPLETED and not recording (it may have started meanwhile).
                val deleted = guarded("Could not delete trip $tripId") {
                    deleter.delete(tripId, TripDeleteCondition.NOT_RECORDING)
                }
                if (deleted == false) _dialog.value = HistoryDialog.Message(MSG_ACTIVE_TRIP_NOT_DELETABLE)
                if (deleted == true) deletePicture(tripId)
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
                    // Checked again in the deleting statement: COMPLETED, nothing open, still under 10 m.
                    val deleted = guarded("Could not delete trip $tripId") {
                        deleter.delete(tripId, TripDeleteCondition.SHORT_NOT_RECORDING)
                    }
                    if (deleted == true) deletePicture(tripId)
                }
                load()
            }
        }
    }

    fun dismiss() {
        _dialog.value = null
    }

    /** The trip is gone: so is its satellite picture (a failure is logged; the next start sweeps what is left). */
    private suspend fun deletePicture(tripId: Long) {
        guarded("Could not delete the feed picture of trip $tripId") { pictures.delete(tripId) }
    }

    /** ACTIVE, or a segment of it is still open (being recorded, e.g. guidance arrival completed the trip). */
    private suspend fun inProgress(tripId: Long): Boolean {
        val summary = rideStore.tripSummaries().find { it.trip.id == tripId }
        return summary?.trip?.status == TripStatus.ACTIVE || rideStore.openSegments().any { it.tripId == tripId }
    }

    private suspend fun load() {
        try {
            guarded("Could not read the ride history") {
                val split = split()
                val segments = guarded("Could not read the history cards' routes, photos and heart rate") {
                    postStore.segmentCards()
                }.orEmpty()
                val photos = guarded("Could not read the photos of the history cards") { shownPhotos(segments) }.orEmpty()
                val byTrip = segments.groupBy { it.tripId }
                val facts = split.shown.associate { summary ->
                    val id = summary.trip.id
                    id to TripFacts(
                        inProgress = summary.trip.status == TripStatus.ACTIVE || id in split.open,
                        hasOpenSegment = id in split.open,
                        cards = byTrip[id].orEmpty(),
                        photos = photos[id].orEmpty(),
                    )
                }
                _trips.value = split.shown
                hiddenShort = split.hidden
                _hiddenShortCount.value = split.hidden.size
                loaded.value = Loaded(split.shown, facts)
            }
        } finally {
            _ready.value = true
        }
    }

    /** The photos not hidden of each trip, in the order they were taken, each picture once ([segments]: the listed segments). */
    private suspend fun shownPhotos(segments: List<SegmentCard>): Map<Long, List<Long>> {
        val tripOf = segments.associate { it.segmentId to it.tripId }
        val withPhotos = segments.filter { it.photoCount > 0 }.map { it.segmentId }
        // SQLite on API 30 takes 999 bound variables at most.
        val photos = withPhotos.chunked(PHOTO_QUERY_CHUNK).flatMap { postStore.observePhotos(it).first() }
        return photos.filterNot { it.hidden }
            .sortedWith(compareBy({ it.takenAt }, { it.id }))
            .groupBy { tripOf[it.segmentId] }
            .mapNotNull { (tripId, list) -> tripId?.let { it to list.map { p -> p.mediaId }.distinct() } }
            .toMap()
    }

    /**
     * The listed trips and the short ones hidden: under [MIN_RIDE_DISTANCE_M] in all, not ACTIVE, nothing open — also
     * the trips whose segments are all 0 m, which are never listed (ruling R21). [HistorySplit.open]: trips with an open segment.
     */
    private suspend fun split(): HistorySplit {
        val all = rideStore.tripSummaries()
        val empty = rideStore.emptyTrips()
        val open = rideStore.openSegments().mapTo(HashSet()) { it.tripId }
        fun finished(trip: Trip) = trip.status != TripStatus.ACTIVE && trip.id !in open
        val (hidden, shown) = all.partition { it.distanceM < MIN_RIDE_DISTANCE_M && finished(it.trip) }
        val hiddenEmpty = empty.filter(::finished).map { it.id }
        return HistorySplit(shown, hidden.map { it.trip.id } + hiddenEmpty, open)
    }

    private class HistorySplit(val shown: List<TripRideSummary>, val hidden: List<Long>, val open: Set<Long>)

    /** What one load read about a listed trip (its finished rides come from the stats flow, not from a read per trip). */
    private class TripFacts(
        /** ACTIVE, or a segment is open: "· 진행 중" and no delete. */
        val inProgress: Boolean,
        /** A segment of it is open (being recorded): its picture is not drawn now. */
        val hasOpenSegment: Boolean,
        /** The listed segments (with a distance) in start order. */
        val cards: List<SegmentCard>,
        val photos: List<Long>,
    )

    private class Loaded(val shown: List<TripRideSummary>, val facts: Map<Long, TripFacts>) {
        companion object {
            val EMPTY = Loaded(emptyList(), emptyMap())
        }
    }

    private class FeedData(
        val months: List<FeedMonth>,
        val cards: Map<Long, FeedCardState>,
        /** The first finished ride (of [MIN_RIDE_DISTANCE_M] or more) of each trip that has one. */
        val firstRide: Map<Long, Long>,
    ) {
        companion object {
            val EMPTY = FeedData(emptyList(), emptyMap(), emptyMap())
        }
    }

    private fun buildFeed(
        loaded: Loaded,
        rides: List<RideStat>,
        labels: Map<Long, TripLabel>,
        videos: List<TripMedia>,
        files: Map<Long, File>,
    ): FeedData {
        val trophies = RideRecords.recordHolderTrips(rides)
        val ridesOf = StatsCalculator.rides(rides).groupBy { it.tripId }
        val videoOf = videos.filter { it.kind == MediaKind.VIDEO }.groupBy { it.tripId }
            .mapValues { (_, list) -> list.maxWith(compareBy({ it.createdAt }, { it.id })) }
        val cards = loaded.shown.associate { summary ->
            val id = summary.trip.id
            val facts = loaded.facts.getValue(id)
            id to cardOf(summary, facts, ridesOf[id].orEmpty(), labels[id] ?: TripLabel(), videoOf[id], id in trophies, files[id])
        }
        /** Spec §2.2: newest first by the first segment's start (a trip with no ride yet: its latest start). */
        fun firstStart(summary: TripRideSummary) = ridesOf[summary.trip.id]?.firstOrNull()?.startedAt ?: summary.lastStartedAt
        val months = loaded.shown.groupBy { YearMonth.from(it.firstDate) }.toSortedMap(reverseOrder())
            .map { (month, trips) ->
                FeedMonth(
                    header = monthHeaderText(month.year, month.monthValue, trips.sumOf { it.distanceM }, trips.size),
                    cards = trips.sortedWith(compareByDescending<TripRideSummary> { firstStart(it) }.thenByDescending { it.trip.id })
                        .map { cards.getValue(it.trip.id) },
                )
            }
        return FeedData(months, cards, ridesOf.mapValues { (_, list) -> list.first().segmentId })
    }

    private fun cardOf(
        summary: TripRideSummary,
        facts: TripFacts,
        rides: List<RideStat>,
        label: TripLabel,
        video: TripMedia?,
        trophy: Boolean,
        file: File?,
    ): FeedCardState {
        val id = summary.trip.id
        val distance = rides.sumOf { it.distanceM }
        val moving = rides.sumOf { it.movingTimeMs }
        val days = rides.map { it.localDate }.distinct().sorted().ifEmpty { listOf(summary.firstDate) }
        val firstStartedAt = rides.firstOrNull()?.startedAt ?: summary.lastStartedAt
        // Spec §2.4: no picture while a segment of the trip is open, or when it has no ride to draw.
        val thumbKey = if (facts.hasOpenSegment || rides.isEmpty()) {
            null
        } else {
            FeedThumbKey.of(rides.map { ThumbSegment(it.segmentId, it.endedAt, it.distanceM) })
        }
        return FeedCardState(
            tripId = id,
            title = RideTitle.shown(label, firstStartedAt, days.size, zone),
            dateLine = feedDateLine(days.first(), days.last(), firstStartedAt, zone),
            inProgress = facts.inProgress,
            distanceM = distance,
            movingTimeMs = moving,
            ascentM = rides.sumOf { it.ascentM },
            avgSpeedMps = if (moving > 0) distance / (moving / 1000.0) else 0.0,
            avgBpm = HeartRateSummary.tripAverage(facts.cards.mapNotNull { s -> s.heartRate?.let { it to s.movingTimeMs } }),
            photoMediaIds = facts.photos.take(MAX_CARD_PHOTOS),
            morePhotos = (facts.photos.size - MAX_CARD_PHOTOS).coerceAtLeast(0),
            video = video,
            trophy = trophy,
            thumbKey = thumbKey,
            thumbFile = thumbKey?.let { key -> file?.takeIf { it.name == feedThumbName(id, key) } },
            routes = facts.cards.mapIndexedNotNull { index, s -> s.route?.let { it to RideColors.colorAt(index) } },
            regionKnown = !label.regionName.isNullOrBlank(),
        )
    }

    private fun weekStripOf(all: List<RideStat>, goalKm: Int?, today: LocalDate): WeekStripState {
        val rides = StatsCalculator.rides(all)
        val week = StatsCalculator.periodOf(PeriodKind.WEEK, today, rides, today)
        val comparison = checkNotNull(StatsCalculator.comparison(rides, week, today))
        val thisWeek = comparison.current
        val lastWeek = comparison.previous
        val diff = if (thisWeek.rides == 0 && lastWeek.rides == 0) null else thisWeek.distanceM - lastWeek.distanceM
        return WeekStripState(thisWeek.distanceM, thisWeek.rides, diff, goalKm, RideRecords.streak(all, today))
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

    /** This flow, but a failure of it is logged and gives [default] instead (the feed then shows without it). */
    private fun <T> Flow<T>.safely(default: T, what: String): Flow<T> = catch { e ->
        log.warn("Could not follow the $what", e)
        emit(default)
    }

    companion object {
        /** The least time between two reads after passes, as on the cumulative map. */
        const val REREAD_SPACING_MS = 500L

        /** Spec §2.3: the photo row shows three. */
        const val MAX_CARD_PHOTOS = 3

        private const val PHOTO_QUERY_CHUNK = 900

        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                // The first use of the lazy thumbnails also sweeps away the pictures of trips deleted since the last run.
                val thumbnails = container.feedThumbnails
                FeedViewModel(
                    container.rideStore,
                    container.postStore,
                    container.postProcessor,
                    container.offline,
                    androidWarnLog("Feed"),
                    rideStats = container.rideStats,
                    labels = container.tripLabels,
                    weeklyGoalKm = container.settings.settings.map { it.weeklyGoalKm }.distinctUntilChanged(),
                    tripMedia = container.tripMedia,
                    pictures = FeedThumbnailsPictures(thumbnails),
                    ensureRegion = container.tripRegions::ensure,
                )
            }
        }
    }
}

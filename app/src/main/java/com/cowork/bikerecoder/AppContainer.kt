package com.cowork.bikerecoder

import android.content.Context
import android.util.Log
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.importing.ImportStore
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.stats.RideStatsSource
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.video.RideTitle
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.DataStoreDayDistances
import com.cowork.bikerecoder.data.DataStoreRecentPlaces
import com.cowork.bikerecoder.data.DayDistances
import com.cowork.bikerecoder.data.RoomFavoriteStore
import com.cowork.bikerecoder.data.RoomImportStore
import com.cowork.bikerecoder.importing.ContentImportReader
import com.cowork.bikerecoder.importing.GpxImportJob
import com.cowork.bikerecoder.importing.ImportReader
import com.cowork.bikerecoder.data.RoomPostStore
import com.cowork.bikerecoder.data.RoomRideStatsSource
import com.cowork.bikerecoder.data.RoomRideStore
import com.cowork.bikerecoder.data.RoomTripLabelStore
import com.cowork.bikerecoder.data.RoomTripMediaStore
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.favorite.FavoriteRepository
import com.cowork.bikerecoder.feed.FeedThumbnails
import com.cowork.bikerecoder.feed.TripRegions
import com.cowork.bikerecoder.location.BarometerSource
import com.cowork.bikerecoder.location.FusedLocationSource
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.location.PressureSource
import com.cowork.bikerecoder.location.SharedLocationSource
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.nav.GuidanceServiceStatus
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.offline.MapLibreOfflineController
import com.cowork.bikerecoder.offline.NetworkWaiter
import com.cowork.bikerecoder.offline.SegmentRepository
import com.cowork.bikerecoder.post.HealthConnectHeartRateSource
import com.cowork.bikerecoder.post.HeartRateSource
import com.cowork.bikerecoder.post.MediaStorePhotoSource
import com.cowork.bikerecoder.post.PhotoSource
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.ShortRides
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.routing.BRouterRouter
import com.cowork.bikerecoder.routing.ProfileInstaller
import com.cowork.bikerecoder.search.KakaoLocalClient
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.RecentPlaceStore
import com.cowork.bikerecoder.share.ShareCandidates
import com.cowork.bikerecoder.tts.AndroidTtsVoiceOutput
import com.cowork.bikerecoder.tts.VoiceOutput
import com.cowork.bikerecoder.video.AndroidVideoBackend
import com.cowork.bikerecoder.video.FeedThumbnailRenderer
import com.cowork.bikerecoder.video.SatelliteProbe
import com.cowork.bikerecoder.video.VideoBackend
import com.cowork.bikerecoder.video.VideoMapStyle
import com.cowork.bikerecoder.video.MediaStoreWriter
import com.cowork.bikerecoder.video.TripMediaStore
import com.cowork.bikerecoder.video.VideoInputsLoader
import com.cowork.bikerecoder.video.VideoJob
import com.cowork.bikerecoder.video.VideoJobStore
import com.cowork.bikerecoder.video.VideoService
import com.cowork.bikerecoder.core.model.LocationFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** Hand-wired dependency graph; owned by [BikeApp] (exactly one instance, so exactly one [AppDatabase]). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val okHttp: OkHttpClient = OkHttpClient()
    val db: AppDatabase = AppDatabase.create(appContext)
    val settings: SettingsRepository = SettingsRepository(appContext)
    val dayDistances: DayDistances = DataStoreDayDistances(appContext)

    private val segmentDir = File(appContext.filesDir, "segments4")
    val segments: SegmentRepository = SegmentRepository(segmentDir, okHttp)

    /**
     * The BRouter profile copy ([ProfileInstaller.install], blocking file IO) happens lazily on the first
     * [Router.route] call, which hops to [Dispatchers.IO] first — never on the main thread at app start.
     */
    val router: Router = LazyRouter {
        BRouterRouter(
            segmentDir = segmentDir,
            profileDir = ProfileInstaller.install(File(appContext.filesDir, "brouter-profiles")),
            maxRunningTimeMs = 60_000,
            // The engine blocks for up to 60 s; keep it off Default.
            dispatcher = Dispatchers.IO,
        )
    }

    private val kakao = KakaoLocalClient(okHttp, BuildConfig.KAKAO_REST_API_KEY)
    val placeSearch: PlaceSearch = kakao
    val recentPlaces: RecentPlaceStore = DataStoreRecentPlaces(appContext)
    /** The style survives restarts in filesDir so an offline cold start still shows the corridor maps. */
    val tileSource: TileSource = OpenFreeMapSource(okHttp, File(appContext.filesDir, "map-style/liberty-ko.json"))
    val tripStore: TripStore = RoomTripStore(db)
    internal val network = NetworkWaiter(appContext)
    val offlineMaps: MapLibreOfflineController = MapLibreOfflineController(
        appContext, tileSource, db.offlineRegionRefDao(), settings, network, clock = System::currentTimeMillis,
    )
    val offline: OfflineMapController = offlineMaps
    val tripManager: TripManager = TripManager(tripStore, offline, clock = System::currentTimeMillis)
    val rideStore: RideStore = RoomRideStore(db)
    val importStore: ImportStore = RoomImportStore(db)
    val tripLabels: TripLabelStore = RoomTripLabelStore(db)
    val rideStats: RideStatsSource = RoomRideStatsSource(db)
    val favorites: FavoriteRepository = FavoriteRepository(RoomFavoriteStore(db), clock = System::currentTimeMillis)

    /** The device barometer; null without one (altitude then comes from GPS only). */
    val barometer: PressureSource? = BarometerSource(appContext).takeIf { it.available }

    /** Created on first use so the TTS engine is not bound at app start. */
    val voice: VoiceOutput by lazy { AndroidTtsVoiceOutput(appContext) }

    /** Replaceable in tests. */
    var locationSourceFactory: () -> LocationSource = { FusedLocationSource(appContext) }

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error in app scope", e) },
    )

    init {
        // Off the critical path: the 100 MB general tile cache limit only needs to be set once per process.
        appScope.launch {
            try {
                offlineMaps.configureAmbientCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not set the map cache size", e)
            }
        }
    }

    /**
     * Latest fix, shared by every screen that needs "where am I". The location source only runs while
     * something collects this (plus a 5 s grace period). Any failure of the source (missing permission,
     * Play services unavailable or updating, ...) is logged and resets the value to null; it never
     * propagates into [appScope]. Collecting again restarts the source.
     */
    val currentLocation: StateFlow<LocationFix?> = flow { emitAll(locationSourceFactory().fixes()) }
        .map<LocationFix, LocationFix?> { it }
        .catch { e ->
            Log.w(TAG, "Location updates failed", e)
            emit(null)
        }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Guidance and recording run here, outside any UI lifecycle, on the main thread: [NavigationController],
     * [RideController] and their sessions must be driven from one single thread. The ride foreground service keeps
     * the process alive while either runs.
     */
    private val rideScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error in the ride scope", e) },
    )

    /**
     * The one location stream of guidance and recording: one upstream from [locationSourceFactory] (read when the
     * stream (re)starts) while anyone collects.
     */
    val rideLocation: LocationSource = SharedLocationSource({ locationSourceFactory() }, rideScope)

    /** Set by the ride foreground service; shown on the guidance screen when it could not start. */
    val guidanceService: GuidanceServiceStatus = GuidanceServiceStatus()

    private val navigationCreated = MutableStateFlow<NavigationController?>(null)

    /** Created on first use (it binds the TTS engine). */
    val navigation: NavigationController by lazy {
        NavigationController(this, rideScope).also { navigationCreated.value = it }
    }

    /** The guidance state without creating [navigation] (Idle until it exists). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val navigationUi: Flow<NavUiState> = navigationCreated.flatMapLatest { it?.ui ?: flowOf(NavUiState.Idle) }

    /** The current guidance state, without creating [navigation]. */
    val navigationState: NavUiState get() = navigationCreated.value?.ui?.value ?: NavUiState.Idle

    private val rideCreated = MutableStateFlow<RideController?>(null)

    /** Created on first use (it creates [navigation] too, which it follows). */
    val ride: RideController by lazy {
        RideController(this, rideScope).also { rideCreated.value = it }
    }

    /** The recording state without creating [ride] (Idle until it exists). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rideUi: Flow<RideUiState> = rideCreated.flatMapLatest { it?.ui ?: flowOf(RideUiState.Idle) }

    /** The current recording state, without creating [ride]. */
    val rideState: RideUiState get() = rideCreated.value?.ui?.value ?: RideUiState.Idle

    /**
     * Post-processing (photos, heart rate, simplified routes of ended segments) runs here, apart from [rideScope]: it
     * only touches Room, MediaStore and Health Connect. [PostProcessor] catches its own failures.
     */
    private val postScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val postStore: PostStore = RoomPostStore(db)
    val photoSource: PhotoSource = MediaStorePhotoSource(appContext)
    val heartRateSource: HeartRateSource = HealthConnectHeartRateSource(appContext)

    /** Gallery thumbnails and photos for the photo screens (memory caches shared by every screen). */
    val photoThumbnails: PhotoThumbnails = PhotoThumbnails(appContext.contentResolver, photoSource)

    /** Started while the app is on screen ([MainActivity.onStart] and the screens that show its results). */
    val postProcessor: PostProcessor = PostProcessor(
        rideStore, postStore, photoSource, heartRateSource, clock = System::currentTimeMillis, scope = postScope,
    )

    /** The picked GPX/ZIP files of the import (V1.6.0 spec §3.3). */
    val importReader: ImportReader = ContentImportReader(appContext)

    /**
     * The one GPX import job (V1.6.0 spec §3.4), outside any screen; it stops with the process. The trip's route profile
     * is the settings' default.
     */
    val gpxImportJob: GpxImportJob by lazy {
        GpxImportJob(
            reader = importReader,
            store = importStore,
            postProcessor = postProcessor::request,
            profile = { settings.settings.first().defaultProfile },
            clock = System::currentTimeMillis,
            scope = appScope,
            log = androidWarnLog("GpxImportJob"),
        )
    }

    /** Short rides asked about at the summaries (spec §6.10), shared so a segment is asked about once. */
    val shortRides: ShortRides = ShortRides(rideStore, offline, androidWarnLog("ShortRides"))

    /** Videos and summary images made from trips (V1.3.0 spec §5.1). */
    val tripMedia: TripMediaStore = RoomTripMediaStore(db)

    /** Last video options, this phone's seconds per frame and the unfinished job (spec §1.2, §3.6, §5.1). */
    val videoJobStore: VideoJobStore = VideoJobStore(appContext)

    /** Gallery files of made videos and images (spec §3.5). */
    val mediaStoreWriter: MediaStoreWriter = MediaStoreWriter(appContext.contentResolver)

    /** The made files of a trip that may still be shared (V1.5.0 spec §2.1). */
    val shareCandidates: ShareCandidates by lazy { ShareCandidates(mediaStoreWriter) }

    /** The video/image job runs here, outside any screen; [VideoService] keeps the process up meanwhile. */
    private val videoScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                // A map error may quote a tile URL, and so the key (ruling K1).
                Log.e(TAG, "Unhandled error in the video scope", VideoMapStyle.redactedError(e, BuildConfig.VWORLD_API_KEY))
            },
    )

    /** The one video or summary-image job (V1.3.0 spec §3); stops when a recording starts. */
    val videoJob: VideoJob by lazy { videoJob(videoBackend()) }

    /**
     * The real backend: the satellite map with the build's VWorld key (V1.3.1 spec §1.2), checked at the start by a probe
     * with [probeKey] — the same key, except in the device test of the fallback (a wrong key).
     */
    internal fun videoBackend(probeKey: String = BuildConfig.VWORLD_API_KEY): AndroidVideoBackend = AndroidVideoBackend(
        appContext, tileSource, mediaStoreWriter, photoThumbnails,
        satelliteKey = BuildConfig.VWORLD_API_KEY,
        probe = SatelliteProbe(okHttp, probeKey),
    )

    /** A job wired like [videoJob] on [backend]; only [videoJob] itself runs in the app. */
    internal fun videoJob(backend: VideoBackend): VideoJob = VideoJob(
        loadInputs = VideoInputsLoader(rideStore, postStore, tripStore, tripLabels)::load,
        store = videoJobStore,
        tripMedia = tripMedia,
        maps = offlineMaps,
        backend = backend,
        recording = rideUi.map { it is RideUiState.Starting || it is RideUiState.Active },
        online = network::isOnline,
        regions = tripRegions,
        startService = { VideoService.start(appContext) },
        scope = videoScope,
        // Map errors may quote a tile URL, and so the key (ruling K1).
        log = VideoMapStyle.redacting(androidWarnLog("VideoJob"), BuildConfig.VWORLD_API_KEY),
    )

    /** "서울 강서구" for a trip's title (V1.4.0 spec §2.5): the stored one first, else looked up once and stored. */
    val tripRegions: TripRegions by lazy {
        TripRegions(
            labels = tripLabels,
            lookup = { point -> (kakao.regionOf(point) as? SearchResult.Ok)?.value?.let { (d1, d2) -> RideTitle.shortRegion(d1, d2) } },
            log = VideoMapStyle.redacting(androidWarnLog("TripRegions"), BuildConfig.KAKAO_REST_API_KEY),
        )
    }

    /**
     * The feed's satellite pictures (V1.4.0 spec §2.4, §4), made in the background one at a time and kept in
     * `filesDir/feed_thumbs`. Nothing is started while a recording, guidance or a video is running ([FeedThumbnails.busy]).
     * Created on first use; pictures of trips deleted since the last run are swept away then.
     */
    val feedThumbnails: FeedThumbnails by lazy {
        val key = BuildConfig.VWORLD_API_KEY
        val renderer = FeedThumbnailRenderer(appContext, videoBackend(), tileSource, key)
        val inputs = VideoInputsLoader(rideStore, postStore, tripStore, tripLabels)
        val log = VideoMapStyle.redacting(androidWarnLog("FeedThumbnails"), key)
        FeedThumbnails(
            dir = File(appContext.filesDir, "feed_thumbs"),
            render = { tripId -> inputs.load(tripId)?.let { renderer.render(it) } },
            busy = combine(
                rideUi,
                navigationUi,
                flow { emitAll(videoJob.runningTripId) },
            ) { ride, guidance, videoTrip ->
                ride is RideUiState.Starting || ride is RideUiState.Active ||
                    guidance is NavUiState.Starting || guidance is NavUiState.Active || videoTrip != null
            },
            online = network.online(),
            scope = appScope,
            log = log,
        ).also { thumbs ->
            appScope.launch {
                try {
                    val keep = rideStore.tripSummaries().mapTo(HashSet()) { it.trip.id } + rideStore.emptyTrips().map { it.id }
                    thumbs.prune(keep)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Feed pictures of deleted trips could not be swept", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}

package com.cowork.bikerecoder

import android.content.Context
import android.util.Log
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.DataStoreDayDistances
import com.cowork.bikerecoder.data.DayDistances
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.location.FusedLocationSource
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.offline.MapLibreOfflineController
import com.cowork.bikerecoder.offline.NetworkWaiter
import com.cowork.bikerecoder.offline.SegmentRepository
import com.cowork.bikerecoder.routing.BRouterRouter
import com.cowork.bikerecoder.routing.ProfileInstaller
import com.cowork.bikerecoder.search.KakaoLocalClient
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.tts.AndroidTtsVoiceOutput
import com.cowork.bikerecoder.tts.VoiceOutput
import com.cowork.bikerecoder.core.model.LocationFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    val placeSearch: PlaceSearch = KakaoLocalClient(okHttp, BuildConfig.KAKAO_REST_API_KEY)
    val tileSource: TileSource = OpenFreeMapSource(okHttp)
    val tripStore: TripStore = RoomTripStore(db)
    val offlineMaps: MapLibreOfflineController = MapLibreOfflineController(
        appContext, tileSource, db.offlineRegionRefDao(), settings, NetworkWaiter(appContext), clock = System::currentTimeMillis,
    )
    val offline: OfflineMapController = offlineMaps
    val tripManager: TripManager = TripManager(tripStore, offline, clock = System::currentTimeMillis)

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
     * Guidance runs here, outside any UI lifecycle, on the main thread (the session must be driven from a
     * single thread). The location foreground service keeps the process alive while it runs.
     */
    private val navigationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error in navigation", e) },
    )

    /** Created on first use (it binds the TTS engine). */
    val navigation: NavigationController by lazy { NavigationController(this, navigationScope) }

    private companion object {
        const val TAG = "AppContainer"
    }
}

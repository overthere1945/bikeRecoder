package com.cowork.bikerecoder

import android.content.Context
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.location.FusedLocationSource
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.offline.NoopOfflineMapController
import com.cowork.bikerecoder.offline.SegmentRepository
import com.cowork.bikerecoder.routing.BRouterRouter
import com.cowork.bikerecoder.routing.ProfileInstaller
import com.cowork.bikerecoder.search.KakaoLocalClient
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.tts.AndroidTtsVoiceOutput
import com.cowork.bikerecoder.tts.VoiceOutput
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.io.File

/** Hand-wired dependency graph; owned by [BikeApp] (exactly one instance, so exactly one [AppDatabase]). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val okHttp: OkHttpClient = OkHttpClient()
    val db: AppDatabase = AppDatabase.create(appContext)
    val settings: SettingsRepository = SettingsRepository(appContext)

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
    val offline: OfflineMapController = NoopOfflineMapController()
    val tripManager: TripManager = TripManager(tripStore, offline, clock = System::currentTimeMillis)

    /** Created on first use so the TTS engine is not bound at app start. */
    val voice: VoiceOutput by lazy { AndroidTtsVoiceOutput(appContext) }

    /** Replaceable in tests. */
    var locationSourceFactory: () -> LocationSource = { FusedLocationSource(appContext) }
}

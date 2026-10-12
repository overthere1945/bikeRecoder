package com.cowork.bikerecoder.nav

import android.content.Context
import com.cowork.bikerecoder.core.gpx.GpxParser
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.location.GpxLocationSource
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.routing.BRouterGeoJsonParser
import com.cowork.bikerecoder.tts.VoiceOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import java.util.concurrent.CopyOnWriteArrayList

// Test doubles shared by the JVM tests (src/test) and the device scenario test (src/androidTest).
// Thread-safe: on the device the controller runs on the main thread while the test thread reads them.

/** Returns the parsed GeoJSON routes in call order (the last one repeats); records every request. */
class AssetRouter(private val jsons: List<String>) : Router {
    val requests: MutableList<RouteRequest> = CopyOnWriteArrayList()

    /** When set, every request fails with it. */
    @Volatile var failure: RouteResult.Failure? = null

    override suspend fun route(request: RouteRequest): RouteResult {
        requests += request
        failure?.let { return it }
        val json = jsons[minOf(requests.size - 1, jsons.lastIndex)]
        return RouteResult.Success(BRouterGeoJsonParser.parse(json, request.profile, request.stops))
    }
}

/** Records what would have been spoken (nothing while [muted]). */
class RecordingVoiceOutput : VoiceOutput {
    val spoken: MutableList<String> = CopyOnWriteArrayList()
    override val available: StateFlow<Boolean> = MutableStateFlow(true)
    override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
    @Volatile override var muted: Boolean = false

    override fun speak(text: String) {
        if (!muted) spoken += text
    }

    override fun shutdown() = Unit
}

/** [GpxLocationSource] at [speedup] (default 20×) that reports through [done] when its replay has ended. */
class Replay(fixes: List<LocationFix>, speedup: Double = 20.0) : LocationSource {
    private val gpx = GpxLocationSource(fixes, speedup)
    val done = CompletableDeferred<Unit>()
    override fun fixes(): Flow<LocationFix> = gpx.fixes().onCompletion { done.complete(Unit) }
}

/** No offline maps: the trip's download and delete do nothing. */
object NoOffline : OfflineMapController {
    override suspend fun deleteForTrip(tripId: Long) = Unit
    override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) = Unit
}

/** A text asset of the androidTest APK; [context] is the instrumentation context (`instrumentation.context`). */
fun scenarioAsset(context: Context, name: String): String =
    context.assets.open(name).use { it.readBytes().decodeToString() }

/** A GPX asset of the androidTest APK as fixes. */
fun scenarioGpx(context: Context, name: String): List<LocationFix> = GpxParser.parse(scenarioAsset(context, name))

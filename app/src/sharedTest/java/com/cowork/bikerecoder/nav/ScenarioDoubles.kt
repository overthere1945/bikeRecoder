package com.cowork.bikerecoder.nav

import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.routing.BRouterGeoJsonParser
import com.cowork.bikerecoder.tts.VoiceOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    @Volatile override var muted: Boolean = false

    override fun speak(text: String) {
        if (!muted) spoken += text
    }

    override fun shutdown() = Unit
}

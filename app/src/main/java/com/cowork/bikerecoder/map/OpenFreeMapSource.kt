package com.cowork.bikerecoder.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** OpenFreeMap vector tiles. The style is downloaded once, Korean-label transformed and cached in memory. */
class OpenFreeMapSource(
    private val client: OkHttpClient,
    override val styleUrl: String = DEFAULT_STYLE_URL,
) : TileSource {

    companion object {
        const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
    }

    override val attribution: String = ATTRIBUTION

    private val mutex = Mutex()
    private var cached: String? = null

    /** Errors (network, HTTP status, malformed JSON) propagate; cancellation is never swallowed. */
    override suspend fun styleJson(): String = mutex.withLock {
        cached ?: fetchAndTransform().also { cached = it }
    }

    private suspend fun fetchAndTransform(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(styleUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("style request failed: HTTP ${response.code}")
            val body = response.body.string()
            KoreanLabelStyle.apply(body)
        }
    }
}

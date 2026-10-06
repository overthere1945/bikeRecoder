package com.cowork.bikerecoder.map

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * OpenFreeMap vector tiles. The style is downloaded, Korean-label transformed and kept in memory; every
 * successful download is also written to [cacheFile] (atomically), which serves a later process that has
 * no network — so a cold start offline still shows the downloaded corridor maps.
 *
 * Order: memory → network → [cacheFile]. Only when all fail does [styleJson] throw.
 */
class OpenFreeMapSource(
    private val client: OkHttpClient,
    private val cacheFile: File?,
    override val styleUrl: String = DEFAULT_STYLE_URL,
    private val log: WarnLog = androidWarnLog(TAG),
) : TileSource {

    companion object {
        const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
        private const val TAG = "OpenFreeMapSource"
    }

    override val attribution: String = ATTRIBUTION

    private val mutex = Mutex()
    private var cached: String? = null

    /** Errors (network, HTTP status, malformed JSON) propagate only without a cached copy; cancellation always. */
    override suspend fun styleJson(): String = mutex.withLock {
        cached ?: load().also { cached = it }
    }

    private suspend fun load(): String = withContext(Dispatchers.IO) {
        val fetched = try {
            fetchAndTransform()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext readCache() ?: throw e
        }
        writeCache(fetched)
        fetched
    }

    private fun fetchAndTransform(): String {
        val request = Request.Builder().url(styleUrl).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("style request failed: HTTP ${response.code}")
            KoreanLabelStyle.apply(response.body.string())
        }
    }

    private fun readCache(): String? = try {
        cacheFile?.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: IOException) {
        log.warn("Could not read the cached style", e)
        null
    }

    /** Write to a temp file, then rename over the old copy: a crash never leaves a half-written style. */
    private fun writeCache(json: String) {
        val file = cacheFile ?: return
        if (file.isFile && file.readTextOrNull() == json) return
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(json)
            if (!tmp.renameTo(file)) throw IOException("rename to $file failed")
        } catch (e: IOException) {
            log.warn("Could not cache the style", e)
            tmp.delete()
        }
    }

    private fun File.readTextOrNull(): String? = try {
        readText()
    } catch (e: IOException) {
        null
    }
}

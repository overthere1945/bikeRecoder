package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Before a video (spec §1.3): fetches the one zoom-17 VWorld satellite tile under a point. True only for a 200 with an
 * image type and a JPEG or PNG body within [timeoutMs]; anything else (error status, VWorld's 200 XML error, a wrong
 * body, time-out, no connection, exception) is false. Logs never carry the key (nor the URL that holds it).
 */
class SatelliteProbe(
    client: OkHttpClient,
    private val key: String,
    private val baseUrl: HttpUrl = "https://api.vworld.kr".toHttpUrl(),
    timeoutMs: Long = TIMEOUT_MS,
    private val log: WarnLog = androidWarnLog(TAG),
) {
    private val client = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    suspend fun check(at: GeoPoint): Boolean = withContext(Dispatchers.IO) {
        val (x, y) = tile(at)
        val request = try {
            Request.Builder().url(
                baseUrl.newBuilder()
                    .addPathSegments("req/wmts/1.0.0")
                    .addPathSegment(key)
                    .addPathSegments("Satellite/$ZOOM/$y")
                    .addPathSegment("$x.jpeg")
                    .build(),
            ).build()
        } catch (e: IllegalArgumentException) {
            log.warn("Satellite probe: unusable key", null)
            return@withContext false
        }
        val call = client.newCall(request)
        try {
            // Blocking socket reads are not cancellable by themselves; abort the call on coroutine cancellation.
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use(::isImage)
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensureActive()
            // The class only: an exception message may hold the URL, and so the key.
            log.warn("Satellite probe failed: ${e.javaClass.simpleName}", null)
            false
        }
    }

    private fun isImage(response: Response): Boolean {
        val type = response.header("Content-Type").orEmpty()
        if (response.code != 200 || !type.startsWith("image/")) {
            log.warn("Satellite probe: HTTP ${response.code} ${type.substringBefore(';')}", null)
            return false
        }
        val source = response.body.source()
        val head = if (source.request(2)) source.readByteArray(2) else ByteArray(0)
        val ok = head.size == 2 && (
            (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) ||
                (head[0] == 0x89.toByte() && head[1] == 0x50.toByte())
            )
        if (!ok) log.warn("Satellite probe: $type body is not a JPEG or PNG", null)
        return ok
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val ZOOM = 17
        private const val TAG = "SatelliteProbe"

        /** Web Mercator tile x, y of [p] at [ZOOM]. */
        internal fun tile(p: GeoPoint): Pair<Int, Int> {
            val n = 1 shl ZOOM
            val x = floor((p.lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val latRad = p.lat * PI / 180.0
            val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return x to y
        }
    }
}

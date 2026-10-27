package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.offline.MapLibreOfflineController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Off-screen MapLibre snapshots of [width]×[height] pixels at pixel ratio 1 (CameraPath zooms assume 512-px tiles at
 * ratio 1, ruling V5 carry). One [MapSnapshotter] is made once and moved from camera to camera (its tiles stay loaded);
 * MapLibre runs on the main thread only. A snapshot that fails (style or tiles unreachable, spec §3.4) is retried once,
 * then taken over a plain background so the overlays still have a projection.
 */
internal class MapSnapshots(
    context: Context,
    private val tileSource: TileSource,
    val width: Int,
    val height: Int,
    private val log: WarnLog = androidWarnLog(TAG),
) {
    private val appContext: Context = context.applicationContext
    private var styleJson: String? = null
    private var map: MapSnapshotter? = null
    private var plain: MapSnapshotter? = null

    /** Snapshots taken so far (failed attempts not counted twice). */
    var taken = 0
        private set

    /** A snapshot: its software ARGB_8888 [bitmap] and the projection of the camera it was taken with. */
    class Shot internal constructor(val bitmap: Bitmap, private val snapshot: MapSnapshot) {
        /** Pixel x, y of each of [points] (x0, y0, x1, y1, …) on [bitmap]. */
        suspend fun project(points: List<GeoPoint>): FloatArray = withContext(Dispatchers.Main) {
            val out = FloatArray(points.size * 2)
            points.forEachIndexed { i, p ->
                val px = snapshot.pixelForLatLng(LatLng(p.lat, p.lon))
                out[2 * i] = px.x
                out[2 * i + 1] = px.y
            }
            out
        }
    }

    suspend fun take(camera: Camera): Shot {
        val json = style()
        taken++
        return withContext(Dispatchers.Main) {
            MapLibre.getInstance(appContext)
            val position = position(camera)
            val main = map ?: newSnapshotter(json, position).also { map = it }
            attempt(main, position)
                ?: attempt(main, position)
                ?: attempt(plain ?: newSnapshotter(PLAIN_STYLE, position).also { plain = it }, position)
                ?: throw IllegalStateException("map snapshot failed even without tiles")
        }
    }

    /** Main thread. Null (logged) when the snapshot fails or takes longer than [TIMEOUT_MS]. */
    private suspend fun attempt(snapshotter: MapSnapshotter, position: CameraPosition): Shot? {
        snapshotter.setCameraPosition(position)
        val snapshot = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<MapSnapshot> { cont ->
                    snapshotter.start(
                        object : MapSnapshotter.SnapshotReadyCallback {
                            override fun onSnapshotReady(snapshot: MapSnapshot) {
                                if (cont.isActive) cont.resume(snapshot)
                            }
                        },
                        object : MapSnapshotter.ErrorHandler {
                            override fun onError(error: String) {
                                if (cont.isActive) cont.resumeWithException(IllegalStateException(error))
                            }
                        },
                    )
                    cont.invokeOnCancellation { snapshotter.cancel() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Map snapshot failed", e)
            return null
        }
        if (snapshot == null) {
            log.warn("Map snapshot took longer than $TIMEOUT_MS ms", null)
            return null
        }
        return Shot(software(snapshot.bitmap), snapshot)
    }

    private fun newSnapshotter(json: String, position: CameraPosition): MapSnapshotter = MapSnapshotter(
        appContext,
        MapSnapshotter.Options(width, height)
            .withStyleBuilder(Style.Builder().fromJson(json))
            .withPixelRatio(MapLibreOfflineController.VIDEO_PIXEL_RATIO)
            .withCameraPosition(position)
            .withLogo(false)
            .withAttribution(false),
    )

    /** The style once per renderer; without it (offline, no cached copy) the plain background. */
    private suspend fun style(): String = styleJson ?: try {
        tileSource.styleJson()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Map style unavailable; drawing over a plain background", e)
        PLAIN_STYLE
    }.also { styleJson = it }

    companion object {
        private const val TAG = "MapSnapshots"
        private const val TIMEOUT_MS = 60_000L

        /** No sources: always renders, offline included. The colour is the liberty style's land. */
        private const val PLAIN_STYLE =
            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#F8F4F0"}}]}"""

        fun position(camera: Camera): CameraPosition = CameraPosition.Builder()
            .target(LatLng(camera.center.lat, camera.center.lon))
            .zoom(camera.zoom)
            .bearing(camera.bearing)
            .tilt(camera.tilt)
            .build()

        /** The frame Canvas is software (ruling V7): a hardware or other-config bitmap is copied to ARGB_8888. */
        fun software(bitmap: Bitmap): Bitmap =
            if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
}

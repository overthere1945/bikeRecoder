package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The feed's satellite picture (V1.4.0 spec §2.4) over a real MapLibre snapshot. The satellite source is replaced by one
 * without a style, which draws over a plain colour (no network, and the map's own colours cannot fake the route's), and the
 * backend's start check is faked: OK, or not.
 */
@RunWith(AndroidJUnit4::class)
class FeedThumbnailRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = object : TileSource {
        override val styleUrl = "https://example.invalid/style.json"
        override val attribution = "plain"
        override suspend fun styleJson(): String = throw IOException("offline")
    }

    /** Only the start check is asked of the backend. */
    private class Backend(val check: SatelliteCheck) : VideoBackend {
        val asked = mutableListOf<GeoPoint>()

        override suspend fun probeSatellite(at: GeoPoint): SatelliteCheck = check.also { asked += at }

        override fun freeBytes(): Long = TODO()
        override suspend fun loadMusic(uri: String, durationUs: Long): MusicTrack? = TODO()
        override suspend fun existingNames(kind: MediaKind): Set<String> = TODO()
        override suspend fun createPending(kind: MediaKind, displayName: String): String = TODO()
        override suspend fun publish(uri: String) = TODO()
        override suspend fun delete(uri: String) = TODO()
        override fun receivedBytes(): Long? = TODO()
        override suspend fun openVideo(uri: String, inputs: VideoInputs, options: VideoOptions, satellite: Boolean): VideoSink = TODO()
        override suspend fun writeImage(uri: String, inputs: VideoInputs, aspect: Aspect, satellite: Boolean) = TODO()
    }

    private fun renderer(backend: Backend) = FeedThumbnailRenderer(context, backend, plain, "TESTKEY") { _, _ -> plain }

    private fun Bitmap.count(left: Int, top: Int, right: Int, bottom: Int, match: (Int) -> Boolean) =
        VideoTestTrip.count(this, left, top, right, bottom, match)

    private fun orange(c: Int) = VideoTestTrip.near(c, FrameStyle.ROUTE_ORANGE, 30)

    @Test
    fun picture1280x720WithTheRouteInOrange() = runBlocking {
        val inputs = VideoTestTrip.inputs()
        val backend = Backend(SatelliteCheck.OK)

        val image = renderer(backend).render(inputs)!!

        assertEquals(1280, image.width)
        assertEquals(720, image.height)
        assertEquals(Bitmap.Config.ARGB_8888, image.config)
        // The start check is made at the ride's start.
        assertEquals(listOf(inputs.timeline.points.first().point), backend.asked)
        val route = image.count(0, 0, 1280, 720, ::orange)
        val green = image.count(0, 0, 1280, 720) { VideoTestTrip.near(it, FrameStyle.START_GREEN, 20) }
        val black = image.count(0, 0, 1280, 720) { VideoTestTrip.near(it, FrameStyle.END_BLACK, 12) }
        Log.i(TAG, "orange $route, green $green, black $black")
        assertTrue("route $route", route > 1_500)
        assertTrue("start $green", green > 300)
        assertTrue("end $black", black > 300)
        image.recycle()
    }

    /** The route is fitted inside a 64 px margin: no line near the edges (the glow may reach a little). */
    @Test
    fun routeStaysInsideTheMargin() = runBlocking {
        val image = renderer(Backend(SatelliteCheck.OK)).render(VideoTestTrip.inputs())!!

        val band = 40
        val outside = image.count(0, 0, 1280, band, ::orange) + image.count(0, 720 - band, 1280, 720, ::orange) +
            image.count(0, 0, band, 720, ::orange) + image.count(1280 - band, 0, 1280, 720, ::orange)
        assertEquals("route pixels in the outer $band px", 0, outside)
        image.recycle()
    }

    /** One attribution line on a dark pill at the bottom right. */
    @Test
    fun attributionAtTheBottomRight() = runBlocking {
        val image = renderer(Backend(SatelliteCheck.OK)).render(VideoTestTrip.inputs())!!

        val corner = image.count(880, 660, 1280, 720) { Color.red(it) < 120 && Color.green(it) < 120 }
        val words = image.count(880, 660, 1280, 720) { VideoTestTrip.near(it, Color.WHITE, 8) }
        val same = image.count(0, 660, 400, 720) { VideoTestTrip.near(it, Color.WHITE, 8) }
        Log.i(TAG, "pill $corner, words $words, elsewhere $same")
        assertTrue("dark pill $corner", corner > 1_000)
        assertTrue("white words $words", words > 100)
        assertEquals("nothing white at the bottom left", 0, same)
        image.recycle()
    }

    @Test
    fun satelliteCheckFailedGivesNull() = runBlocking {
        assertNull(renderer(Backend(SatelliteCheck.FAILED)).render(VideoTestTrip.inputs()))
    }

    @Test
    fun noKeyGivesNull() = runBlocking {
        assertNull(renderer(Backend(SatelliteCheck.NO_KEY)).render(VideoTestTrip.inputs()))
    }

    private companion object {
        const val TAG = "FeedThumbnailRendererTest"
    }
}

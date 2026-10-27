package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.FramePlan
import com.cowork.bikerecoder.core.video.SceneKind
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.math.abs

/**
 * One 1080×1920 frame at a time over a real MapLibre snapshot (OpenFreeMap over the device's internet). The overlay
 * checks hold even when the tiles do not load (the snapshot then falls back to a plain background).
 */
@RunWith(AndroidJUnit4::class)
class FrameRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tiles = OpenFreeMapSource(OkHttpClient(), cacheFile = null)
    private val inputs = VideoTestTrip.inputs()
    private val options = VideoOptions(length = VideoLength.SHORT)
    private val plans = VideoTimeline.plan(inputs.timeline, inputs.photos, options, inputs.dayStarts)
    private val cameras = CameraPath.cameras(
        plans, inputs.timeline, CameraPath.overview(inputs.timeline.points.map { it.point }, W, H),
    )
    private val renderers = mutableListOf<FrameRenderer>()

    @After
    fun tearDown() = renderers.forEach { it.close() }

    private fun renderer(opts: VideoOptions = options, source: TileSource = tiles) =
        FrameRenderer(context, source, inputs, opts, W, H) { id -> if (id == 1L) VideoTestTrip.photo() else null }
            .also { renderers += it }

    private fun FrameRenderer.frame(plan: FramePlan): Bitmap = runBlocking {
        Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { render(plan, cameras[plan.index], Canvas(it)) }
    }

    /** The scene frame whose progress is nearest [progress]. */
    private fun planOf(kind: SceneKind, progress: Float) = plans.filter { it.kind == kind }.minBy { abs(it.sceneProgress - progress) }

    @Test
    fun rideFrameDrawsTheDoneRouteUpToTheCurrentPoint() {
        val frame = renderer().frame(planOf(SceneKind.RIDE, 0.25f))
        val done = Color.parseColor(RideColors.colorAt(0))

        // The camera centre is the current point; the route already ridden comes from behind it (below the centre).
        val behind = VideoTestTrip.count(frame, W / 2 - 100, H / 2 + 40, W / 2 + 100, H / 2 + 240) { VideoTestTrip.near(it, done) }
        val ahead = VideoTestTrip.count(frame, W / 2 - 100, H / 2 - 240, W / 2 + 100, H / 2 - 40) { VideoTestTrip.near(it, done) }
        assertTrue("done route behind the current point: $behind", behind > 300)
        assertTrue("only the faint remaining route ahead: $ahead vs $behind", ahead * 5 < behind)
        // The current point: a white ring around the route colour, at the centre.
        val ring = VideoTestTrip.count(frame, W / 2 - 40, H / 2 - 40, W / 2 + 40, H / 2 + 40) { VideoTestTrip.near(it, Color.WHITE, 10) }
        assertTrue("white ring at the current point: $ring", ring > 100)
    }

    @Test
    fun withoutAMapStyleTheRouteIsStillDrawnOverAPlainBackground() {
        val offline = object : TileSource {
            override val styleUrl = "https://example.invalid/style.json"
            override val attribution = "test"
            override suspend fun styleJson(): String = throw IOException("offline")
        }
        val renderer = renderer(source = offline)
        val frame = renderer.frame(planOf(SceneKind.RIDE, 0.25f))

        val done = Color.parseColor(RideColors.colorAt(0))
        val behind = VideoTestTrip.count(frame, W / 2 - 100, H / 2 + 40, W / 2 + 100, H / 2 + 240) { VideoTestTrip.near(it, done) }
        assertTrue("done route behind the current point: $behind", behind > 300)
        assertTrue("plain background", VideoTestTrip.near(frame.getPixel(20, 20), Color.rgb(0xF8, 0xF4, 0xF0), 4))
        assertEquals(1, renderer.snapshotsTaken)
    }

    @Test
    fun photoFrameShowsThePhotoInTheMiddle() {
        val frame = renderer().frame(planOf(SceneKind.PHOTO, 0.5f))

        val centre = VideoTestTrip.count(frame, W / 2 - 100, H / 2 - 100, W / 2 + 100, H / 2 + 100) {
            VideoTestTrip.near(it, VideoTestTrip.PHOTO_COLOR, 12)
        }
        assertEquals("the middle 200×200 is the photo", 200 * 200, centre)
        // 80 % of the width: the photo spans x ≈ 108..972 (its own dark frame is about 43 px wide), and no further.
        assertTrue("inside the card", VideoTestTrip.near(frame.getPixel(W / 2 - 380, H / 2), VideoTestTrip.PHOTO_COLOR, 30))
        assertTrue("outside the card", !VideoTestTrip.near(frame.getPixel(W / 2 - 480, H / 2), VideoTestTrip.PHOTO_COLOR, 60))
    }

    @Test
    fun photoAppearsAndDisappears() {
        val photo = plans.filter { it.kind == SceneKind.PHOTO }
        val renderer = renderer()
        val first = renderer.frame(photo.first())
        val last = renderer.frame(photo.last())

        assertTrue(!VideoTestTrip.near(first.getPixel(W / 2, H / 2), VideoTestTrip.PHOTO_COLOR, 40))
        assertTrue(!VideoTestTrip.near(last.getPixel(W / 2, H / 2), VideoTestTrip.PHOTO_COLOR, 40))
    }

    @Test
    fun hudBandIsDrawnAtTheBottom() {
        val frame = renderer().frame(planOf(SceneKind.RIDE, 0.25f))
        val band = H - 200

        val text = VideoTestTrip.count(frame, 0, band, W, H) { VideoTestTrip.near(it, Color.WHITE, 15) }
        val dark = VideoTestTrip.count(frame, 0, band, W, H) { maxOf(Color.red(it), Color.green(it), Color.blue(it)) < 160 }
        assertTrue("white numbers in the band: $text", text > 800)
        assertTrue("dark band: $dark", dark > W * 200 * 3 / 10)
    }

    @Test
    fun reusedFramesTakeOneSnapshot() {
        val renderer = renderer()
        val overview = plans.filter { it.kind == SceneKind.INTRO_OVERVIEW }.take(8)
        assertTrue(overview.drop(1).all { it.reuseMap })

        overview.forEach { renderer.frame(it) }
        assertEquals(1, renderer.snapshotsTaken)

        val ride = plans.filter { it.kind == SceneKind.RIDE }.take(2)
        ride.forEach { renderer.frame(it) }
        assertEquals(3, renderer.snapshotsTaken)

        plans.filter { it.kind == SceneKind.OUTRO }.take(5).forEach { renderer.frame(it) }
        assertEquals(4, renderer.snapshotsTaken)
    }

    @Test
    fun outroFadesToBlack() {
        val last = renderer().frame(plans.last())

        assertEquals(1f, plans.last().fade)
        val black = VideoTestTrip.count(last, 0, 0, W, H) { VideoTestTrip.near(it, Color.BLACK, 2) }
        assertEquals(W * H, black)
    }

    private companion object {
        const val W = 1080
        const val H = 1920
    }
}

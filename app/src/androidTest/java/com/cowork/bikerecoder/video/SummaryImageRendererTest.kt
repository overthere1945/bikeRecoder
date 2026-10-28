package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.BuildConfig
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.SummaryImageLayout
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * The summary image (spec §3.9) over a real MapLibre snapshot: the VWorld satellite map with the build's key (the plain
 * OpenFreeMap map when the build has none), over the device's internet. The colour checks of the route and its ends use
 * a source without a style, which draws over a plain colour, so the map's own colours cannot fake them.
 */
@RunWith(AndroidJUnit4::class)
class SummaryImageRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plainMap = OpenFreeMapSource(OkHttpClient(), cacheFile = null)
    private val tiles: TileSource =
        if (BuildConfig.VWORLD_API_KEY.isBlank()) plainMap else SatelliteTileSource(plainMap, BuildConfig.VWORLD_API_KEY)
    private val offline = object : TileSource {
        override val styleUrl = "https://example.invalid/style.json"
        override val attribution = "test"
        override suspend fun styleJson(): String = throw IOException("offline")
    }

    private fun render(inputs: VideoInputs, aspect: Aspect, photos: List<Bitmap>, source: TileSource = tiles) =
        runBlocking { SummaryImageRenderer(context, source).render(inputs, aspect, photos) }

    private fun Bitmap.count(box: Box, match: (Int) -> Boolean) = VideoTestTrip.count(
        this, box.x.toInt(), box.y.toInt(), (box.x + box.w).toInt(), (box.y + box.h).toInt(), match,
    )

    /** The route's orange line (spec §3.1). */
    private fun Bitmap.routePixels(box: Box) = count(box) { VideoTestTrip.near(it, FrameStyle.ROUTE_ORANGE, 30) }

    private fun Bitmap.isPhotoAt(box: Box) =
        VideoTestTrip.near(getPixel((box.x + box.w / 2).toInt(), (box.y + box.h / 2).toInt()), VideoTestTrip.PHOTO_COLOR, 12)

    private fun assertSoftware(image: Bitmap, aspect: Aspect) {
        assertEquals(aspect.width, image.width)
        assertEquals(aspect.height, image.height)
        assertEquals(Bitmap.Config.ARGB_8888, image.config)
    }

    @Test
    fun portraitImageHasMapAndThreePhotos() {
        val photo = VideoTestTrip.photo()
        val image = render(VideoTestTrip.inputs(), Aspect.PORTRAIT, listOf(photo, photo, photo))

        assertSoftware(image, Aspect.PORTRAIT)
        val layout = SummaryImageLayout.layout(Aspect.PORTRAIT, 3)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        assertTrue(layout.photos.all { image.isPhotoAt(it) })
    }

    @Test
    fun landscapeImageHasMapOnTheLeft() {
        val photo = VideoTestTrip.photo()
        val image = render(VideoTestTrip.inputs(), Aspect.LANDSCAPE, listOf(photo))

        assertSoftware(image, Aspect.LANDSCAPE)
        val layout = SummaryImageLayout.layout(Aspect.LANDSCAPE, 1)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        assertTrue(image.isPhotoAt(layout.photos.single()))
        assertEquals("nothing of the route right of the map", 0, image.routePixels(Box(layout.map.w + 1, 0f, 1920 - layout.map.w - 1, 1080f)))
    }

    @Test
    fun imageWithoutPhotosOrHeartRate() {
        val image = render(VideoTestTrip.inputs(withPhoto = false, withHeartRate = false), Aspect.PORTRAIT, emptyList())

        assertSoftware(image, Aspect.PORTRAIT)
        val layout = SummaryImageLayout.layout(Aspect.PORTRAIT, 0)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        // No photo row: the map takes its height, and only a thin plain band is left above "bikeRecoder".
        assertTrue(layout.map.h > SummaryImageLayout.layout(Aspect.PORTRAIT, 3).map.h)
        val below = layout.stats.last().let { (it.y + it.h + 2).toInt() }
        val above = (layout.footer.y - 2).toInt()
        assertTrue("band ${above - below}", above - below in 1..48)
        val plain = VideoTestTrip.count(image, 100, below, 980, above) { VideoTestTrip.near(it, Color.WHITE, 3) }
        assertEquals(880 * (above - below), plain)
    }

    @Test
    fun landscapeImageWithoutPhotos() {
        val image = render(VideoTestTrip.inputs(withPhoto = false, withHeartRate = false), Aspect.LANDSCAPE, emptyList())

        assertSoftware(image, Aspect.LANDSCAPE)
        val layout = SummaryImageLayout.layout(Aspect.LANDSCAPE, 0)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        // The right column is centred above the footer: as much room above the header as below the last row (+ gap).
        val top = layout.header.y - 48f
        val bottom = layout.footer.y - layout.stats.maxOf { it.y + it.h }
        assertTrue("above $top below $bottom", abs(bottom - top - 24f) <= 1f)
    }

    /** Spec §3.9: the grey line under the date is the ride's automatic title (§3.8), drawn whole. */
    @Test
    fun headerUsesRideTitle() {
        val title = "저녁 라이딩 · 서울 강서구"
        val image = render(VideoTestTrip.inputs().copy(rideTitle = title), Aspect.PORTRAIT, emptyList())

        val header = SummaryImageLayout.layout(Aspect.PORTRAIT, 0).header
        // The second line: below the date's descenders, down into the gap above the map.
        val line = Box(0f, header.y + header.h * 0.7f, Aspect.PORTRAIT.width.toFloat(), header.h * 0.3f + 20f)
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var grey = 0
        for (y in line.y.toInt() until (line.y + line.h).toInt()) for (x in 0 until image.width) {
            val c = image.getPixel(x, y)
            if (VideoTestTrip.near(c, Color.WHITE, 8)) continue
            left = minOf(left, x)
            right = maxOf(right, x)
            if (VideoTestTrip.near(c, GREY, 30)) grey++
        }
        assertTrue("grey words: $grey", grey > 200)
        val expected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14 * Aspect.PORTRAIT.width / 360f
            typeface = Typeface.DEFAULT
        }.measureText(title)
        val drawn = right - left + 1
        Log.i(TAG, "ride title drawn $drawn px, measured $expected, grey $grey")
        assertTrue("drawn $drawn, measured $expected", abs(drawn - expected) <= 4f)
    }

    /**
     * Spec §3.9 / §1.1: the attribution is one small grey line in the image's top right corner, on the white (off the
     * map, so it never tangles with the map's names), in both orientations.
     */
    @Test
    fun attributionInOneLineAtTheTopRight() {
        for (aspect in listOf(Aspect.PORTRAIT, Aspect.LANDSCAPE)) {
            val image = render(VideoTestTrip.inputs(withPhoto = false), aspect, emptyList())
            val u = minOf(aspect.width, aspect.height) / 360f
            val map = SummaryImageLayout.layout(aspect, 0).map
            val corner = Box(aspect.width * 0.65f, 0f, aspect.width * 0.35f, 18 * u)
            assertTrue("$aspect: off the map", corner.y + corner.h <= map.y || map.x + map.w <= corner.x)
            val words = image.count(corner) { !VideoTestTrip.near(it, Color.WHITE, 8) }
            assertTrue("$aspect: words in the corner $words", words > 50)
            // One line: the rows just below it are blank.
            val below = image.count(Box(corner.x, corner.h, corner.w, 2 * u)) { !VideoTestTrip.near(it, Color.WHITE, 8) }
            assertEquals("$aspect: nothing just below", 0, below)
        }
    }

    /**
     * The plain map's long attribution ("OpenFreeMap © OpenMapTiles Data from OpenStreetMap", the build without a key)
     * stays in the header's box: in landscape inside the white column, its left end right of the map.
     */
    @Test
    fun plainMapAttributionStaysInTheHeaderBox() {
        assertEquals(OpenFreeMapSource.ATTRIBUTION, plainMap.attribution)
        for (aspect in listOf(Aspect.LANDSCAPE, Aspect.PORTRAIT)) {
            val image = render(VideoTestTrip.inputs(withPhoto = false), aspect, emptyList(), source = plainMap)
            val u = minOf(aspect.width, aspect.height) / 360f
            val layout = SummaryImageLayout.layout(aspect, 0)
            val map = layout.map
            val header = layout.header
            // The rows of the attribution; in landscape from the map's right edge on (the map itself is not white).
            val from = if (map.y < 18 * u) (map.x + map.w).toInt() else 0
            var left = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            for (y in 0 until (18 * u).toInt()) for (x in from until aspect.width) {
                if (VideoTestTrip.near(image.getPixel(x, y), Color.WHITE, 8)) continue
                left = minOf(left, x)
                right = maxOf(right, x)
            }
            val message = "$aspect: words $left..$right, header ${header.x}..${header.x + header.w}, map right ${map.x + map.w}"
            Log.i(TAG, message)
            assertTrue(message, right > left)
            if (aspect == Aspect.LANDSCAPE) assertTrue(message, left > map.x + map.w)
            assertTrue(message, left >= header.x - 1 && right <= header.x + header.w + 1)
        }
    }

    /** Spec §3.9: the route glows orange (§3.1), with the green start and the black end (§3.2) in white borders. */
    @Test
    fun routeGlowAndStartEndDots() {
        val image = render(VideoTestTrip.inputs(), Aspect.PORTRAIT, emptyList(), source = offline)

        val map = SummaryImageLayout.layout(Aspect.PORTRAIT, 0).map
        val orange = image.routePixels(map)
        // The glow: a soft orange halo (warm, red over green over blue), lighter than the line and not white.
        val glow = image.count(map) {
            val r = Color.red(it)
            val g = Color.green(it)
            val b = Color.blue(it)
            !VideoTestTrip.near(it, FrameStyle.ROUTE_ORANGE, 30) && r > 220 && g in 140..225 && b < g - 40
        }
        val green = image.count(map) { VideoTestTrip.near(it, FrameStyle.START_GREEN, 20) }
        val black = image.count(map) { VideoTestTrip.near(it, FrameStyle.END_BLACK, 12) }
        val message = "orange $orange, glow $glow, green $green, black $black"
        Log.i(TAG, message)
        assertTrue(message, orange > 3_000)
        assertTrue(message, glow > 5_000)
        // A 9u dot with a 2.5u border leaves a 6.5u (19.5 px) disc of colour.
        assertTrue(message, green > 1_000)
        assertTrue(message, black > 1_000)
    }

    /**
     * For looking at, only when asked: `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`. The images go to
     * the app's cache (`run-as … cat cache/v131-image-*.png`), never to shared storage.
     */
    @Test
    fun screenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        val inputs = VideoTestTrip.inputs().copy(rideTitle = "아침 라이딩 · 서울 영등포구")
        val photo = VideoTestTrip.photo()
        fun save(name: String, image: Bitmap) =
            File(context.cacheDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        save("v131-image-portrait", render(inputs, Aspect.PORTRAIT, listOf(photo, photo, photo)))
        save("v131-image-landscape", render(inputs, Aspect.LANDSCAPE, listOf(photo, photo)))
        save("v131-image-nophoto", render(VideoTestTrip.inputs(withPhoto = false, withHeartRate = false), Aspect.PORTRAIT, emptyList()))
        save("v131-image-landscape-plain", render(inputs, Aspect.LANDSCAPE, listOf(photo, photo), source = plainMap))
    }

    private companion object {
        const val GREY = 0xFF757575.toInt()
        const val TAG = "SummaryImageRendererTest"
    }
}

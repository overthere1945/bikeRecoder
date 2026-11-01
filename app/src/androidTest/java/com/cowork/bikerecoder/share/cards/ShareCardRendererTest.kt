package com.cowork.bikerecoder.share.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.CardSize
import com.cowork.bikerecoder.core.video.RidePoint
import com.cowork.bikerecoder.core.video.RideTimeline
import com.cowork.bikerecoder.core.video.ShareCardLayout
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.ui.activity.ActivityStyle
import com.cowork.bikerecoder.video.TripTotals
import com.cowork.bikerecoder.video.VideoInputs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The share cards (spec §2.2–§2.5) on a fixed trip: a 5 km bike loop near 36.4 N 128.2 E. The satellite card's map is a
 * style of one plain colour (deterministic), a source whose style cannot be had (MapSnapshots falls back: no map), or none
 * at all; the photo card and slides draw solid-colour test photos.
 */
@RunWith(AndroidJUnit4::class)
class ShareCardRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val offline = object : TileSource {
        override val styleUrl = "https://example.invalid/style.json"
        override val attribution = "test"
        override suspend fun styleJson(): String = throw IOException("offline")
    }

    /** A real map of one colour, [MAP_COLOR]. */
    private val mapped = object : TileSource {
        override val styleUrl = "https://example.invalid/plain.json"
        override val attribution = "test map"
        override suspend fun styleJson(): String =
            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#F8F4F0"}}]}"""
    }
    private val inputs = loop()
    private val facts = cardFacts(inputs, "경북 상주시")
    private val bike = ActivityStyle.color(ActivityType.BIKE).toArgb()

    private fun render(size: CardSize, plain: TileSource? = mapped, satellite: TileSource? = null) =
        runBlocking { ShareCardRenderer(context, satellite = satellite, plain = plain).satellite(inputs, facts, size) }

    @Test
    fun feedSize() {
        val card = render(CardSize.FEED)
        assertEquals(1080, card.width)
        assertEquals(1350, card.height)
        assertEquals(Bitmap.Config.ARGB_8888, card.config)
    }

    @Test
    fun storySize() {
        val card = render(CardSize.STORY)
        assertEquals(1080, card.width)
        assertEquals(1920, card.height)
    }

    @Test
    fun routeColourInMap() {
        val card = render(CardSize.FEED)
        val route = card.count(ShareCardLayout.satellite(CardSize.FEED).map) { near(it, bike, 8) }
        Log.i(TAG, "route pixels $route")
        assertTrue("route pixels $route", route > 0)
    }

    @Test
    fun bandsAreDark() {
        for (size in listOf(CardSize.FEED, CardSize.STORY)) {
            val card = render(size)
            val boxes = ShareCardLayout.satellite(size)
            val top = card.meanLuminance(boxes.top)
            val bottom = card.meanLuminance(boxes.bottom)
            Log.i(TAG, "$size: top band $top, bottom band $bottom")
            assertTrue("$size top $top", top < 0.45)
            assertTrue("$size bottom $bottom", bottom < 0.45)
        }
    }

    /**
     * Spec §2.2: nothing at all (no text, no band) in the story's top and bottom 14%. The bands' edges fall inside a
     * pixel row (the top band starts at 0.14 × 1920 = 268.8, the bottom one ends at 1651.2), so rows 268 and 1651 are
     * partly band: the clean rows are 0..267 and 1652..1919.
     */
    @Test
    fun storySafeZonesHaveNoWhiteText() {
        val card = render(CardSize.STORY, plain = null)
        val other = { c: Int -> c != ShareCardRenderer.NO_MAP }
        assertEquals(0, count(card, 0, 0, 1080, 268, other))
        assertEquals(0, count(card, 0, 1652, 1080, 1920, other))
    }

    /** The map is there: the plain colour beside the bands (the satellite failing first, so the plain map is the next). */
    @Test
    fun satelliteFailsPlainMapDrawn() {
        val card = render(CardSize.STORY, satellite = offline, plain = mapped)
        assertTrue(Integer.toHexString(card.getPixel(0, 0)), near(card.getPixel(0, 0), MAP_COLOR, 3))
    }

    /** A source whose style cannot be had falls back to a plain background inside MapSnapshots: no map, the dark card. */
    @Test
    fun fallbackShotMovesOn() {
        assertDarkWithoutAttribution(render(CardSize.STORY, satellite = offline, plain = null))
    }

    @Test
    fun satelliteFallsBackToPlain() {
        assertDarkWithoutAttribution(render(CardSize.STORY, satellite = offline, plain = offline))
    }

    private fun assertDarkWithoutAttribution(card: Bitmap) {
        assertEquals(ShareCardRenderer.NO_MAP, card.getPixel(0, 0))
        // Where the attribution would be: right under the top band, on the right half.
        val top = ShareCardLayout.satellite(CardSize.STORY).top
        val below = (top.y + top.h).toInt() + 1
        assertEquals(0, count(card, 540, below, 1080, below + 42) { it != ShareCardRenderer.NO_MAP })
    }

    @Test
    fun noMapGivesDarkCard() {
        val story = render(CardSize.STORY, plain = null)
        assertEquals(ShareCardRenderer.NO_MAP, story.getPixel(0, 0))
        val feed = render(CardSize.FEED, plain = null)
        val map = ShareCardLayout.satellite(CardSize.FEED).map
        // Left of the map box, between the bands: the plain fill.
        assertEquals(ShareCardRenderer.NO_MAP, feed.getPixel(1, (map.y + map.h / 2).toInt()))
        assertTrue("route on the dark card", feed.count(map) { near(it, bike, 8) } > 0)
    }

    /** Spec §2.3: the photo fills the card (no top band), the bottom band is dark, the photo's place a dot in the bike colour. */
    @Test
    fun photoCardSizeAndBand() {
        val photo = solid(1200, 1600, PHOTO_COLOR)
        val card = renderer().photoCard(inputs, facts, CardSize.FEED, photo, inputs.timeline.points.first().point)
        assertEquals(1080, card.width)
        assertEquals(1350, card.height)
        assertEquals(PHOTO_COLOR, card.getPixel(0, 0))
        val boxes = ShareCardLayout.photoCard(CardSize.FEED)
        val band = boxes.bottom
        val above = Box(0f, 0f, 1080f, band.y)
        val bottom = card.meanLuminance(band)
        val photoLuminance = card.meanLuminance(above)
        Log.i(TAG, "photo card bottom band $bottom, photo $photoLuminance")
        assertTrue("bottom $bottom, photo $photoLuminance", bottom < photoLuminance * 0.75)
        // The band's top edge: the photo right above it, darker right inside it (left of the route square, no text).
        assertEquals(PHOTO_COLOR, card.getPixel(2, band.y.toInt() - 1))
        val inside = card.getPixel(2, band.y.toInt() + 2)
        assertTrue(Integer.toHexString(inside), inside != PHOTO_COLOR)
        assertTrue("dot where the photo was taken", card.count(boxes.map) { near(it, bike, 8) } > 0)
    }

    /**
     * Spec §2.3 (ruling): a photo taken 3 km north of the loop would put its dot over the photo above the band; it is
     * left out, so the bike colour is nowhere on the card.
     */
    @Test
    fun offRouteDotIsSkipped() {
        val off = GeoPoint(36.4 + 3_000.0 / 111_195.0, 128.2)
        val card = renderer().photoCard(inputs, facts, CardSize.FEED, solid(1200, 1600, PHOTO_COLOR), off)
        assertEquals(0, count(card, 0, 0, 1080, 1350) { near(it, bike, 8) })
    }

    /** Spec §2.3: in a story, nothing in the top and bottom 14% but the photo itself. */
    @Test
    fun photoCardStoryKeepsSafeZones() {
        val card = renderer().photoCard(inputs, facts, CardSize.STORY, solid(1200, 1600, PHOTO_COLOR), null)
        val other = { c: Int -> c != PHOTO_COLOR }
        assertEquals(0, count(card, 0, 0, 1080, 268, other))
        assertEquals(0, count(card, 0, 1652, 1080, 1920, other))
    }

    /** Spec §2.4: a portrait photo is cropped to fill the slide. */
    @Test
    fun photoSlidePortraitFills() {
        val slide = renderer().photoSlide(solid(900, 1600, Color.RED), CardSize.FEED)
        assertEquals(1080, slide.width)
        assertEquals(1350, slide.height)
        for ((x, y) in listOf(0 to 0, 1079 to 0, 0 to 1349, 1079 to 1349)) assertEquals("($x, $y)", Color.RED, slide.getPixel(x, y))
    }

    /**
     * Spec §2.4: a landscape photo lies across the slide's width over a blurred fill of itself. The photo is blue on the
     * left and green on the right, so both the fill (row 100, above the photo) and the photo (the centre row) change
     * colour at x = 540: gradually in the blurred fill, at once in the photo.
     */
    @Test
    fun photoSlideLandscapeBlurBehind() {
        val photo = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.GREEN)
                drawRect(0f, 0f, 800f, 900f, Paint().apply { color = Color.BLUE })
            }
        }
        val slide = renderer().photoSlide(photo, CardSize.FEED)
        assertEquals(1080, slide.width)
        assertEquals(1350, slide.height)
        val mixed = { c: Int -> !near(c, Color.BLUE, 24) && !near(c, Color.GREEN, 24) }
        val blurred = count(slide, 0, 100, 1080, 101, mixed)
        val sharp = count(slide, 0, 675, 1080, 676, mixed)
        Log.i(TAG, "mixed pixels: row 100 $blurred, centre row $sharp")
        assertTrue("row 100 ${Integer.toHexString(slide.getPixel(10, 100))}", near(slide.getPixel(10, 100), Color.BLUE, 8))
        assertTrue("blurred row $blurred", blurred >= 8)
        assertTrue("centre left", near(slide.getPixel(10, 675), Color.BLUE, 8))
        assertTrue("centre right", near(slide.getPixel(1070, 675), Color.GREEN, 8))
        assertTrue("sharp row $sharp", sharp <= 3)
    }

    /** Spec §2.5: transparent around the route and the numbers. */
    @Test
    fun stickerTransparentCorners() {
        val sticker = renderer().sticker(inputs, facts)
        assertEquals(1080, sticker.width)
        assertEquals(1080, sticker.height)
        for ((x, y) in listOf(0 to 0, 1079 to 0, 0 to 1079, 1079 to 1079)) assertEquals("($x, $y)", 0, Color.alpha(sticker.getPixel(x, y)))
        val route = count(sticker, 0, 0, 1080, 648) { Color.alpha(it) == 255 }
        Log.i(TAG, "opaque route pixels $route")
        assertTrue("opaque route pixels $route", route > 0)
    }

    @Test
    fun stickerPngKeepsAlpha() {
        val bytes = ByteArrayOutputStream().also { renderer().sticker(inputs, facts).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(0, Color.alpha(decoded.getPixel(0, 0)))
    }

    /**
     * For looking at, only when asked: `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`. The cards go to the
     * app's external files dir (`share-cards/`).
     */
    @Test
    fun screenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        val dir = File(context.getExternalFilesDir(null), "share-cards").apply { mkdirs() }
        fun save(name: String, card: Bitmap) = File(dir, "$name.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        save("satellite-feed", render(CardSize.FEED))
        save("satellite-story", render(CardSize.STORY))
        save("satellite-feed-nomap", render(CardSize.FEED, plain = null))
        save("satellite-story-nomap", render(CardSize.STORY, plain = null))
        save("satellite-story-hike", runBlocking {
            val hike = inputs.copy(activity = ActivityType.HIKE, segmentActivities = listOf(ActivityType.HIKE))
            ShareCardRenderer(context, satellite = null, plain = mapped).satellite(hike, cardFacts(hike, "경북 상주시"), CardSize.STORY)
        })
        val photo = gradient(1200, 1600)
        val taken = inputs.timeline.points[inputs.timeline.points.size / 3].point
        save("photo-feed", renderer().photoCard(inputs, facts, CardSize.FEED, photo, taken))
        save("photo-story", renderer().photoCard(inputs, facts, CardSize.STORY, photo, taken))
        save("slide-portrait-feed", renderer().photoSlide(photo, CardSize.FEED))
        save("slide-landscape-feed", renderer().photoSlide(gradient(1600, 900), CardSize.FEED))
        save("slide-landscape-story", renderer().photoSlide(gradient(1600, 900), CardSize.STORY))
        save("sticker", renderer().sticker(inputs, facts))
    }

    private fun renderer() = ShareCardRenderer(context, satellite = null, plain = null)

    private fun solid(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** A photo stand-in for the screenshots: sky blue at the top to green at the bottom. */
    private fun gradient(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, w / 3f, h.toFloat(), 0xFF7FB3E0.toInt(), 0xFF3E6B2F.toInt(), Shader.TileMode.CLAMP)
        })
    }

    private fun Bitmap.count(box: Box, match: (Int) -> Boolean) =
        count(this, box.x.toInt(), box.y.toInt(), (box.x + box.w).toInt(), (box.y + box.h).toInt(), match)

    /** Mean relative luminance (linear sRGB, 0–1) of the pixels in [box]. */
    private fun Bitmap.meanLuminance(box: Box): Double {
        var sum = 0.0
        var n = 0
        for (y in box.y.toInt() until (box.y + box.h).toInt()) for (x in box.x.toInt() until (box.x + box.w).toInt()) {
            val c = getPixel(x, y)
            sum += 0.2126 * linear(Color.red(c)) + 0.7152 * linear(Color.green(c)) + 0.0722 * linear(Color.blue(c))
            n++
        }
        return sum / n
    }

    private fun linear(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun count(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, match: (Int) -> Boolean): Int {
        var n = 0
        for (y in top until bottom) for (x in left until right) if (match(bitmap.getPixel(x, y))) n++
        return n
    }

    private fun near(actual: Int, expected: Int, tolerance: Int) =
        abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance

    private companion object {
        const val TAG = "ShareCardRendererTest"
        const val MAP_COLOR = 0xFFF8F4F0.toInt()
        const val PHOTO_COLOR = 0xFF6699CC.toInt()

        /** A 5 km circle near 36.4 N 128.2 E, one point every ~10 m at 5 m/s, one bike segment on 2026-11-03. */
        fun loop(): VideoInputs {
            val start = LocalDate.of(2026, 11, 3).atTime(9, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
            val radiusM = 5_000.0 / (2 * PI)
            val n = 500
            val mPerDeg = 111_195.0
            val points = (0..n).map { i ->
                val a = 2 * PI * i / n
                val lat = 36.4 + radiusM * sin(a) / mPerDeg
                val lon = 128.2 + radiusM * cos(a) / (mPerDeg * cos(Math.toRadians(36.4)))
                RidePoint(i * 2_000L, GeoPoint(lat, lon), null, start + i * 2_000L, 0, 0)
            }
            return VideoInputs(
                tripId = 1,
                timeline = RideTimeline(points),
                photos = emptyList(),
                heartRate = emptyList(),
                dayStarts = emptyList(),
                title = "11월 3일 (화)",
                rideTitle = "아침 라이딩",
                totals = TripTotals(5_000.0, 1_000_000L, 5.0, 8.0, 410.0, avgBpm = 128, calories = null),
                canColorByHeart = false,
                firstStartedAt = start,
                segmentActivities = listOf(ActivityType.BIKE),
                activity = ActivityType.BIKE,
            )
        }
    }
}

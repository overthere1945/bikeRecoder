package com.cowork.bikerecoder.share.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
 * The satellite share card (spec §2.2) on a fixed trip: a 5 km bike loop near 36.4 N 128.2 E. The map is a source
 * without a style, which draws over a plain colour (deterministic), or no map at all.
 */
@RunWith(AndroidJUnit4::class)
class ShareCardRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val offline = object : TileSource {
        override val styleUrl = "https://example.invalid/style.json"
        override val attribution = "test"
        override suspend fun styleJson(): String = throw IOException("offline")
    }
    private val inputs = loop()
    private val facts = cardFacts(inputs, "경북 상주시")
    private val bike = ActivityStyle.color(ActivityType.BIKE).toArgb()

    private fun render(size: CardSize, plain: TileSource? = offline) =
        runBlocking { ShareCardRenderer(context, satellite = null, plain = plain).satellite(inputs, facts, size) }

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

    @Test
    fun storySafeZonesHaveNoWhiteText() {
        val card = render(CardSize.STORY, plain = null)
        val white = { c: Int -> Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240 }
        assertEquals(0, count(card, 0, 0, 1080, 269, white))
        assertEquals(0, count(card, 0, 1652, 1080, 1920, white))
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

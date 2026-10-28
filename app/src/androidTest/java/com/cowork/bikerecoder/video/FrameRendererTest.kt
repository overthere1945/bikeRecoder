package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.BuildConfig
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.FramePlan
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.SceneKind
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * One frame at a time over a real MapLibre snapshot: the VWorld satellite map with the build's key (the plain
 * OpenFreeMap map when the build has none), over the device's internet. Checks that need a known background use a
 * source without a style, which draws over a plain colour.
 */
@RunWith(AndroidJUnit4::class)
class FrameRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plainMap = OpenFreeMapSource(OkHttpClient(), cacheFile = null)
    private val tiles: TileSource =
        if (BuildConfig.VWORLD_API_KEY.isBlank()) plainMap else SatelliteTileSource(plainMap, BuildConfig.VWORLD_API_KEY)
    private val offline = object : TileSource {
        override val styleUrl = "https://example.invalid/style.json"
        override val attribution = "test"
        override suspend fun styleJson(): String = throw IOException("offline")
    }
    private val inputs = VideoTestTrip.inputs()
    private val options = VideoOptions(length = VideoLength.SHORT)
    private val plans = VideoTimeline.plan(inputs.timeline, inputs.photos, options, inputs.dayStarts)
    private val cameras = camerasFor(plans, W, H)
    private val unit = W / 360f
    private val renderers = mutableListOf<FrameRenderer>()

    @After
    fun tearDown() = renderers.forEach { it.close() }

    /** Ruling C1: the real frame height for the marker's place. */
    private fun camerasFor(plans: List<FramePlan>, width: Int, height: Int, videoInputs: VideoInputs = inputs): List<Camera> =
        CameraPath.cameras(
            plans, videoInputs.timeline, FrameRenderer.introCamera(videoInputs, width, height),
            FrameRenderer.outroCamera(videoInputs, width, height), heightPx = height,
        )

    private fun renderer(
        opts: VideoOptions = options,
        source: TileSource = tiles,
        width: Int = W,
        height: Int = H,
        videoInputs: VideoInputs = inputs,
        loadPhoto: suspend (Long) -> Bitmap? = { id -> if (id == 1L) VideoTestTrip.photo() else null },
    ) = FrameRenderer(context, source, videoInputs, opts, width, height, loadPhoto).also { renderers += it }

    private fun FrameRenderer.frame(plan: FramePlan, cams: List<Camera> = cameras): Bitmap = runBlocking {
        Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { render(plan, cams[plan.index], Canvas(it)) }
    }

    /** The scene frame whose progress is nearest [progress]. */
    private fun planOf(kind: SceneKind, progress: Float, from: List<FramePlan> = plans) =
        from.filter { it.kind == kind }.minBy { abs(it.sceneProgress - progress) }

    /** The [k]-th frame (from 0) of the first [kind] scene. */
    private fun nth(kind: SceneKind, k: Int, from: List<FramePlan> = plans) = from.filter { it.kind == kind }[k]

    /** Where MapLibre puts [plan]'s current point on a [width]×[height] frame with [cams]. */
    private fun markerOf(plan: FramePlan, cams: List<Camera> = cameras, width: Int = W, height: Int = H): PointF = runBlocking {
        val maps = MapSnapshots(context, offline, width, height)
        try {
            val shot = maps.take(cams[plan.index])
            val xy = shot.project(listOf(inputs.timeline.at(plan.moveMs).point))
            shot.bitmap.recycle()
            PointF(xy[0], xy[1])
        } finally {
            maps.close()
        }
    }

    private val hudTop = (H * (1 - FrameStyle.HUD_SHARE)).toInt()

    private fun isOrange(c: Int, tolerance: Int = 24) = VideoTestTrip.near(c, FrameStyle.ROUTE_ORANGE, tolerance)
    private fun brightness(c: Int) = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0

    /** The average colour of row [y] of [frame]. */
    private fun rowAverage(frame: Bitmap, y: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (x in 0 until frame.width) {
            val c = frame.getPixel(x, y)
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
        }
        val n = frame.width
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun maxChannelDiff(a: Int, b: Int) =
        maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

    /** Pixels of [frame] between radii [from] and [to] around [at] that satisfy [match], and how many were looked at. */
    private fun ring(frame: Bitmap, at: PointF, from: Float, to: Float, match: (Int) -> Boolean): Pair<Int, Int> {
        var hit = 0
        var all = 0
        for (y in (at.y - to).toInt()..(at.y + to).toInt()) for (x in (at.x - to).toInt()..(at.x + to).toInt()) {
            val d = hypot(x - at.x, y - at.y)
            if (d < from || d > to || x !in 0 until frame.width || y !in 0 until frame.height) continue
            all++
            if (match(frame.getPixel(x, y))) hit++
        }
        return hit to all
    }

    private fun count(frame: Bitmap, box: Box, match: (Int) -> Boolean) =
        VideoTestTrip.count(frame, box.x.toInt(), box.y.toInt(), (box.x + box.w).toInt(), (box.y + box.h).toInt(), match)

    // ---- the route and the current point (spec §3.1, §3.2) ----

    @Test
    fun rideFrameHasGlowTailAndPulse() {
        val plan = planOf(SceneKind.RIDE, 0.25f)
        val frame = renderer().frame(plan)
        val marker = markerOf(plan)

        val orange = VideoTestTrip.count(frame, 0, 0, W, hudTop) { isOrange(it) }
        Log.i(TAG, "ride frame orange pixels above the band: $orange")
        assertTrue("a plainly visible orange route: $orange", orange >= MIN_ORANGE)

        // The white ring of the current point (between the colour disc of 8u and the white disc of 11u), within 40u.
        val (white, all) = ring(frame, marker, 8.5f * unit, 10.5f * unit) { VideoTestTrip.near(it, Color.WHITE, 30) }
        assertTrue("white ring around the current point: $white of $all", white > all * 8 / 10)
        // The tail: the thicker orange line just behind the current point (below it on screen).
        val tail = VideoTestTrip.count(
            frame, (marker.x - 100).toInt(), (marker.y + 40).toInt(), (marker.x + 100).toInt(), (marker.y + 140).toInt(),
        ) { isOrange(it) }
        assertTrue("orange tail behind the current point: $tail", tail > 1_000)
    }

    @Test
    fun theDoneRouteEndsAtTheCurrentPoint() {
        val plan = planOf(SceneKind.RIDE, 0.25f)
        val frame = renderer().frame(plan)
        val m = markerOf(plan)

        // The route already ridden comes from behind the current point (below it); ahead is only the faint white rest.
        val behind = VideoTestTrip.count(frame, (m.x - 100).toInt(), (m.y + 40).toInt(), (m.x + 100).toInt(), (m.y + 140).toInt()) { isOrange(it, 40) }
        val ahead = VideoTestTrip.count(frame, (m.x - 100).toInt(), (m.y - 240).toInt(), (m.x + 100).toInt(), (m.y - 40).toInt()) { isOrange(it, 40) }
        assertTrue("done route behind the current point: $behind", behind > 300)
        assertTrue("only the faint remaining route ahead: $ahead vs $behind", ahead * 5 < behind)
    }

    /**
     * [plan] drawn over a plain background (no style) by a flat camera at zoom 18 centred on the current point and
     * heading the way the route goes there: the ridden route runs straight down from the middle of the frame, 150 m of
     * it (the tail) about 635 px long. No band, so nothing fades. [index] sets the frame number (the pulse's time).
     */
    private fun flatFrame(plan: FramePlan, index: Int = plan.index, loadPhoto: suspend (Long) -> Bitmap? = { null }): Bitmap {
        val located = inputs.timeline.at(plan.moveMs)
        val a = inputs.timeline.points[located.doneIndex].point
        val b = inputs.timeline.points[located.doneIndex + 1].point
        val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
            sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
        val camera = Camera(located.point, 18.0, (Math.toDegrees(atan2(y, x)) + 360) % 360, 0.0)
        return runBlocking {
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also {
                renderer(opts = options.copy(hud = false), source = offline, loadPhoto = loadPhoto)
                    .render(plan.copy(index = index), camera, Canvas(it))
            }
        }
    }

    /** The width of the orange core of the line crossing row [y] at the middle of the frame. */
    private fun lineWidth(frame: Bitmap, y: Int): Int {
        var left = W / 2
        while (left > 0 && isOrange(frame.getPixel(left - 1, y), 40)) left--
        var right = W / 2
        while (right < W - 1 && isOrange(frame.getPixel(right + 1, y), 40)) right++
        return if (isOrange(frame.getPixel(W / 2, y), 40)) right - left + 1 else 0
    }

    /** The tail is 9u wide at the current point and the route 7u further back; beside the line, its soft orange glow. */
    @Test
    fun tailWidensToNineUnitsAndTheRouteGlows() {
        val frame = flatFrame(planOf(SceneKind.RIDE, 0.25f))
        val near = lineWidth(frame, H / 2 + 60)
        val far = lineWidth(frame, H / 2 + 790) // about 185 m back: past the tail
        Log.i(TAG, "line width near $near px, far $far px")
        assertTrue("tail at the current point ≈ 9u (27 px): $near", near in 25..29)
        assertTrue("route behind the tail ≈ 7u (21 px): $far", far in 19..23)

        // The glow: outside the white casing (5u) and within 15u of the line, semi-transparent orange.
        var glow = 0
        for (y in H / 2 + 760 until H / 2 + 820) for (x in W / 2 - (15 * unit).toInt()..W / 2 + (15 * unit).toInt()) {
            if (abs(x - W / 2) < 6 * unit) continue
            val c = frame.getPixel(x, y)
            if (Color.red(c) - Color.blue(c) > 30 && !isOrange(c, 40)) glow++
        }
        Log.i(TAG, "glow pixels beside the line: $glow")
        assertTrue("orange glow beside the line: $glow", glow > 1_500)
    }

    /** The pulse (1 s): half a second apart, the glow ring around the current point is small, then wide and faint. */
    @Test
    fun markerPulses() {
        val plan = planOf(SceneKind.RIDE, 0.25f)
        val centre = PointF(W / 2f, H / 2f)
        // Ahead of the point (above it) only the faint white rest of the route: any orange tint there is the pulse.
        fun tinted(frame: Bitmap): Int {
            var n = 0
            for (y in (centre.y - 22 * unit).toInt() until (centre.y - 10).toInt()) for (x in (centre.x - 22 * unit).toInt()..(centre.x + 22 * unit).toInt()) {
                val d = hypot(x - centre.x, y - centre.y)
                if (d < 17 * unit || d > 22 * unit) continue
                val c = frame.getPixel(x, y)
                if (Color.red(c) - Color.blue(c) > 30) n++
            }
            return n
        }
        val small = tinted(flatFrame(plan, index = 24 * 4 + 2)) // phase 0.08: radius ≈ 15u
        val wide = tinted(flatFrame(plan, index = 24 * 4 + 14)) // phase 0.58: radius ≈ 23u
        Log.i(TAG, "pulse ring pixels: small $small, wide $wide")
        assertTrue("the wide pulse fills the ring: $wide", wide > 1_200)
        assertTrue("the small pulse does not reach it: $small vs $wide", small * 5 < wide)
    }

    @Test
    fun markerInLowerThird() {
        for (progress in listOf(0.25f, 0.5f, 0.75f)) {
            val share = markerOf(planOf(SceneKind.RIDE, progress)).y / H
            assertTrue("1080×1920 at $progress: $share", share in 0.62f..0.72f)
        }
        // The 720p fallback, with its own height (ruling C1).
        val small = camerasFor(plans, 720, 1280)
        val share = markerOf(planOf(SceneKind.RIDE, 0.5f), small, 720, 1280).y / 1280
        assertTrue("720×1280: $share", share in 0.62f..0.72f)
    }

    @Test
    fun heartRateColoursGlowInTheirOwnColour() {
        // Under 100 bpm all along: every point is the blue band.
        val calm = inputs.copy(heartRate = inputs.heartRate.map { HeartRateSample(it.timeMillis, 90) })
        val blue = Color.parseColor("#1E88E5")
        val plan = planOf(SceneKind.RIDE, 0.5f)
        val frame = renderer(opts = options.copy(coloring = RouteColoring.HEART_RATE), source = offline, videoInputs = calm).frame(plan)

        // Over the plain background, around the stretch already ridden (behind the current point): the halo is lighter
        // blue (blue blended with the background), never orange.
        val m = markerOf(plan)
        val halo = VideoTestTrip.count(frame, 0, (m.y + 15 * unit).toInt(), W, hudTop) {
            Color.blue(it) - Color.red(it) > 25 && !VideoTestTrip.near(it, blue, 30) && !VideoTestTrip.near(it, Color.WHITE, 30)
        }
        val orange = VideoTestTrip.count(frame, 0, 0, W, hudTop) { Color.red(it) - Color.blue(it) > 80 && Color.red(it) > Color.green(it) + 40 }
        assertTrue("blue halo: $halo", halo > 3_000)
        assertEquals("nothing orange above the band", 0, orange)
    }

    @Test
    fun withoutAMapStyleTheRouteIsStillDrawnOverAPlainBackground() {
        val renderer = renderer(source = offline)
        val plan = planOf(SceneKind.RIDE, 0.25f)
        val frame = renderer.frame(plan)
        val m = markerOf(plan)

        val behind = VideoTestTrip.count(frame, (m.x - 100).toInt(), (m.y + 40).toInt(), (m.x + 100).toInt(), (m.y + 140).toInt()) { isOrange(it) }
        assertTrue("done route behind the current point: $behind", behind > 300)
        assertTrue("plain background", VideoTestTrip.near(frame.getPixel(W / 2, H / 3), Color.rgb(0xF8, 0xF4, 0xF0), 4))
        assertEquals(1, renderer.snapshotsTaken)
    }

    // ---- the haze and the number band (spec §3.3, §3.4) ----

    @Test
    fun hazeOnlyWhenTilted() {
        val renderer = renderer()
        val ride = renderer.frame(planOf(SceneKind.RIDE, 0.5f))
        val overview = renderer.frame(nth(SceneKind.INTRO_OVERVIEW, 0))

        val rideTop = maxChannelDiff(rowAverage(ride, 0), FrameStyle.HAZE)
        val overviewTop = maxChannelDiff(rowAverage(overview, 0), FrameStyle.HAZE)
        Log.i(TAG, "top row off the haze colour: ride $rideTop, overview $overviewTop")
        assertTrue("tilted: the top row is sky blue ($rideTop)", rideTop < 60)
        assertTrue("flat: no haze ($overviewTop)", overviewTop > 100)
    }

    @Test
    fun hudGradientAndElevationGraph() {
        val plan = planOf(SceneKind.RIDE, 0.5f)
        val withHud = renderer().frame(plan)
        val without = renderer(opts = options.copy(hud = false)).frame(plan)
        val hud = FrameLayout.hud(W, H, 4)
        val bandTop = hud.bandTop.toInt()

        val bottom = brightness(rowAverage(withHud, H - 1))
        val top = abs(brightness(rowAverage(withHud, bandTop)) - brightness(rowAverage(without, bandTop)))
        assertTrue("dark at the bottom: $bottom", bottom < 60)
        assertTrue("clear at the top of the band: $top", top < 30)
        assertEquals("27 % of the height", H * FrameStyle.HUD_SHARE, H - hud.bandTop, 1f)

        val graph = count(withHud, hud.graph) { isOrange(it, 40) }
        assertTrue("orange elevation done so far: $graph", graph > 2_000)
        // Half-way: orange on the left half of the graph only.
        val right = count(withHud, Box(hud.graph.x + hud.graph.w * 0.6f, hud.graph.y, hud.graph.w * 0.4f, hud.graph.h)) { isOrange(it, 40) }
        assertEquals("no orange ahead of the current point", 0, right)
        // White numbers under it.
        val numbers = count(withHud, Box(0f, hud.values.first().y, W.toFloat(), hud.values.first().h)) { VideoTestTrip.near(it, Color.WHITE, 15) }
        assertTrue("white numbers: $numbers", numbers > 2_000)

        // A route without heights: no graph.
        val flat = VideoTestTrip.inputs(withAltitude = false)
        val flatPlans = VideoTimeline.plan(flat.timeline, flat.photos, options, flat.dayStarts)
        val flatPlan = planOf(SceneKind.RIDE, 0.5f, flatPlans)
        val noHeights = renderer(videoInputs = flat).frame(flatPlan, camerasFor(flatPlans, W, H, flat))
        val flatHud = FrameLayout.hud(W, H, 3)
        assertEquals("no graph without heights", 0, count(noHeights, flatHud.graph) { isOrange(it, 40) })
    }

    /**
     * With the band up, the route fades out under it: no orange runs through the numbers (both orientations). Over the
     * plain background, so the only orange there could be is the route's (satellite roofs can be orange-brown).
     */
    @Test
    fun routeFadesUnderTheBand() {
        for (wide in listOf(false, true)) {
            val (w, h) = if (wide) H to W else W to H
            val opts = options.copy(aspect = if (wide) Aspect.LANDSCAPE else Aspect.PORTRAIT)
            val plans = VideoTimeline.plan(inputs.timeline, inputs.photos, opts, inputs.dayStarts)
            val cams = camerasFor(plans, w, h)
            val hud = FrameLayout.hud(w, h, 4)
            val gone = ceil(hud.bandTop + (h - hud.bandTop) * FrameStyle.ROUTE_FADE_SHARE).toInt()
            for (progress in listOf(0.25f, 0.5f)) {
                val plan = planOf(SceneKind.RIDE, progress, plans)
                val frame = runBlocking {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        renderer(opts, source = offline, width = w, height = h).render(plan, cams[plan.index], Canvas(it))
                    }
                }
                val g = hud.graph
                val tinted = { c: Int -> Color.red(c) - Color.blue(c) > 40 }
                // Just above the band the route is still there (the check can see it)...
                val before = VideoTestTrip.count(frame, 0, (hud.bandTop - 40).toInt(), w, hud.bandTop.toInt(), tinted)
                assertTrue("${w}x$h at $progress: route above the band: $before", before > 200)
                // ...and below the fade there is none, the graph's orange aside.
                val orange = VideoTestTrip.count(frame, 0, gone, w, h, tinted) -
                    count(frame, Box(g.x - 2, max(g.y, gone.toFloat()), g.w + 4, g.y + g.h - max(g.y, gone.toFloat()) + 2), tinted)
                assertEquals("${w}x$h at $progress: orange route under the band (the graph aside)", 0, orange)
            }
        }
    }

    // ---- the title card (spec §3.5) ----

    /**
     * Ruling F3: the sky view's route sits above the title, in portrait and landscape and from the first to the last
     * overview frame (the camera turns meanwhile). Over the plain background, so orange tint can only be the route; the
     * date and name lines are white, so any tint inside them is route under the words.
     */
    @Test
    fun introRouteClearsTheTitle() {
        val tinted = { c: Int -> Color.red(c) - Color.blue(c) > 40 }
        for (wide in listOf(false, true)) {
            val (w, h) = if (wide) H to W else W to H
            val opts = options.copy(aspect = if (wide) Aspect.LANDSCAPE else Aspect.PORTRAIT)
            val plans = VideoTimeline.plan(inputs.timeline, inputs.photos, opts, inputs.dayStarts)
            val cams = camerasFor(plans, w, h)
            val title = FrameLayout.title(w, h)
            val u = FrameLayout.unit(w, h)
            val overview = plans.filter { it.kind == SceneKind.INTRO_OVERVIEW }
            for (plan in listOf(overview.first(), overview.last())) {
                val frame = runBlocking {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        renderer(opts, source = offline, width = w, height = h).render(plan, cams[plan.index], Canvas(it))
                    }
                }
                val above = VideoTestTrip.count(frame, 0, 0, w, title.block.y.toInt(), tinted)
                // From the top of the date to below the name: white words only.
                val words = Box(0f, title.block.y, w.toFloat(), title.nameBaseline + FrameLayout.DESCENT * FrameStyle.TITLE_NAME_SIZE * u - title.block.y)
                val under = count(frame, words, tinted)
                assertTrue("${w}x$h frame ${plan.index}: route above the title: $above", above > 1_000)
                assertEquals("${w}x$h frame ${plan.index}: route inside the title's words", 0, under)
            }
        }
    }

    @Test
    fun titleCardThreeLines() {
        // The same frames for another trip: only the words differ, so pixels that differ are the title's.
        val other = inputs.copy(title = "1월 8일 (목)", rideTitle = "새벽 라이딩 · 경기 성남시 분당구", totals = inputs.totals.copy(distanceM = 98_765.0))
        val area = Box(W / 2f - 160 * unit, H / 2f, 320 * unit, H / 2f)
        fun differing(a: Bitmap, b: Bitmap, match: (Int) -> Boolean): Int {
            var n = 0
            for (y in area.y.toInt() until (area.y + area.h).toInt()) for (x in area.x.toInt() until (area.x + area.w).toInt()) {
                if (match(a.getPixel(x, y)) != match(b.getPixel(x, y))) n++
            }
            return n
        }
        val isWhite = { c: Int -> VideoTestTrip.near(c, Color.WHITE, 40) }
        val isOrangeText = { c: Int -> isOrange(c, 40) }

        val first = nth(SceneKind.INTRO_OVERVIEW, 0)
        val a = renderer().frame(first)
        val b = renderer(videoInputs = other).frame(first)
        val white = differing(a, b, isWhite)
        val orange = differing(a, b, isOrangeText)
        assertTrue("white date and name below the middle: $white", white > 2_000)
        assertTrue("orange distance below the middle: $orange", orange > 500)

        val gone = nth(SceneKind.INTRO_DESCENT, 4)
        val c = renderer().frame(gone)
        val d = renderer(videoInputs = other).frame(gone)
        assertTrue("faded by the 5th descent frame", differing(c, d, isWhite) + differing(c, d, isOrangeText) < 50)
    }

    // ---- the photo card (spec §3.6) ----

    @Test
    fun photoPopsInAndZoomsSlowly() {
        val plain = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(VideoTestTrip.PHOTO_COLOR) }
        val renderer = renderer(loadPhoto = { plain })
        val cy = (H / 2f - 16 * unit).toInt()
        val cyan = { c: Int -> Color.blue(c) - Color.red(c) > 80 && Color.green(c) - Color.red(c) > 60 }
        fun width(frame: Bitmap): Int {
            var left = W / 2
            while (left > 0 && cyan(frame.getPixel(left - 1, cy))) left--
            var right = W / 2
            while (right < W - 1 && cyan(frame.getPixel(right + 1, cy))) right++
            return if (cyan(frame.getPixel(W / 2, cy))) right - left + 1 else 0
        }
        val final = W * 0.8f

        val third = width(renderer.frame(nth(SceneKind.PHOTO, 2)))
        val thirtieth = width(renderer.frame(nth(SceneKind.PHOTO, 29)))
        assertTrue("3rd frame: $third of $final", third > 0.85f * final)
        assertEquals("30th frame: the card's own size", final, thirtieth.toFloat(), 2f)
    }

    /** The slow zoom inside the card: a line a quarter of the way into the photo moves outward from frame 10 to 50. */
    @Test
    fun photoZoomsInsideTheCard() {
        // The photo colour with a dark 8 px line at x = 400 of 1600.
        val lined = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(VideoTestTrip.PHOTO_COLOR)
            Canvas(this).drawRect(396f, 0f, 404f, 1200f, android.graphics.Paint().apply { color = Color.BLACK })
        }
        val renderer = renderer(loadPhoto = { lined })
        val cy = (H / 2f - 16 * unit).toInt()
        fun lineX(frame: Bitmap): Int {
            var x = W / 2
            while (x > 0 && brightness(frame.getPixel(x, cy)) > 80) x--
            return x
        }
        val early = lineX(renderer.frame(nth(SceneKind.PHOTO, 10)))
        val late = lineX(renderer.frame(nth(SceneKind.PHOTO, 50)))
        // On the 864 px card the line sits 216·zoom px left of the middle: zoom 1.010 at frame 10, 1.051 at frame 50.
        fun expected(k: Int) = W / 2 - 216f * (1 + (FrameStyle.PHOTO_ZOOM - 1) * k / (VideoTimeline.PHOTO_FRAMES - 1))
        Log.i(TAG, "photo line at $early then $late (expected ${expected(10)}, ${expected(50)})")
        assertEquals(expected(10), early.toFloat(), 4f)
        assertEquals(expected(50), late.toFloat(), 4f)
        assertTrue("moved outward: $early → $late", early - late in 6..12)
    }

    @Test
    fun photoFrameShowsThePhotoInTheMiddle() {
        val frame = renderer().frame(planOf(SceneKind.PHOTO, 0.5f))

        val centre = VideoTestTrip.count(frame, W / 2 - 100, H / 2 - 100, W / 2 + 100, H / 2 + 100) {
            VideoTestTrip.near(it, VideoTestTrip.PHOTO_COLOR, 12)
        }
        assertEquals("the middle 200×200 is the photo", 200 * 200, centre)
        // 80 % of the width: the photo spans x ≈ 108..972, and no further.
        assertTrue("inside the card", VideoTestTrip.near(frame.getPixel(W / 2 - 380, H / 2), VideoTestTrip.PHOTO_COLOR, 30))
        assertTrue("outside the card", !VideoTestTrip.near(frame.getPixel(W / 2 - 480, H / 2), VideoTestTrip.PHOTO_COLOR, 60))
    }

    @Test
    fun unreadablePhotoShowsTheMapOnly() {
        val photo = planOf(SceneKind.PHOTO, 0.5f)
        for (loader in listOf<suspend (Long) -> Bitmap?>({ null }, { throw IOException("gone") })) {
            val frame = renderer(loadPhoto = loader).frame(photo)
            val centre = VideoTestTrip.count(frame, W / 2 - 100, H / 2 - 100, W / 2 + 100, H / 2 + 100) {
                VideoTestTrip.near(it, VideoTestTrip.PHOTO_COLOR, 40)
            }
            assertEquals(0, centre)
        }
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

    // ---- the closing card (spec §3.7) ----

    @Test
    fun outroCardSixCellsAndStartEndDots() {
        val frame = renderer(source = offline).frame(nth(SceneKind.OUTRO, 39))
        val layout = FrameLayout.outro(W, H, 6)
        val card = layout.card

        val white = count(frame, card) { minOf(Color.red(it), Color.green(it), Color.blue(it)) > 225 }
        assertTrue("a white card: $white of ${(card.w * card.h).toInt()}", white > card.w * card.h * 0.75f)
        // Words in every one of the six cells.
        layout.values.forEachIndexed { i, box ->
            val ink = count(frame, box) { brightness(it) < 100 }
            assertTrue("value $i drawn: $ink", ink > 100)
        }
        val above = Box(0f, 0f, W.toFloat(), card.y)
        val green = count(frame, above) { VideoTestTrip.near(it, FrameStyle.START_GREEN, 30) }
        val black = count(frame, above) { VideoTestTrip.near(it, FrameStyle.END_BLACK, 20) }
        assertTrue("green start: $green", green > 600)
        assertTrue("black end: $black", black > 600)
        // The attribution moves into the card's footer: grey words there, no dark pill over the map at the top right.
        val credit = count(frame, layout.attribution) { brightness(it) < 200 }
        assertTrue("attribution in the footer: $credit", credit > 20)
        val pill = VideoTestTrip.count(frame, W - (120 * unit).toInt(), 0, W, (24 * unit).toInt()) { brightness(it) < 120 }
        assertEquals("no attribution pill on the outro", 0, pill)
    }

    /** The OUTRO frame in the middle of the scene (card fully in, no fade) at [width]×[height], with the outro camera. */
    private fun outroFrame(width: Int, height: Int): Bitmap {
        val aspect = if (width > height) Aspect.LANDSCAPE else Aspect.PORTRAIT
        val opts = options.copy(aspect = aspect)
        val plans = VideoTimeline.plan(inputs.timeline, inputs.photos, opts, inputs.dayStarts)
        val cameras = camerasFor(plans, width, height)
        val plan = plans.filter { it.kind == SceneKind.OUTRO }.minBy { abs(it.sceneProgress - 0.5f) }
        assertEquals(0f, plan.fade)
        return runBlocking {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { renderer(opts, width = width, height = height).render(plan, cameras[plan.index], Canvas(it)) }
        }
    }

    private fun assertWholeRouteAboveTheCard(width: Int, height: Int) {
        val frame = outroFrame(width, height)
        val card = FrameRenderer.outroCard(inputs, width, height)
        val above = VideoTestTrip.count(frame, 0, 0, width, card.y.toInt()) { isOrange(it) }
        val inside = VideoTestTrip.count(
            frame, card.x.toInt(), card.y.toInt(), (card.x + card.w).toInt(), (card.y + card.h).toInt(),
        ) { isOrange(it) }
        assertTrue("${width}x$height route above the card: $above", above > 1_000)
        assertEquals("${width}x$height route under the card", 0, inside)
    }

    @Test
    fun outroCardLeavesTheWholeRouteAboveItPortrait() = assertWholeRouteAboveTheCard(W, H)

    @Test
    fun outroCardLeavesTheWholeRouteAboveItLandscape() = assertWholeRouteAboveTheCard(H, W)

    /** Review Focus 5: at the 720p fallback (and the other sizes) the card and the band fit and nothing in them touches. */
    @Test
    fun layoutFitsAt720() {
        fun inside(box: Box, outer: Box) =
            box.x >= outer.x && box.y >= outer.y && box.x + box.w <= outer.x + outer.w + 0.01f && box.y + box.h <= outer.y + outer.h + 0.01f
        fun overlap(a: Box, b: Box) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
        fun assertApart(name: String, parts: List<Box>) {
            for (i in parts.indices) for (j in i + 1 until parts.size) assertTrue("$name: parts $i and $j overlap", !overlap(parts[i], parts[j]))
        }
        for ((w, h) in listOf(720 to 1280, 1280 to 720, 1080 to 1920, 1920 to 1080)) {
            val screen = Box(0f, 0f, w.toFloat(), h.toFloat())
            val outro = FrameLayout.outro(w, h, 6)
            assertTrue("${w}x$h card on screen", inside(outro.card, screen))
            outro.parts.forEach { assertTrue("${w}x$h part in the card", inside(it, outro.card)) }
            assertApart("${w}x$h card", outro.parts)
            for (cells in 2..4) {
                val hud = FrameLayout.hud(w, h, cells)
                hud.parts.forEach { assertTrue("${w}x$h band part on screen", inside(it, screen)) }
                assertApart("${w}x$h band of $cells", hud.parts)
                assertTrue("${w}x$h gradient above the graph", hud.bandTop < hud.graph.y)
                if (w > h) {
                    // One row: the graph right of every cell, level with the values, the band within 27 %.
                    assertTrue("${w}x$h graph right of the cells", (hud.labels + hud.values).all { it.x + it.w <= hud.graph.x })
                    assertTrue("${w}x$h graph on the values' row", hud.graph.y >= hud.labels.first().y && hud.graph.y + hud.graph.h <= h)
                    assertTrue("${w}x$h band ${h - hud.bandTop} within 27 %", h - hud.bandTop <= h * FrameStyle.HUD_SHARE + 0.5f)
                } else {
                    assertTrue("${w}x$h graph above the cells", hud.graph.y + hud.graph.h <= hud.labels.first().y)
                }
            }
        }
    }

    // ---- frames and snapshots ----

    @Test
    fun cancellingARenderFromAnotherThreadIsSafe() {
        val renderer = renderer()
        val rides = plans.filter { it.kind == SceneKind.RIDE }
        renderer.frame(rides.first()) // the style and the snapshotter are ready
        // Cancel while a snapshot is under way, from this (instrumentation) thread: MapLibre must only be touched on Main.
        for (k in 1..3) {
            val plan = rides[rides.size * k / 4]
            val job = CoroutineScope(Dispatchers.Default).launch {
                renderer.render(plan, cameras[plan.index], Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
            }
            Thread.sleep(80)
            job.cancel()
            runBlocking { job.join() }
        }
        // Still usable afterwards.
        val plan = planOf(SceneKind.RIDE, 0.25f)
        val frame = renderer.frame(plan)
        val m = markerOf(plan)
        assertTrue(VideoTestTrip.count(frame, (m.x - 100).toInt(), (m.y + 40).toInt(), (m.x + 100).toInt(), (m.y + 140).toInt()) { isOrange(it) } > 300)
    }

    /** Ruling C2: only the outro's hold (its camera at rest) reuses the map; the overview, photo and rise frames move. */
    @Test
    fun reusedFramesTakeOneSnapshot() {
        val renderer = renderer()
        plans.filter { it.kind == SceneKind.INTRO_OVERVIEW }.take(3).forEach { renderer.frame(it) }
        assertEquals(3, renderer.snapshotsTaken)

        plans.filter { it.kind == SceneKind.PHOTO }.take(3).forEach { renderer.frame(it) }
        assertEquals(6, renderer.snapshotsTaken)

        val outro = plans.filter { it.kind == SceneKind.OUTRO }
        outro.subList(VideoTimeline.OUTRO_RISE_FRAMES - 3, VideoTimeline.OUTRO_RISE_FRAMES).forEach { renderer.frame(it) }
        assertEquals("rising frames move", 9, renderer.snapshotsTaken)
        val hold = outro.drop(VideoTimeline.OUTRO_RISE_FRAMES).take(8)
        assertTrue(hold.all { it.reuseMap })
        hold.forEach { renderer.frame(it) }
        // The last rise frame ends exactly on the outro camera, so the whole hold reuses its map.
        assertEquals(cameras[hold.first().index], cameras[outro[VideoTimeline.OUTRO_RISE_FRAMES - 1].index])
        assertEquals("the hold reuses the map", 9, renderer.snapshotsTaken)
    }

    @Test
    fun outroFadesToBlack() {
        val last = renderer().frame(plans.last())

        assertEquals(1f, plans.last().fade)
        val black = VideoTestTrip.count(last, 0, 0, W, H) { VideoTestTrip.near(it, Color.BLACK, 2) }
        assertEquals(W * H, black)
    }

    /**
     * For looking at (spec §5.2), only when asked: `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`. The
     * frames go to the app's cache (`run-as … cat cache/v131-*.png`), never to shared storage.
     */
    @Test
    fun screenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        val dir = context.cacheDir
        fun save(name: String, frame: Bitmap) = File(dir, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val renderer = renderer()
        val shots = listOf(
            "v131-intro" to nth(SceneKind.INTRO_OVERVIEW, 0),
            "v131-descent" to planOf(SceneKind.INTRO_DESCENT, 0.5f),
            "v131-ride" to planOf(SceneKind.RIDE, 0.25f),
            "v131-photo" to nth(SceneKind.PHOTO, 29),
            "v131-outro" to nth(SceneKind.OUTRO, 50),
        )
        for ((name, plan) in shots) save(name, renderer.frame(plan))
        // Landscape: the one-row band and the three-column card on a short frame.
        val wide = options.copy(aspect = Aspect.LANDSCAPE)
        val widePlans = VideoTimeline.plan(inputs.timeline, inputs.photos, wide, inputs.dayStarts)
        val wideCameras = camerasFor(widePlans, H, W)
        val wideRenderer = renderer(wide, width = H, height = W)
        val wideShots = listOf(
            "v131-intro-land" to nth(SceneKind.INTRO_OVERVIEW, 0, widePlans),
            "v131-ride-land" to planOf(SceneKind.RIDE, 0.5f, widePlans),
            "v131-outro-land" to nth(SceneKind.OUTRO, 50, widePlans),
        )
        for ((name, plan) in wideShots) {
            val frame = Bitmap.createBitmap(H, W, Bitmap.Config.ARGB_8888)
            runBlocking { wideRenderer.render(plan, wideCameras[plan.index], Canvas(frame)) }
            save(name, frame)
        }
    }

    private companion object {
        const val W = 1080
        const val H = 1920
        const val TAG = "FrameRendererTest"

        /**
         * The ridden route shows at least this much orange above the band: in this frame about 120 px of it between the
         * current point and the band, 7–9u (21–27 px) wide, plus the current point (3857 measured). (V1.3.0 drew none:
         * its route was the day colour #E53935.) The glow itself is pinned by [tailWidensToNineUnitsAndTheRouteGlows].
         */
        const val MIN_ORANGE = 2_500
    }
}

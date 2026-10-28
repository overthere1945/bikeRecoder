package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

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
    private fun camerasFor(plans: List<FramePlan>, width: Int, height: Int, videoInputs: VideoInputs = inputs): List<Camera> {
        val points = videoInputs.timeline.points.map { it.point }
        return CameraPath.cameras(
            plans, videoInputs.timeline, CameraPath.overview(points, width, height),
            FrameRenderer.outroCamera(videoInputs, width, height), heightPx = height,
        )
    }

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
        Log.i(TAG, "ride frame orange pixels above the band: $orange (V1.3.0: $V130_ORANGE)")
        assertTrue("orange $orange vs V1.3.0 $V130_ORANGE", orange >= 2 * V130_ORANGE)
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

    // ---- the title card (spec §3.5) ----

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
        assertTrue("the hold reuses one map: ${renderer.snapshotsTaken}", renderer.snapshotsTaken <= 10)
    }

    @Test
    fun outroFadesToBlack() {
        val last = renderer().frame(plans.last())

        assertEquals(1f, plans.last().fade)
        val black = VideoTestTrip.count(last, 0, 0, W, H) { VideoTestTrip.near(it, Color.BLACK, 2) }
        assertEquals(W * H, black)
    }

    /** For looking at (spec §5.2): the app's external files dir, pulled by hand and then deleted. */
    @Test
    fun screenshots() {
        val renderer = renderer()
        val shots = listOf(
            "v131-intro" to nth(SceneKind.INTRO_OVERVIEW, 0),
            "v131-descent" to planOf(SceneKind.INTRO_DESCENT, 0.5f),
            "v131-ride" to planOf(SceneKind.RIDE, 0.25f),
            "v131-photo" to nth(SceneKind.PHOTO, 29),
            "v131-outro" to nth(SceneKind.OUTRO, 50),
        )
        val dir = context.getExternalFilesDir(null)!!
        for ((name, plan) in shots) {
            val frame = renderer.frame(plan)
            File(dir, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Landscape: the band and the card on a short frame.
        val wide = options.copy(aspect = Aspect.LANDSCAPE)
        val widePlans = VideoTimeline.plan(inputs.timeline, inputs.photos, wide, inputs.dayStarts)
        val wideCameras = camerasFor(widePlans, H, W)
        val wideRenderer = renderer(wide, width = H, height = W)
        for ((name, plan) in listOf("v131-ride-landscape" to planOf(SceneKind.RIDE, 0.5f, widePlans), "v131-outro-landscape" to nth(SceneKind.OUTRO, 50, widePlans))) {
            val frame = Bitmap.createBitmap(H, W, Bitmap.Config.ARGB_8888)
            runBlocking { wideRenderer.render(plan, wideCameras[plan.index], Canvas(frame)) }
            File(dir, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private companion object {
        const val W = 1080
        const val H = 1920
        const val TAG = "FrameRendererTest"

        /**
         * Orange (#FF7A1A ±24) pixels above the band in the same ride frame drawn by the V1.3.0 renderer, measured in the
         * RED run of 2026-10-28 on the S22 over the satellite map: none, as V1.3.0 drew the route in its day colour
         * (#E53935). Hence also [MIN_ORANGE].
         */
        const val V130_ORANGE = 0

        /**
         * Whatever the baseline, the ridden route shows at least this much orange above the band: in this frame about
         * 120 px of it between the current point and the band, 7–9u (21–27 px) wide, plus the current point (3980 measured).
         */
        const val MIN_ORANGE = 2_500
    }
}

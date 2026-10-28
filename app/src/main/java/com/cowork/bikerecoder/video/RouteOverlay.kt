package com.cowork.bikerecoder.video

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.core.video.RideTimeline
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** How [RouteOverlay.draw] draws the route (spec §3.1, §3.5). */
enum class RouteStyle {
    /** A ride frame: the stretch ridden glowing, its last [FrameStyle.TAIL_M] (the tail) brighter, the rest faint white. */
    RIDE,

    /** The title card: the whole route as ridden, [FrameStyle.TITLE_ROUTE_SCALE] as wide. */
    TITLE,

    /** The closing frames and the summary image: the whole route as ridden. */
    FULL,
}

/**
 * The route drawn over a map snapshot (spec §3.1): the stretch ridden in three layers (a soft glow in its point's
 * colour, a white casing, the colour line), the last 150 m before the current point thicker with a brighter glow, and
 * the rest a thin faint white line, in units of [unit] pixels. Positions come from the snapshot's own projection
 * ([MapSnapshots.Shot.project]), so the line sits exactly on the tilted 3D map. The glow is a blur mask filter: draw
 * on a software Canvas only (ruling V7).
 *
 * Only what the camera can see is projected: points far from the view are skipped, and on a tilted camera the route is
 * cut where it passes behind the camera (a perspective projection of such points is meaningless). [colors] has one
 * colour per timeline point: the colour of the stretch from that point to the next.
 */
internal class RouteOverlay(timeline: RideTimeline, private val colors: IntArray) {

    private val points = timeline.points
    private val segment = IntArray(points.size) { points[it].segmentIndex }
    private val lat0 = if (points.isEmpty()) 0.0 else points.sumOf { it.point.lat } / points.size
    private val lon0 = if (points.isEmpty()) 0.0 else points.sumOf { it.point.lon } / points.size
    private val cos0 = cos(Math.toRadians(lat0))

    /** Local plane, metres east / north of (lat0, lon0): good enough to decide what is near the camera. */
    private val xs = DoubleArray(points.size) { (points[it].point.lon - lon0) * M_PER_DEG * cos0 }
    private val ys = DoubleArray(points.size) { (points[it].point.lat - lat0) * M_PER_DEG }

    /** Metres along the route to each point in the local plane (a move between segments counts 0), for the tail. */
    private val along = DoubleArray(points.size).also { d ->
        for (i in 1 until points.size) {
            d[i] = d[i - 1] + if (segment[i] == segment[i - 1]) hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) else 0.0
        }
    }

    init {
        require(colors.size == points.size) { "${colors.size} colours for ${points.size} points" }
    }

    /** A connected visible stretch: [s] = position along the timeline (point index + fraction), [xy] = pixels. */
    class Run(val s: DoubleArray, val xy: FloatArray)

    /** The visible route on one snapshot, and the pixels of the extra points asked for (x0, y0, x1, y1, …). */
    class Projected(val runs: List<Run>, val extras: FloatArray)

    /** Projects the visible route and [extras] onto [shot], taken with [camera]. */
    suspend fun project(shot: MapSnapshots.Shot, camera: Camera, extras: List<GeoPoint> = emptyList()): Projected {
        val view = View(camera, shot.bitmap.width, shot.bitmap.height)
        val runs = visibleRuns(view)
        val geo = ArrayList<GeoPoint>(runs.sumOf { it.size } + extras.size)
        runs.forEach { run -> run.forEach { geo += pointAt(it) } }
        geo += extras
        val pixels = shot.project(geo)
        var k = 0
        val projected = runs.map { run ->
            val xy = FloatArray(run.size * 2)
            for (i in run.indices) {
                xy[2 * i] = pixels[k++]
                xy[2 * i + 1] = pixels[k++]
            }
            decimate(run.toDoubleArray(), xy)
        }
        return Projected(projected, pixels.copyOfRange(2 * (geo.size - extras.size), pixels.size))
    }

    /**
     * Draws [projected] in [style]. For [RouteStyle.RIDE] the route is ridden up to position [doneUntil] (point index +
     * fraction) and [split] is the pixel where ridden and faint meet (the current point), if known; the other styles
     * draw it all as ridden.
     */
    fun draw(canvas: Canvas, projected: Projected, doneUntil: Double, unit: Float, split: Pair<Float, Float>?, style: RouteStyle) {
        val until = if (style == RouteStyle.RIDE) doneUntil else Double.POSITIVE_INFINITY
        val tailFrom = if (style == RouteStyle.RIDE) positionAt(distanceAt(until) - FrameStyle.TAIL_M) else until
        val done = HashMap<Int, Pen>()
        // The tail in steps, so its line widens smoothly towards the current point.
        val tail = List(TAIL_STEPS) { HashMap<Int, Pen>() }
        val left = HashMap<Int, Pen>()
        fun tailStep(k: Int) = tailFrom + (until - tailFrom) * k / TAIL_STEPS
        for (run in projected.runs) {
            for (j in 0 until run.s.size - 1) {
                val s0 = run.s[j]
                val s1 = run.s[j + 1]
                val color = colors[floor(s0).toInt().coerceIn(0, colors.lastIndex)]
                val x0 = run.xy[2 * j]
                val y0 = run.xy[2 * j + 1]
                val x1 = run.xy[2 * j + 2]
                val y1 = run.xy[2 * j + 3]
                /** The pixel at position [s] on this edge; the current point's own pixel at [until]. */
                fun xAt(s: Double) = if (s == until && split != null) split.first else x0 + (x1 - x0) * ((s - s0) / (s1 - s0)).toFloat()
                fun yAt(s: Double) = if (s == until && split != null) split.second else y0 + (y1 - y0) * ((s - s0) / (s1 - s0)).toFloat()
                fun piece(target: HashMap<Int, Pen>, from: Double, to: Double) {
                    if (to <= from) return
                    val ax = if (from == s0) x0 else xAt(from)
                    val ay = if (from == s0) y0 else yAt(from)
                    val bx = if (to == s1) x1 else xAt(to)
                    val by = if (to == s1) y1 else yAt(to)
                    target.getOrPut(color) { Pen() }.line(ax, ay, bx, by)
                }
                piece(done, s0, min(s1, tailFrom))
                if (style == RouteStyle.RIDE && s1 > tailFrom && s0 < until) {
                    for (k in 0 until TAIL_STEPS) piece(tail[k], max(s0, tailStep(k)), min(s1, if (k == TAIL_STEPS - 1) until else tailStep(k + 1)))
                }
                piece(left, max(s0, until), s1)
            }
        }

        val scale = if (style == RouteStyle.TITLE) FrameStyle.TITLE_ROUTE_SCALE else 1f
        val u = unit * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // The rest of the way: thin, faint white.
        paint.strokeWidth = FrameStyle.LEFT_WIDTH * u
        paint.color = Color.WHITE
        paint.alpha = (FrameStyle.LEFT_ALPHA * 255).roundToInt()
        left.values.forEach { canvas.drawPath(it.path, paint) }
        // The glow under it all, in each stretch's own colour (heart-rate bands glow in theirs).
        paint.strokeWidth = FrameStyle.GLOW_WIDTH * u
        paint.maskFilter = BlurMaskFilter(FrameStyle.GLOW_BLUR * u, BlurMaskFilter.Blur.NORMAL)
        fun glow(pens: Map<Int, Pen>, alpha: Float) = pens.forEach { (color, pen) ->
            paint.color = color
            paint.alpha = (alpha * 255).roundToInt()
            canvas.drawPath(pen.path, paint)
        }
        glow(done, FrameStyle.GLOW_ALPHA)
        // One glow for the whole tail: overlapping blurred steps would leave bright beads where they meet.
        val tailGlow = HashMap<Int, Pen>()
        tail.forEach { step -> step.forEach { (color, pen) -> tailGlow.getOrPut(color) { Pen() }.path.addPath(pen.path) } }
        glow(tailGlow, FrameStyle.TAIL_GLOW_ALPHA)
        paint.maskFilter = null
        // The white casing (the same 1.5u border on each side all along), then the colour line, 7u widening to 9u
        // over the tail.
        val border = FrameStyle.CASING_WIDTH - FrameStyle.LINE_WIDTH
        fun tailWidth(k: Int) = FrameStyle.LINE_WIDTH + (FrameStyle.TAIL_WIDTH - FrameStyle.LINE_WIDTH) * (k + 1f) / TAIL_STEPS
        paint.color = Color.WHITE
        paint.strokeWidth = FrameStyle.CASING_WIDTH * u
        done.values.forEach { canvas.drawPath(it.path, paint) }
        tail.forEachIndexed { k, step ->
            paint.strokeWidth = (tailWidth(k) + border) * u
            step.values.forEach { canvas.drawPath(it.path, paint) }
        }
        fun line(pens: Map<Int, Pen>, width: Float) = pens.forEach { (color, pen) ->
            paint.strokeWidth = width * u
            paint.color = color
            canvas.drawPath(pen.path, paint)
        }
        line(done, FrameStyle.LINE_WIDTH)
        tail.forEachIndexed { k, step -> line(step, tailWidth(k)) }
    }

    /** A path drawn piece by piece: a piece that starts where the last ended continues it. */
    private class Pen {
        val path = Path()
        private var x = Float.NaN
        private var y = Float.NaN

        fun line(ax: Float, ay: Float, bx: Float, by: Float) {
            if (ax != x || ay != y) path.moveTo(ax, ay)
            path.lineTo(bx, by)
            x = bx
            y = by
        }
    }

    /** Metres along the route at position [s] (point index + fraction). */
    private fun distanceAt(s: Double): Double {
        if (points.isEmpty()) return 0.0
        if (s >= points.lastIndex) return along.last()
        val i = floor(s).toInt().coerceAtLeast(0)
        return along[i] + (along[i + 1] - along[i]) * (s - i)
    }

    /** The position (point index + fraction) [metres] along the route; 0 before the start. */
    private fun positionAt(metres: Double): Double {
        if (points.isEmpty() || metres <= 0.0) return 0.0
        var lo = 0
        var hi = points.lastIndex
        if (metres >= along[hi]) return hi.toDouble()
        // The last point at or before [metres].
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (along[mid] <= metres) lo = mid else hi = mid - 1
        }
        val step = along[lo + 1] - along[lo]
        return if (step <= 0.0) lo.toDouble() else lo + (metres - along[lo]) / step
    }

    /** Colour of the stretch at position [s]. */
    fun colorAt(s: Double): Int = colors[floor(s).toInt().coerceIn(0, colors.lastIndex)]

    private fun pointAt(s: Double): GeoPoint {
        val i = floor(s).toInt().coerceIn(0, points.lastIndex)
        val f = s - i
        if (f <= 0.0 || i == points.lastIndex) return points[i].point
        val a = points[i].point
        val b = points[i + 1].point
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** What a camera sees, in the local plane: its centre, forward direction, reach and where "behind it" begins. */
    private inner class View(camera: Camera, width: Int, height: Int) {
        val cx = (camera.center.lon - lon0) * M_PER_DEG * cos0
        val cy = (camera.center.lat - lat0) * M_PER_DEG
        private val bearing = Math.toRadians(camera.bearing)
        val fx = sin(bearing)
        val fy = cos(bearing)
        private val tilt = Math.toRadians(camera.tilt)
        private val mpp = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(camera.center.lat)) / (TILE_SIZE * 2.0.pow(camera.zoom))

        /** MapLibre's camera stands 1.5 × the view height (pixels) from the centre (field of view 2·atan(1/3)). */
        private val cameraDistanceM = CAMERA_DISTANCE_PER_HEIGHT * height * mpp
        private val behindM = sin(tilt) * cameraDistanceM
        private val heightM = cos(tilt) * cameraDistanceM
        private val farM = heightM * tan(min(tilt + HALF_FOV, MAX_RAY)) - behindM

        /** Generous: anything beyond is off the picture. */
        val reachM = farM + behindM + (width + height) * mpp

        /** Forward distance below which a point is (nearly) under or behind the camera; none on a flat camera. */
        val nearM = if (camera.tilt < MIN_TILT_DEG) Double.NEGATIVE_INFINITY else -NEAR_SHARE * behindM

        fun forward(i: Int) = (xs[i] - cx) * fx + (ys[i] - cy) * fy
    }

    /** Runs of positions (point index + fraction) along the visible route. */
    private fun visibleRuns(view: View): List<MutableList<Double>> {
        val runs = ArrayList<MutableList<Double>>()
        var run: MutableList<Double>? = null
        for (i in 0 until points.size - 1) {
            val keep = segment[i] == segment[i + 1] && near(view, i)
            if (!keep) {
                run = null
                continue
            }
            val fa = view.forward(i)
            val fb = view.forward(i + 1)
            if (fa < view.nearM && fb < view.nearM) {
                run = null
                continue
            }
            val start = if (fa >= view.nearM) i.toDouble() else i + (view.nearM - fa) / (fb - fa)
            val end = if (fb >= view.nearM) (i + 1).toDouble() else i + (view.nearM - fa) / (fb - fa)
            val current = run ?: mutableListOf<Double>().also { runs += it }
            if (current.isEmpty() || current.last() != start) current += start
            current += end
            run = if (end == (i + 1).toDouble()) current else null
        }
        return runs.filter { it.size >= 2 }
    }

    /** Whether the stretch i → i+1 comes within the view's reach of its centre. */
    private fun near(view: View, i: Int): Boolean {
        val ax = xs[i] - view.cx
        val ay = ys[i] - view.cy
        val dx = xs[i + 1] - xs[i]
        val dy = ys[i + 1] - ys[i]
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + dx * t, ay + dy * t) <= view.reachM
    }

    /** Drops vertices less than [MIN_STEP_PX] from the last kept one (not the last, nor where the colour changes). */
    private fun decimate(s: DoubleArray, xy: FloatArray): Run {
        val keepS = ArrayList<Double>(s.size)
        val keepXy = ArrayList<Float>(xy.size)
        for (i in s.indices) {
            val last = keepS.size - 1
            val drop = last >= 0 && i < s.lastIndex &&
                colorAt(keepS[last]) == colorAt(s[i]) &&
                hypot(xy[2 * i] - keepXy[2 * last], xy[2 * i + 1] - keepXy[2 * last + 1]) < MIN_STEP_PX
            if (drop) continue
            keepS += s[i]
            keepXy += xy[2 * i]
            keepXy += xy[2 * i + 1]
        }
        return Run(keepS.toDoubleArray(), keepXy.toFloatArray())
    }

    companion object {
        private const val TAIL_STEPS = 6
        private const val M_PER_DEG = 111_195.0
        private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
        private const val TILE_SIZE = 512.0
        private const val CAMERA_DISTANCE_PER_HEIGHT = 1.5
        private const val HALF_FOV = 0.32175 // atan(1/3)
        private const val MAX_RAY = 85.0 * PI / 180
        private const val MIN_TILT_DEG = 1.0
        private const val NEAR_SHARE = 0.75
        private const val MIN_STEP_PX = 1.5f
    }
}

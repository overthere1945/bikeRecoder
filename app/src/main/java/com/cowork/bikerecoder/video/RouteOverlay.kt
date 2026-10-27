package com.cowork.bikerecoder.video

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
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * The route drawn over a map snapshot (spec §2.2): the part ridden as a solid line (width 6, its point's colour) and
 * the rest faint (width 4, opacity 0.35), in units of [unit] pixels. Positions come from the snapshot's own
 * projection ([MapSnapshots.Shot.project]), so the line sits exactly on the tilted 3D map.
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
     * Draws [projected]: up to position [doneUntil] (point index + fraction; [Double.POSITIVE_INFINITY] for all) solid,
     * after it faint. [split] is the pixel where they meet (the current point), if known.
     */
    fun draw(canvas: Canvas, projected: Projected, doneUntil: Double, unit: Float, split: Pair<Float, Float>? = null) {
        val done = HashMap<Int, Path>()
        val left = HashMap<Int, Path>()
        for (run in projected.runs) {
            var lastPath: Path? = null
            for (j in 0 until run.s.size - 1) {
                val s0 = run.s[j]
                val s1 = run.s[j + 1]
                val color = colors[floor(s0).toInt().coerceIn(0, colors.lastIndex)]
                val x0 = run.xy[2 * j]
                val y0 = run.xy[2 * j + 1]
                val x1 = run.xy[2 * j + 2]
                val y1 = run.xy[2 * j + 3]
                fun edge(target: HashMap<Int, Path>, ax: Float, ay: Float, bx: Float, by: Float) {
                    val path = target.getOrPut(color) { Path() }
                    if (path !== lastPath) path.moveTo(ax, ay)
                    path.lineTo(bx, by)
                    lastPath = path
                }
                when {
                    s1 <= doneUntil -> edge(done, x0, y0, x1, y1)
                    s0 >= doneUntil -> edge(left, x0, y0, x1, y1)
                    else -> {
                        val f = ((doneUntil - s0) / (s1 - s0)).toFloat()
                        val mx = split?.first ?: (x0 + (x1 - x0) * f)
                        val my = split?.second ?: (y0 + (y1 - y0) * f)
                        edge(done, x0, y0, mx, my)
                        lastPath = null
                        edge(left, mx, my, x1, y1)
                    }
                }
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        paint.strokeWidth = LEFT_WIDTH * unit
        left.forEach { (color, path) ->
            paint.color = color
            paint.alpha = (LEFT_OPACITY * 255).toInt()
            canvas.drawPath(path, paint)
        }
        paint.strokeWidth = (DONE_WIDTH + 2) * unit
        paint.color = CASING
        done.values.forEach { canvas.drawPath(it, paint) }
        paint.strokeWidth = DONE_WIDTH * unit
        done.forEach { (color, path) ->
            paint.color = color
            canvas.drawPath(path, paint)
        }
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
        const val DONE_WIDTH = 6f
        const val LEFT_WIDTH = 4f
        const val LEFT_OPACITY = 0.35f
        private val CASING = Color.argb(70, 0, 0, 0)

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

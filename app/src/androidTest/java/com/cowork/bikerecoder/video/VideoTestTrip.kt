package com.cowork.bikerecoder.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.video.PhotoCue
import com.cowork.bikerecoder.core.video.RideTimelineBuilder
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A test trip around Yeouido, Seoul (about 1.85 km, 5 m/s, one point a second) with one photo and heart rate. */
object VideoTestTrip {

    /** The generated photo's colour, unlike any map or route colour. */
    const val PHOTO_COLOR: Int = 0xFF00E5FF.toInt()

    private val WAYPOINTS = listOf(
        GeoPoint(37.5262, 126.9290),
        GeoPoint(37.5290, 126.9370),
        GeoPoint(37.5318, 126.9420),
        GeoPoint(37.5290, 126.9470),
    )
    /** 2026-10-25 09:00 Seoul, the first point. */
    val START: Long = LocalDate.of(2026, 10, 25).atTime(9, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    /** The track: one point a second at 5 m/s through [WAYPOINTS], over a 35 m hill ([withAltitude]) or without heights. */
    fun points(withAltitude: Boolean = true): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var t = START
        for (leg in 0 until WAYPOINTS.lastIndex) {
            val a = WAYPOINTS[leg]
            val b = WAYPOINTS[leg + 1]
            val steps = max(1, (com.cowork.bikerecoder.core.model.GeoMath.distanceM(a, b) / 5.0).roundToInt())
            for (k in 0 until steps) {
                val f = k.toDouble() / steps
                out += TrackPoint(t, GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f), null, 5f, 5f)
                t += 1_000
            }
        }
        out += TrackPoint(t, WAYPOINTS.last(), null, 5f, 5f)
        if (!withAltitude) return out
        return out.mapIndexed { i, p -> p.copy(altitudeM = 20.0 + 35.0 * sin(PI * i / out.lastIndex)) }
    }

    /** [withPhoto]: one photo at 40 % of the ride; [withHeartRate]: a sample every 10 s; [withAltitude]: heights on the track. */
    fun inputs(withPhoto: Boolean = true, withHeartRate: Boolean = true, withAltitude: Boolean = true): VideoInputs {
        val track = points(withAltitude)
        val totals = SegmentTotals(1_850.0, track.last().timeMillis - START, track.last().timeMillis - START, 6.5, 24.0, 20.0, 20.0, 49.0)
        val segment = RideSegment(1, 1, START, track.last().timeMillis, LocalDate.of(2026, 10, 25), totals)
        val timeline = RideTimelineBuilder.build(listOf(segment to track), ZoneId.of("Asia/Seoul"))
        val photoAt = track[track.size * 2 / 5].timeMillis
        val heart = if (withHeartRate) (track.first().timeMillis..track.last().timeMillis step 10_000).mapIndexed { i, time ->
            HeartRateSample(time, 100 + (i * 7) % 70)
        } else emptyList()
        return VideoInputs(
            tripId = 1,
            timeline = timeline,
            photos = if (withPhoto) listOf(PhotoCue(1, timeline.moveMsAtTime(photoAt)!!, photoAt)) else emptyList(),
            heartRate = heart,
            dayStarts = emptyList(),
            segmentColors = listOf(RideColors.colorAt(0)),
            title = "10월 25일 (일)",
            rideTitle = "아침 라이딩",
            totals = TripTotals(totals.distanceM, totals.movingTimeMs, totals.avgSpeedMps, totals.maxSpeedMps, totals.ascentM,
                avgBpm = if (withHeartRate) 132 else null, calories = if (withHeartRate) 210.0 else null),
            canColorByHeart = withHeartRate,
            firstStartedAt = START,
        )
    }

    /** A 1600×1200 photo: [PHOTO_COLOR] with a darker frame, so the middle is plainly the photo. */
    fun photo(): Bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(PHOTO_COLOR)
        val frame = Paint().apply { color = Color.rgb(0, 96, 100); style = Paint.Style.STROKE; strokeWidth = 80f }
        canvas.drawRect(0f, 0f, 1600f, 1200f, frame)
    }

    /** Whether [actual] is within [tolerance] of [expected] in each of R, G and B. */
    fun near(actual: Int, expected: Int, tolerance: Int = 40): Boolean =
        abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance

    /** How many pixels of [bitmap] in the rectangle satisfy [match]. */
    fun count(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, match: (Int) -> Boolean): Int {
        var n = 0
        for (y in top until bottom) for (x in left until right) if (match(bitmap.getPixel(x, y))) n++
        return n
    }
}

package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.SummaryImageLayout
import com.cowork.bikerecoder.map.TileSource
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The summary image (spec §4) in the [SummaryImageLayout] boxes: the date and badge, a top-down map of the whole route
 * (one snapshot fitted to the map box, ruling V5) in its day colours with start / end points and small circles where the
 * photos were taken, six numbers (the last is the average heart rate, or "주행 N회" without one), up to three [photos]
 * (chosen by the caller) and "bikeRecoder". Returns a software ARGB_8888 bitmap of the [Aspect] size.
 */
class SummaryImageRenderer(private val context: Context, private val tileSource: TileSource) {

    suspend fun render(inputs: VideoInputs, aspect: Aspect, photos: List<Bitmap>): Bitmap {
        val shown = photos.take(SummaryImageLayout.MAX_PHOTOS)
        val layout = SummaryImageLayout.layout(aspect, shown.size)
        val unit = min(aspect.width, aspect.height) / 360f
        val image = Bitmap.createBitmap(aspect.width, aspect.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)

        drawMap(canvas, inputs, layout.map, unit)
        drawHeader(canvas, inputs, layout.header, unit)
        stats(inputs).zip(layout.stats).forEach { (stat, box) -> drawStat(canvas, stat, box, unit) }
        shown.zip(layout.photos).forEach { (photo, box) -> drawPhoto(canvas, MapSnapshots.software(photo), box, unit) }
        val footer = layout.footer
        canvas.drawText(BRAND, footer.x + footer.w / 2, footer.y + footer.h * 0.7f, text(unit, 14f, GREY, bold = true))
        return image
    }

    private suspend fun drawMap(canvas: Canvas, inputs: VideoInputs, box: Box, unit: Float) {
        val w = box.w.roundToInt()
        val h = box.h.roundToInt()
        val timeline = inputs.timeline
        val points = timeline.points
        val camera = CameraPath.overview(points.map { it.point }, w, h)
        val colors = inputs.segmentColors.map(Color::parseColor)
        val overlay = RouteOverlay(timeline, IntArray(points.size) { colors.getOrElse(points[it].segmentIndex) { colors.last() } })
        val shot = MapSnapshots(context, tileSource, w, h).take(camera)
        val extras = listOf(points.first().point, points.last().point) + inputs.photos.map { timeline.at(it.moveMs).point }
        val projected = overlay.project(shot, camera, extras)

        canvas.save()
        canvas.translate(box.x, box.y)
        canvas.clipRect(0f, 0f, box.w, box.h)
        canvas.drawBitmap(shot.bitmap, 0f, 0f, null)
        shot.bitmap.recycle()
        overlay.draw(canvas, projected, Double.POSITIVE_INFINITY, unit)
        val xy = projected.extras
        for (i in 2 until extras.size) {
            canvas.drawCircle(xy[2 * i], xy[2 * i + 1], 5 * unit, fill(Color.WHITE))
            canvas.drawCircle(xy[2 * i], xy[2 * i + 1], 5 * unit, stroke(DARK, 1.5f * unit))
        }
        dot(canvas, xy[0], xy[1], START_COLOR, unit)
        dot(canvas, xy[2], xy[3], DARK, unit)
        canvas.drawText(tileSource.attribution, box.w - 4 * unit, box.h - 4 * unit,
            text(unit, 7f, Color.argb(200, 60, 60, 60), align = Paint.Align.RIGHT))
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, inputs: VideoInputs, box: Box, unit: Float) {
        canvas.drawText(inputs.title, box.x, box.y + box.h * 0.5f, text(unit, 26f, DARK, bold = true, align = Paint.Align.LEFT))
        canvas.drawText(inputs.badge, box.x, box.y + box.h * 0.85f, text(unit, 14f, GREY, align = Paint.Align.LEFT))
    }

    /** Distance, moving time, average speed / top speed, ascent, average heart rate or "주행 N회" (spec §4 ③). */
    private fun stats(inputs: VideoInputs): List<Pair<String, String>> {
        val t = inputs.totals
        return listOf(
            "거리" to RideFormat.distance(t.distanceM),
            "이동 시간" to RideFormat.duration(t.movingTimeMs),
            "평균 속도" to "${RideFormat.speedKmh(t.avgSpeedMps)}km/h",
            "최고 속도" to "${RideFormat.speedKmh(t.maxSpeedMps)}km/h",
            "상승 고도" to RideFormat.altitude(t.ascentM),
            t.avgBpm?.let { "평균 심박" to "${it}bpm" } ?: ("주행" to "${inputs.segmentColors.size}회"),
        )
    }

    private fun drawStat(canvas: Canvas, stat: Pair<String, String>, box: Box, unit: Float) {
        canvas.drawRoundRect(RectF(box.x, box.y, box.x + box.w, box.y + box.h), 10 * unit, 10 * unit, fill(CELL))
        val x = box.x + 10 * unit
        canvas.drawText(stat.first, x, box.y + 18 * unit, text(unit, 11f, GREY, align = Paint.Align.LEFT))
        val value = text(unit, 20f, DARK, bold = true, align = Paint.Align.LEFT)
        // A long value ("12시간 30분" in a narrow landscape cell) is shrunk to fit.
        val room = box.w - 20 * unit
        val wide = value.measureText(stat.second)
        if (wide > room) value.textSize *= room / wide
        canvas.drawText(stat.second, x, box.y + 40 * unit, value)
    }

    /** Centre-cropped into [box], rounded. */
    private fun drawPhoto(canvas: Canvas, photo: Bitmap, box: Box, unit: Float) {
        val target = RectF(box.x, box.y, box.x + box.w, box.y + box.h)
        val scale = maxOf(box.w / photo.width, box.h / photo.height)
        val cropW = (box.w / scale).roundToInt().coerceAtMost(photo.width)
        val cropH = (box.h / scale).roundToInt().coerceAtMost(photo.height)
        val left = (photo.width - cropW) / 2
        val top = (photo.height - cropH) / 2
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(target, 10 * unit, 10 * unit, Path.Direction.CW) })
        canvas.drawBitmap(photo, Rect(left, top, left + cropW, top + cropH), target, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, color: Int, unit: Float) {
        canvas.drawCircle(x, y, 8 * unit, fill(Color.WHITE))
        canvas.drawCircle(x, y, 5.5f * unit, fill(color))
    }

    private fun text(unit: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * unit
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private companion object {
        const val BRAND = "bikeRecoder"
        val DARK = Color.rgb(33, 33, 33)
        val GREY = Color.rgb(117, 117, 117)
        val CELL = Color.rgb(245, 245, 245)
        val START_COLOR = Color.rgb(67, 160, 71)
    }
}

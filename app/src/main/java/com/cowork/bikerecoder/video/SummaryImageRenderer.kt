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
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.SummaryImageLayout
import com.cowork.bikerecoder.map.TileSource
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The summary image (spec §3.9) in the [SummaryImageLayout] boxes: the date and the ride's automatic title (§3.8), a
 * top-down map of the whole route (one snapshot fitted to the map box, ruling V5) on the [tileSource]'s map (the
 * satellite style, or the plain map when that is not to be had) with the route glowing orange (§3.1), the start and end
 * points (§3.2) and small circles where the photos were taken; the map's attribution in one line (§1.1) at the top
 * right; six numbers (the last is the average heart rate, or "주행 N회" without one), up to three [photos] (chosen by
 * the caller) and "bikeRecoder". Returns a software ARGB_8888 bitmap of the [Aspect] size.
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
        drawAttribution(canvas, layout.header, aspect.width, unit)
        val stats = stats(inputs).map { (label, value) -> label to FrameRenderer.splitUnit(value) }
        // One value size for all six cells: the largest that fits every number with its smaller unit.
        val value = text(unit, 20f, DARK, bold = true, align = Paint.Align.LEFT)
        val small = text(unit, 20f * FrameStyle.CARD_UNIT_SHARE, DARK, bold = true, align = Paint.Align.LEFT)
        val shrink = stats.zip(layout.stats).minOf { (stat, box) -> min(1f, (box.w - 20 * unit) / valueWidth(stat.second, value, small, unit)) }
        value.textSize *= shrink
        small.textSize *= shrink
        stats.zip(layout.stats).forEach { (stat, box) -> drawStat(canvas, stat, box, unit, value, small) }
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
        val overlay = RouteOverlay(timeline, IntArray(points.size) { FrameStyle.ROUTE_ORANGE })
        val maps = MapSnapshots(context, tileSource, w, h)
        val shot = try {
            maps.take(camera)
        } finally {
            maps.close()
        }
        val extras = listOf(points.first().point, points.last().point) + inputs.photos.map { timeline.at(it.moveMs).point }
        val projected = overlay.project(shot, camera, extras)

        canvas.save()
        canvas.translate(box.x, box.y)
        canvas.clipRect(0f, 0f, box.w, box.h)
        canvas.drawBitmap(shot.bitmap, 0f, 0f, null)
        shot.bitmap.recycle()
        overlay.draw(canvas, projected, Double.POSITIVE_INFINITY, unit, split = null, style = RouteStyle.FULL)
        val xy = projected.extras
        for (i in 2 until extras.size) {
            canvas.drawCircle(xy[2 * i], xy[2 * i + 1], 5 * unit, fill(Color.WHITE))
            canvas.drawCircle(xy[2 * i], xy[2 * i + 1], 5 * unit, stroke(DARK, 1.5f * unit))
        }
        MapMarks.startAndEnd(canvas, xy[0], xy[1], xy[2], xy[3], unit)
        canvas.restore()
    }

    /** The date and, in grey below it, the ride's automatic title (spec §3.8), shrunk to the header's width if need be. */
    private fun drawHeader(canvas: Canvas, inputs: VideoInputs, box: Box, unit: Float) {
        val date = box.y + box.h * 0.5f
        canvas.drawText(inputs.title, box.x, date, text(unit, 26f, DARK, bold = true, align = Paint.Align.LEFT))
        val name = text(unit, 14f, GREY, align = Paint.Align.LEFT)
        val wide = name.measureText(inputs.rideTitle)
        if (wide > box.w) name.textSize *= box.w / wide
        canvas.drawText(inputs.rideTitle, box.x, date + NAME_BELOW * unit, name)
    }

    /**
     * The map's attribution in one line (spec §1.1), small in the image's top right corner, on the white above the
     * header's date: off the map, so it never tangles with the map's own names. Right-aligned with the [header] (the
     * white column in landscape) and shrunk to fit it, and at most half the [width].
     */
    private fun drawAttribution(canvas: Canvas, header: Box, width: Int, unit: Float) {
        val paint = text(unit, 9f, ATTRIBUTION_GREY, align = Paint.Align.RIGHT)
        val room = min(width / 2f, header.w)
        val wide = paint.measureText(tileSource.attribution)
        if (wide > room) paint.textSize *= room / wide
        canvas.drawText(tileSource.attribution, header.x + header.w, 14 * unit, paint)
    }

    /** A cell: its label in grey, then its number with the unit ("km/h", "bpm", …) smaller after it, as on the closing card. */
    private fun drawStat(canvas: Canvas, stat: Pair<String, Pair<String, String>>, box: Box, unit: Float, value: Paint, small: Paint) {
        canvas.drawRoundRect(RectF(box.x, box.y, box.x + box.w, box.y + box.h), 10 * unit, 10 * unit, fill(CELL))
        val x = box.x + 10 * unit
        canvas.drawText(stat.first, x, box.y + 18 * unit, text(unit, 11f, GREY, align = Paint.Align.LEFT))
        val (number, unitText) = stat.second
        val baseline = box.y + 40 * unit
        canvas.drawText(number, x, baseline, value)
        if (unitText.isNotEmpty()) canvas.drawText(unitText, x + value.measureText(number) + unit, baseline, small)
    }

    /** How wide a [number and unit][value] is drawn: the number, a one-unit gap and the smaller unit. */
    private fun valueWidth(value: Pair<String, String>, number: Paint, small: Paint, unit: Float): Float =
        number.measureText(value.first) + if (value.second.isEmpty()) 0f else unit + small.measureText(value.second)

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

    companion object {
        /**
         * Distance, moving time, average speed / top speed, ascent, and the average heart rate or "주행 N회" (spec §4 ③,
         * unchanged by §3.9), N = the target segments; the same cells as the video's closing card.
         */
        internal fun stats(inputs: VideoInputs): List<Pair<String, String>> {
            val t = inputs.totals
            return FrameRenderer.rideCells(t) + FrameRenderer.ascentCell(t) +
                FrameRenderer.heartRateOrRides(t, FrameRenderer.segmentCount(inputs))
        }

        private const val BRAND = "bikeRecoder"
        private const val DARK = 0xFF212121.toInt()
        private const val GREY = 0xFF757575.toInt()
        private const val ATTRIBUTION_GREY = 0xFF757575.toInt()
        private const val CELL = 0xFFF5F5F5.toInt()

        /** The ride's title line sits this far (units) below the date's baseline. */
        private const val NAME_BELOW = 20f
    }
}

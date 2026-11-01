package com.cowork.bikerecoder.share.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.CardSize
import com.cowork.bikerecoder.core.video.ShareCardLayout
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.ui.activity.ActivityStyle
import com.cowork.bikerecoder.video.FeedThumbnailRenderer
import com.cowork.bikerecoder.video.MapMarks
import com.cowork.bikerecoder.video.MapSnapshots
import com.cowork.bikerecoder.video.RouteOverlay
import com.cowork.bikerecoder.video.RouteStyle
import com.cowork.bikerecoder.video.TitleBadge
import com.cowork.bikerecoder.video.VideoInputs
import com.cowork.bikerecoder.video.activitiesBySegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * The share cards (spec §2). [satellite] draws the satellite card (spec §2.1, §2.2): a map snapshot of the whole card with
 * the route fitted to [ShareCardLayout.satellite]'s map box, glowing in each segment's activity colour with the green
 * start and black end points, a black 35% band on top (the badge, "title · region", the date line) and a black 50% band
 * below (the three numbers, the small line, "bikeRecoder"), and the map's attribution under the top band.
 *
 * The map is the [satellite] source (null: no key, or the start check failed), then the [plain] map (null only in tests),
 * each given [MAP_TIMEOUT_MS]; a source whose snapshot falls back to a plain background ([MapSnapshots.Shot.fallback])
 * gives no map. Without either the card is a dark [NO_MAP] fill with the route alone and no attribution.
 *
 * [photoCard] draws a photo with the route small and white in its bottom band (spec §2.3), [photoSlide] a photo alone in
 * the card's shape (spec §2.4), [sticker] the route and the numbers on a transparent square (spec §2.5); none of them
 * uses a map. Each returns a software ARGB_8888 bitmap (blur mask filters and shadow layers need software) that the
 * caller owns; the photos given must be software bitmaps too.
 */
class ShareCardRenderer(
    private val context: Context,
    private val satellite: TileSource?,
    private val plain: TileSource?,
) : CardDrawer {

    /** The satellite card (spec §2.2) of [size], [CardSize.FEED] or [CardSize.STORY]. */
    override suspend fun satellite(inputs: VideoInputs, facts: CardFacts, size: CardSize): Bitmap {
        val boxes = ShareCardLayout.satellite(size)
        val w = size.width
        val h = size.height
        val unit = min(w, h) / 360f
        val timeline = inputs.timeline
        val points = timeline.points
        val camera = CameraPath.overviewInBox(points.map { it.point }, w, h, boxes.map)
        val overlay = RouteOverlay(timeline, FeedThumbnailRenderer.lineColors(timeline, inputs.activitiesBySegment()))
        val ends = listOf(points.first().point, points.last().point)

        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            val map = snapshot(camera, w, h)
            val projected = if (map == null) {
                canvas.drawColor(NO_MAP)
                flat(inputs, camera, w, h, ends)
            } else {
                val (shot, _) = map
                try {
                    canvas.drawBitmap(shot.bitmap, 0f, 0f, null)
                    overlay.project(shot, camera, ends)
                } finally {
                    shot.bitmap.recycle()
                }
            }
            overlay.draw(canvas, projected, Double.POSITIVE_INFINITY, unit, split = null, style = RouteStyle.FULL)
            val xy = projected.extras
            MapMarks.startAndEnd(canvas, xy[0], xy[1], xy[2], xy[3], unit)

            canvas.drawRect(boxes.top.left, boxes.top.y, boxes.top.right, boxes.top.bottom, fill(Color.BLACK, TOP_BAND_ALPHA))
            canvas.drawRect(boxes.bottom.left, boxes.bottom.y, boxes.bottom.right, boxes.bottom.bottom, fill(Color.BLACK, BOTTOM_BAND_ALPHA))
            drawTitle(canvas, facts, boxes.top, unit)
            map?.second?.let { drawAttribution(canvas, it.attribution, boxes.top, unit) }
            drawNumbers(canvas, facts, boxes.bottom, size, unit)
            return image
        } catch (e: Throwable) {
            image.recycle()
            throw e
        }
    }

    /**
     * The photo card (spec §2.3) of [size], [CardSize.FEED] or [CardSize.STORY]: [photo] (upright) cropped about its centre
     * to fill the card, and a black 45% band at the bottom with the whole route small and white on its left (no map), a
     * dot in the activity's colour where the photo was taken ([takenAt]; null or outside the route square: none), and
     * right of the route the title line without the badge, the date line and the three numbers; "bikeRecoder" at the
     * bottom right.
     */
    override fun photoCard(inputs: VideoInputs, facts: CardFacts, size: CardSize, photo: Bitmap, takenAt: GeoPoint?): Bitmap {
        val boxes = ShareCardLayout.photoCard(size)
        val w = size.width
        val h = size.height
        val unit = min(w, h) / 360f
        val camera = CameraPath.overviewInBox(inputs.timeline.points.map { it.point }, w, h, boxes.map)
        val projected = flat(inputs, camera, w, h, listOfNotNull(takenAt))

        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            cover(canvas, photo, w, h)
            val band = boxes.bottom
            canvas.drawRect(band.left, band.y, band.right, band.bottom, fill(Color.BLACK, PHOTO_BAND_ALPHA))
            canvas.drawPath(path(projected), stroke(Color.WHITE, MINI_ROUTE_WIDTH * unit))
            if (takenAt != null) {
                val x = projected.extras[0]
                val y = projected.extras[1]
                // A photo geotagged off the route would put the dot over the photo: none then.
                if (x in boxes.map.left..boxes.map.right && y in boxes.map.y..boxes.map.bottom) {
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ActivityStyle.color(facts.activity).toArgb() }
                    canvas.drawCircle(x, y, TAKEN_DOT * unit, dot)
                }
            }
            drawPhotoText(canvas, facts, band, boxes.map, unit)
            return image
        } catch (e: Throwable) {
            image.recycle()
            throw e
        }
    }

    /**
     * A photo slide (spec §2.4) of [size]: a portrait [photo] (upright; height ≥ width) cropped about its centre to fill
     * it; a landscape one across the whole width, in the middle, over a blurred fill of itself (cropped to fill, scaled
     * down to 1/[BLUR_SCALE] and back up, both with filtering). No words, no marks.
     */
    override fun photoSlide(photo: Bitmap, size: CardSize): Bitmap {
        val w = size.width
        val h = size.height
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            if (photo.height >= photo.width) {
                cover(canvas, photo, w, h)
            } else {
                val blurred = blurred(photo, w, h)
                try {
                    canvas.drawBitmap(blurred, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), FILTER)
                } finally {
                    blurred.recycle()
                }
                val fit = ShareCardLayout.fitWidth(photo.width, photo.height, w, h)
                canvas.drawBitmap(photo, null, RectF(fit.left, fit.y, fit.right, fit.bottom), FILTER)
            }
            return image
        } catch (e: Throwable) {
            image.recycle()
            throw e
        }
    }

    /**
     * The sticker (spec §2.5): a transparent [CardSize.STICKER] square with the whole route in its top 60% (a white
     * 4-unit line over a black 55% 7-unit one, no start or end point) and the three numbers below, white with two black
     * shadows (3 and 6 units). Nothing else: no map, no title, no "bikeRecoder".
     */
    override fun sticker(inputs: VideoInputs, facts: CardFacts): Bitmap {
        val w = CardSize.STICKER.width
        val h = CardSize.STICKER.height
        val unit = min(w, h) / 360f
        val inset = STICKER_INSET * unit
        val routeBox = Box(inset, inset, w - 2 * inset, h * STICKER_ROUTE_SHARE - 2 * inset)
        val camera = CameraPath.overviewInBox(inputs.timeline.points.map { it.point }, w, h, routeBox)
        val route = path(flat(inputs, camera, w, h, emptyList()))

        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawPath(route, stroke(Color.BLACK, STICKER_CASING_WIDTH * unit).apply { alpha = (STICKER_CASING_ALPHA * 255).roundToInt() })
            canvas.drawPath(route, stroke(Color.WHITE, STICKER_LINE_WIDTH * unit))
            val left = PAD * unit
            val column = (w - 2 * left) / COLUMNS
            val top = h * STICKER_ROUTE_SHARE
            facts.cells.forEachIndexed { i, it ->
                cell(
                    canvas, it, left + i * column, column, top, STICKER_LABEL, STICKER_VALUE, STICKER_VALUE_SIZE, unit,
                    labelSize = STICKER_LABEL_SIZE, shadows = STICKER_SHADOWS,
                )
            }
            return image
        } catch (e: Throwable) {
            image.recycle()
            throw e
        }
    }

    /**
     * A snapshot of the whole card and its source: satellite, then plain, each within [MAP_TIMEOUT_MS]; null: no map. A
     * [MapSnapshots.Shot.fallback] (the source's style or snapshot failed, drawn over a plain background) is no map from
     * that source.
     */
    private suspend fun snapshot(camera: Camera, w: Int, h: Int): Pair<MapSnapshots.Shot, TileSource>? {
        for (source in listOfNotNull(satellite, plain)) {
            val maps = MapSnapshots(context, source, w, h)
            val shot = try {
                withTimeoutOrNull(MAP_TIMEOUT_MS) { maps.take(camera) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The message may quote a tile URL with the key (ruling K1): the type only.
                Log.w(TAG, "Card map failed: ${e.javaClass.simpleName}")
                null
            } finally {
                maps.close()
            }
            if (shot != null && !shot.fallback) return shot to source
            shot?.bitmap?.recycle()
            Log.w(TAG, "No card map from ${source.javaClass.simpleName}")
        }
        return null
    }

    /**
     * The route without a map: each segment one run, projected flat with the [camera]'s Web-Mercator maths (512-px tiles,
     * north up, no tilt), as MapLibre would have placed it on a [w]×[h] snapshot. Also the pixels of [extras].
     */
    private fun flat(inputs: VideoInputs, camera: Camera, w: Int, h: Int, extras: List<GeoPoint>): RouteOverlay.Projected {
        val world = TILE_SIZE * 2.0.pow(camera.zoom)
        val cx = mercatorX(camera.center.lon)
        val cy = mercatorY(camera.center.lat)
        fun x(p: GeoPoint) = ((mercatorX(p.lon) - cx) * world + w / 2.0).toFloat()
        fun y(p: GeoPoint) = ((mercatorY(p.lat) - cy) * world + h / 2.0).toFloat()
        val points = inputs.timeline.points
        val runs = ArrayList<RouteOverlay.Run>()
        var start = 0
        for (i in 1..points.size) {
            if (i < points.size && points[i].segmentIndex == points[start].segmentIndex) continue
            if (i - start >= 2) {
                val xy = FloatArray(2 * (i - start))
                for (k in start until i) {
                    xy[2 * (k - start)] = x(points[k].point)
                    xy[2 * (k - start) + 1] = y(points[k].point)
                }
                runs += RouteOverlay.Run(DoubleArray(i - start) { (start + it).toDouble() }, xy)
            }
            start = i
        }
        val ends = FloatArray(2 * extras.size)
        extras.forEachIndexed { i, p ->
            ends[2 * i] = x(p)
            ends[2 * i + 1] = y(p)
        }
        return RouteOverlay.Projected(runs, ends)
    }

    /** The top band: the badge and "title · region" on one line, the date line below, centred in [band] together. */
    private fun drawTitle(canvas: Canvas, facts: CardFacts, band: Box, unit: Float) {
        val left = band.x + PAD * unit
        val lineCentre = band.y + band.h / 2 - TITLE_ABOVE_CENTRE * unit
        val badge = BADGE * unit
        TitleBadge.draw(canvas, facts.activity, left, lineCentre - badge / 2, badge, 1f)
        val title = titleLine(facts)
        val titleX = left + badge + BADGE_GAP * unit
        val titlePaint = text(unit, TITLE_SIZE, Color.WHITE, bold = true)
        fit(titlePaint, title, band.x + band.w - PAD * unit - titleX)
        canvas.drawText(title, titleX, centred(titlePaint, lineCentre), titlePaint)
        val datePaint = text(unit, DATE_SIZE, white(DATE_ALPHA))
        fit(datePaint, facts.dateLine, band.w - 2 * PAD * unit)
        canvas.drawText(facts.dateLine, left, lineCentre + DATE_BELOW * unit, datePaint)
    }

    /** The map's attribution in one line right under the top band, at the right; at most half the card wide. */
    private fun drawAttribution(canvas: Canvas, attribution: String, band: Box, unit: Float) {
        val paint = text(unit, ATTRIBUTION_SIZE, white(ATTRIBUTION_ALPHA), align = Paint.Align.RIGHT)
        fit(paint, attribution, band.w / 2)
        canvas.drawText(attribution, band.x + band.w - PAD * unit, band.y + band.h + ATTRIBUTION_BELOW * unit, paint)
    }

    /**
     * The bottom band: a feed card's three cells in one row, its small line ("상승 410 m · ♥ 128") at the bottom left; a
     * story's first cell large on its own row, the other two and the ascent (when there is one) small in a row below it,
     * and only the heart rate at the bottom left (spec §2.2). "bikeRecoder" at the bottom right.
     */
    private fun drawNumbers(canvas: Canvas, facts: CardFacts, band: Box, size: CardSize, unit: Float) {
        val left = band.x + PAD * unit
        val column = (band.w - 2 * PAD * unit) / COLUMNS
        val cells = facts.cells
        if (size == CardSize.STORY) {
            cells.firstOrNull()?.let { cell(canvas, it, left, band.w - 2 * PAD * unit, band.y, STORY_FIRST_LABEL, STORY_FIRST_VALUE, BIG_VALUE_SIZE, unit) }
            val row = cells.drop(1) + listOfNotNull(facts.ascent?.let { CardFacts.LABEL_ASCENT to it })
            row.forEachIndexed { i, it -> cell(canvas, it, left + i * column, column, band.y, STORY_ROW_LABEL, STORY_ROW_VALUE, VALUE_SIZE, unit) }
        } else {
            cells.forEachIndexed { i, it -> cell(canvas, it, left + i * column, column, band.y, FEED_LABEL, FEED_VALUE, VALUE_SIZE, unit) }
        }

        val brandWidth = drawBrand(canvas, band, unit)
        val small = if (size == CardSize.STORY) facts.heart else facts.smallLine
        small?.let { line ->
            val paint = text(unit, SMALL_SIZE, white(SMALL_ALPHA))
            fit(paint, line, band.right - PAD * unit - brandWidth - BADGE_GAP * unit - left)
            canvas.drawText(line, left, band.y + band.h - LAST_ROW_ABOVE_BOTTOM * unit, paint)
        }
    }

    /**
     * The photo card's band (spec §2.3), right of the [route] square: the title line (no badge) and the date line, the
     * three numbers in a row below them, the block centred in [band]; "bikeRecoder" at the bottom right.
     */
    private fun drawPhotoText(canvas: Canvas, facts: CardFacts, band: Box, route: Box, unit: Float) {
        val left = route.right + PAD * unit
        val width = band.right - PAD * unit - left
        val centre = band.y + band.h / 2
        val title = titleLine(facts)
        val titlePaint = text(unit, TITLE_SIZE, Color.WHITE, bold = true)
        fit(titlePaint, title, width)
        canvas.drawText(title, left, centre + PHOTO_TITLE * unit, titlePaint)
        val datePaint = text(unit, DATE_SIZE, white(DATE_ALPHA))
        fit(datePaint, facts.dateLine, width)
        canvas.drawText(facts.dateLine, left, centre + PHOTO_DATE * unit, datePaint)
        val column = width / COLUMNS
        facts.cells.forEachIndexed { i, it -> cell(canvas, it, left + i * column, column, centre, PHOTO_LABEL, PHOTO_VALUE, VALUE_SIZE, unit) }
        drawBrand(canvas, band, unit)
    }

    /** "bikeRecoder" at [band]'s bottom right; returns its width. */
    private fun drawBrand(canvas: Canvas, band: Box, unit: Float): Float {
        val brand = text(unit, BRAND_SIZE, white(BRAND_ALPHA), align = Paint.Align.RIGHT)
        canvas.drawText(BRAND, band.right - PAD * unit, band.y + band.h - LAST_ROW_ABOVE_BOTTOM * unit, brand)
        return brand.measureText(BRAND)
    }

    /** "title · region", or the title alone without a region. */
    private fun titleLine(facts: CardFacts) = listOfNotNull(facts.title, facts.region).joinToString(" · ")

    /**
     * A cell: its label small, its value large and bold below, shrunk to [width] if need be; baselines in units from [top].
     * With [shadows] (blur radii in units) each text is drawn once per black shadow, in that order.
     */
    private fun cell(
        canvas: Canvas, cell: Pair<String, String>, x: Float, width: Float, top: Float,
        labelAt: Float, valueAt: Float, valueSize: Float, unit: Float,
        labelSize: Float = LABEL_SIZE, shadows: List<Float> = emptyList(),
    ) {
        val label = text(unit, labelSize, white(LABEL_ALPHA))
        fit(label, cell.first, width - CELL_GAP * unit)
        shadowed(canvas, cell.first, x, top + labelAt * unit, label, shadows, unit)
        val value = text(unit, valueSize, Color.WHITE, bold = true)
        fit(value, cell.second, width - CELL_GAP * unit)
        shadowed(canvas, cell.second, x, top + valueAt * unit, value, shadows, unit)
    }

    private fun shadowed(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint, shadows: List<Float>, unit: Float) {
        if (shadows.isEmpty()) return canvas.drawText(text, x, y, paint)
        for (radius in shadows) {
            paint.setShadowLayer(radius * unit, 0f, 0f, Color.BLACK)
            canvas.drawText(text, x, y, paint)
        }
        paint.clearShadowLayer()
    }

    /** [photo] cropped about its centre ([ShareCardLayout.cover]) to fill a [w]×[h] [canvas]. */
    private fun cover(canvas: Canvas, photo: Bitmap, w: Int, h: Int) {
        val src = ShareCardLayout.cover(photo.width, photo.height, w, h)
        val rect = Rect(src.left.roundToInt(), src.y.roundToInt(), src.right.roundToInt(), src.bottom.roundToInt())
        canvas.drawBitmap(photo, rect, RectF(0f, 0f, w.toFloat(), h.toFloat()), FILTER)
    }

    /**
     * [photo] cropped to fill [w]×[h], at 1/[BLUR_SCALE] of that size: halved step by step, so each step averages its
     * pixels (one filtered jump would skip most of them and sparkle). Scaled back up with filtering it is the blur.
     */
    private fun blurred(photo: Bitmap, w: Int, h: Int): Bitmap {
        var scale = 2
        var current = Bitmap.createBitmap(max(1, w / scale), max(1, h / scale), Bitmap.Config.ARGB_8888)
        cover(Canvas(current), photo, current.width, current.height)
        while (scale < BLUR_SCALE) {
            scale *= 2
            val next = Bitmap.createBitmap(max(1, w / scale), max(1, h / scale), Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(current, null, RectF(0f, 0f, next.width.toFloat(), next.height.toFloat()), FILTER)
            current.recycle()
            current = next
        }
        return current
    }

    /** [projected]'s runs as one path (one stroke: a translucent line does not darken where runs meet). */
    private fun path(projected: RouteOverlay.Projected) = Path().apply {
        for (run in projected.runs) {
            moveTo(run.xy[0], run.xy[1])
            for (i in 1 until run.xy.size / 2) lineTo(run.xy[2 * i], run.xy[2 * i + 1])
        }
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    /** Shrinks [paint]'s text size so [text] is at most [width] wide. */
    private fun fit(paint: Paint, text: String, width: Float) {
        val wide = paint.measureText(text)
        if (wide > width && width > 0f) paint.textSize *= width / wide
    }

    /** The baseline that centres [paint]'s line on [centre]. */
    private fun centred(paint: Paint, centre: Float): Float = centre - (paint.ascent() + paint.descent()) / 2

    private fun text(unit: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * unit
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
        }

    private fun fill(color: Int, alpha: Float) = Paint().apply {
        this.color = color
        this.alpha = (alpha * 255).roundToInt()
    }

    private fun white(alpha: Float) = Color.argb((alpha * 255).roundToInt(), 255, 255, 255)

    private val Box.left get() = x
    private val Box.right get() = x + w
    private val Box.bottom get() = y + h

    companion object {
        private const val TAG = "ShareCardRenderer"

        /** Each map source's time limit (spec §2.2). */
        const val MAP_TIMEOUT_MS = 8_000L

        /** The card without a map (spec §2.2). */
        const val NO_MAP = 0xFF1E2A22.toInt()

        private const val BRAND = "bikeRecoder"
        private const val TOP_BAND_ALPHA = 0.35f
        private const val BOTTOM_BAND_ALPHA = 0.5f

        // Sizes and places in units (the card's short side / 360).
        private const val PAD = 12f
        private const val BADGE = 18f
        private const val BADGE_GAP = 6f
        private const val TITLE_SIZE = 15f
        private const val DATE_SIZE = 11f
        private const val DATE_ALPHA = 0.85f

        /** The title line's centre sits this far above the band's centre; the date's baseline this far below that line. */
        private const val TITLE_ABOVE_CENTRE = 7f
        private const val DATE_BELOW = 22f
        private const val ATTRIBUTION_SIZE = 7f
        private const val ATTRIBUTION_ALPHA = 0.7f
        private const val ATTRIBUTION_BELOW = 10f
        private const val COLUMNS = 3
        private const val CELL_GAP = 4f
        private const val LABEL_SIZE = 11f
        private const val LABEL_ALPHA = 0.75f
        private const val VALUE_SIZE = 20f
        private const val BIG_VALUE_SIZE = 32f

        /** Label and value baselines from the band's top: a feed card's row, a story's first cell and its second row. */
        private const val FEED_LABEL = 26f
        private const val FEED_VALUE = 52f
        private const val STORY_FIRST_LABEL = 20f
        private const val STORY_FIRST_VALUE = 53f
        private const val STORY_ROW_LABEL = 71f
        private const val STORY_ROW_VALUE = 94f

        /** The small line's and "bikeRecoder"'s baseline above the band's bottom. */
        private const val LAST_ROW_ABOVE_BOTTOM = 13f
        private const val SMALL_SIZE = 11f
        private const val SMALL_ALPHA = 0.85f
        private const val BRAND_SIZE = 10f
        private const val BRAND_ALPHA = 0.85f

        /** Photo card (spec §2.3): the band's darkness, the route's width, the photo's dot radius. */
        private const val PHOTO_BAND_ALPHA = 0.45f
        private const val MINI_ROUTE_WIDTH = 2.4f
        private const val TAKEN_DOT = 4f

        /** The photo card's baselines from its band's centre: the title, the date, the cells' labels and values. */
        private const val PHOTO_TITLE = -30f
        private const val PHOTO_DATE = -14f
        private const val PHOTO_LABEL = 6f
        private const val PHOTO_VALUE = 26f

        /** A landscape slide's blurred fill is the photo at this fraction of the slide's size, scaled back up (spec §2.4). */
        private const val BLUR_SCALE = 16

        /** Sticker (spec §2.5): the route's share of the height from the top and its room to that area's edges. */
        private const val STICKER_ROUTE_SHARE = 0.6f
        private const val STICKER_INSET = 16f
        private const val STICKER_LINE_WIDTH = 4f
        private const val STICKER_CASING_WIDTH = 7f
        private const val STICKER_CASING_ALPHA = 0.55f
        private val STICKER_SHADOWS = listOf(6f, 3f)

        /** The sticker's cells: sizes, and baselines from the top of the area below the route. */
        private const val STICKER_LABEL_SIZE = 13f
        private const val STICKER_VALUE_SIZE = 28f
        private const val STICKER_LABEL = 52f
        private const val STICKER_VALUE = 86f

        private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)

        private const val TILE_SIZE = 512.0

        private fun mercatorX(lon: Double) = (lon + 180.0) / 360.0

        private fun mercatorY(lat: Double): Double {
            val r = Math.toRadians(lat)
            return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
        }
    }
}

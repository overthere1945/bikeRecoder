package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.core.video.FramePlan
import com.cowork.bikerecoder.core.video.Hud
import com.cowork.bikerecoder.core.video.HudValues
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.SceneKind
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.map.TileSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the video's frames (spec §2, §3.1③): a 3D MapLibre snapshot for the plan's [Camera], then on a software
 * Canvas the route (ridden / still ahead), the current point, the number band ([VideoOptions.hud]), the photo card,
 * the day caption, the opening title, the closing summary card with "bikeRecoder", and the fade to black.
 *
 * A plan with [FramePlan.reuseMap] draws over the previous snapshot (when its camera is the same). Photos come from [loadPhoto] (null = unreadable,
 * the photo scene then shows the map alone); a hardware bitmap is copied to software (ruling V7). Frames are drawn one
 * at a time, in order; [close] when done.
 */
class FrameRenderer(
    context: Context,
    tileSource: TileSource,
    private val inputs: VideoInputs,
    private val options: VideoOptions,
    private val width: Int,
    private val height: Int,
    private val loadPhoto: suspend (mediaId: Long) -> Bitmap?,
) : AutoCloseable {

    private val maps = MapSnapshots(context, tileSource, width, height)
    private val attribution = tileSource.attribution
    private val timeline = inputs.timeline
    private val overlay = RouteOverlay(timeline, pointColors())

    /** One unit: a 360-unit-wide short side, so a 1080 frame has 3 px per unit and the 720p fallback 2. */
    private val unit = min(width, height) / 360f

    private var shot: MapSnapshots.Shot? = null
    private var shotCamera: Camera? = null
    private var projected: RouteOverlay.Projected? = null
    private var projectedMoveMs = -1L
    private var photoId: Long? = null
    private var photo: Bitmap? = null

    /** Map snapshots taken so far (a reused frame takes none). */
    val snapshotsTaken: Int get() = maps.taken

    suspend fun render(plan: FramePlan, camera: Camera, canvas: Canvas) {
        val located = timeline.at(plan.moveMs)
        // Reused only for the same camera, so a frame drawn out of order never shows another frame's map.
        val current = shot.takeIf { plan.reuseMap && shotCamera == camera } ?: maps.take(camera).also {
            shot?.bitmap?.recycle()
            shot = it
            shotCamera = camera
            projected = null
        }
        val route = projected?.takeIf { projectedMoveMs == plan.moveMs }
            ?: overlay.project(current, camera, listOf(located.point)).also {
                projected = it
                projectedMoveMs = plan.moveMs
            }
        val cue = plan.photo?.takeIf { plan.kind == SceneKind.PHOTO }
        val picture = cue?.let { photoOf(it.mediaId) }

        canvas.drawBitmap(current.bitmap, 0f, 0f, null)
        val position = located.doneIndex + located.fraction
        val marker = route.extras[0] to route.extras[1]
        when (plan.kind) {
            SceneKind.OUTRO -> overlay.draw(canvas, route, Double.POSITIVE_INFINITY, unit)
            else -> {
                overlay.draw(canvas, route, position, unit, marker)
                drawMarker(canvas, marker.first, marker.second, overlay.colorAt(position))
            }
        }
        drawAttribution(canvas, hudShown(plan))
        if (hudShown(plan)) drawHud(canvas, Hud.at(plan.moveMs, timeline, inputs.heartRate))
        plan.caption?.let { drawCaption(canvas, it) }
        when (plan.kind) {
            SceneKind.INTRO_OVERVIEW -> drawTitle(canvas, 1f)
            SceneKind.INTRO_DESCENT -> drawTitle(canvas, 1f - plan.sceneProgress / TITLE_FADE_SHARE)
            SceneKind.PHOTO -> if (cue != null && picture != null) drawPhoto(canvas, picture, cue.takenAt, plan.sceneProgress)
            SceneKind.OUTRO -> drawSummary(canvas, plan.sceneProgress)
            SceneKind.RIDE -> Unit
        }
        if (plan.fade > 0f) canvas.drawColor(Color.argb((plan.fade.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0))
    }

    override fun close() {
        shot?.bitmap?.recycle()
        shot = null
        projected = null
        photo = null
    }

    private fun hudShown(plan: FramePlan) = options.hud && (plan.kind == SceneKind.RIDE || plan.kind == SceneKind.PHOTO)

    /** Colour of each point's stretch: its day's colour, or its heart-rate band ([RouteColoring.HEART_RATE]). */
    private fun pointColors(): IntArray {
        val points = timeline.points
        if (options.coloring == RouteColoring.HEART_RATE) {
            val track = points.map { TrackPoint(it.timeMillis, it.point, it.altitudeM, null, 0f) }
            return HeartRateColor.bands(track, inputs.heartRate).map { Color.parseColor(it.colorHex) }.toIntArray()
        }
        val bySegment = inputs.segmentColors.map(Color::parseColor)
        return IntArray(points.size) { bySegment.getOrElse(points[it].segmentIndex) { bySegment.lastOrNull() ?: Color.RED } }
    }

    private suspend fun photoOf(mediaId: Long): Bitmap? {
        if (photoId == mediaId) return photo
        // Not recycled: the loader may hand out a bitmap it keeps (a cache).
        photo = loadPhoto(mediaId)?.let(MapSnapshots::software)
        photoId = mediaId
        return photo
    }

    // ---- drawing ----

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * unit
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /** [paint] shrunk so [value] is at most [room] wide. */
    private fun fitted(paint: Paint, value: String, room: Float): Paint = paint.apply {
        val wide = measureText(value)
        if (wide > room) textSize *= room / wide
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, color: Int) {
        canvas.drawCircle(x, y + unit, 10 * unit, fill(Color.argb(70, 0, 0, 0)))
        canvas.drawCircle(x, y, 9 * unit, fill(Color.WHITE))
        canvas.drawCircle(x, y, 6 * unit, fill(color))
    }

    private fun drawAttribution(canvas: Canvas, aboveHud: Boolean) {
        val paint = text(7f, Color.argb(200, 60, 60, 60), align = Paint.Align.RIGHT)
        val y = height - (if (aboveHud) HUD_HEIGHT * unit else 0f) - 4 * unit
        canvas.drawText(attribution, width - 4 * unit, y, paint)
    }

    /** The bottom band (spec §2.2): distance so far, speed, altitude and heart rate; a cell without a value is left out. */
    private fun drawHud(canvas: Canvas, values: HudValues) {
        val top = height - HUD_HEIGHT * unit
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill(Color.argb(150, 0, 0, 0)))
        val cells = buildList {
            add("거리" to RideFormat.distance(values.distanceKm * 1000))
            add("속도" to "${RideFormat.speedKmh(values.speedKmh / 3.6)}km/h")
            values.altitudeM?.let { add("고도" to RideFormat.altitude(it)) }
            values.heartBpm?.let { add("심박" to "${it}bpm") }
        }
        val label = text(11f, Color.argb(200, 255, 255, 255))
        val cellW = width.toFloat() / cells.size
        cells.forEachIndexed { i, (name, number) ->
            val cx = cellW * (i + 0.5f)
            canvas.drawText(name, cx, top + 22 * unit, label)
            canvas.drawText(number, cx, top + 50 * unit, fitted(text(20f, bold = true), number, cellW - 12 * unit))
        }
    }

    /** "N일째 · M월 d일" at the top. */
    private fun drawCaption(canvas: Canvas, caption: String) {
        val paint = text(18f, bold = true)
        val w = paint.measureText(caption) + 32 * unit
        val top = 40 * unit
        val box = RectF((width - w) / 2, top, (width + w) / 2, top + 40 * unit)
        canvas.drawRoundRect(box, 20 * unit, 20 * unit, fill(Color.argb(160, 0, 0, 0)))
        canvas.drawText(caption, width / 2f, box.bottom - 13 * unit, paint)
    }

    /** The opening title (spec §2.1): date, "당일" / "여러 날 · N일", total distance; [alpha] fades it out. */
    private fun drawTitle(canvas: Canvas, alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0f) return
        val cy = height / 2f
        val box = RectF(width / 2f - 150 * unit, cy - 70 * unit, width / 2f + 150 * unit, cy + 70 * unit)
        canvas.drawRoundRect(box, 16 * unit, 16 * unit, fill(Color.argb((150 * a).roundToInt(), 0, 0, 0)))
        val alphaInt = (255 * a).roundToInt()
        canvas.drawText(inputs.title, width / 2f, cy - 22 * unit, text(30f, bold = true).apply { this.alpha = alphaInt })
        canvas.drawText(inputs.badge, width / 2f, cy + 8 * unit, text(16f).apply { this.alpha = alphaInt })
        canvas.drawText(
            RideFormat.distance(inputs.totals.distanceM), width / 2f, cy + 50 * unit,
            text(30f, bold = true).apply { this.alpha = alphaInt },
        )
    }

    /**
     * The photo (spec §2.3): 80 % of the width (and at most 70 % of the height), rounded, with a white border, its time
     * "오후 8:49" below; it grows in over 0.4 s, stays 1.7 s and fades out over 0.4 s.
     */
    private fun drawPhoto(canvas: Canvas, picture: Bitmap, takenAt: Long, progress: Float) {
        val t = progress * PHOTO_SECONDS
        val a = min(t / PHOTO_FADE_SECONDS, (PHOTO_SECONDS - t) / PHOTO_FADE_SECONDS).coerceIn(0f, 1f)
        if (a <= 0f) return
        canvas.drawColor(Color.argb((90 * a).roundToInt(), 0, 0, 0))
        val aspect = picture.width.toFloat() / picture.height
        val w = min(width * PHOTO_WIDTH_SHARE, height * PHOTO_HEIGHT_SHARE * aspect)
        val h = w / aspect
        val cx = width / 2f
        val cy = height / 2f - 16 * unit
        val scale = PHOTO_START_SCALE + (1f - PHOTO_START_SCALE) * a
        val alphaInt = (255 * a).roundToInt()
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val border = 4 * unit
        val radius = 16 * unit
        canvas.drawRoundRect(
            RectF(rect.left - border, rect.top - border, rect.right + border, rect.bottom + border),
            radius + border, radius + border, fill(Color.WHITE).apply { this.alpha = alphaInt },
        )
        val clip = android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(picture, null, rect, Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alphaInt })
        canvas.restore()
        val label = text(20f, bold = true).apply {
            this.alpha = alphaInt
            setShadowLayer(4 * unit, 0f, unit, Color.argb((200 * a).roundToInt(), 0, 0, 0))
        }
        canvas.drawText(TIME.format(Instant.ofEpochMilli(takenAt).atZone(SEOUL)), cx, cy + h * scale / 2 + border + 30 * unit, label)
    }

    /**
     * The closing card (spec §2.4): total distance, moving time, average / top speed, ascent, and the average heart rate
     * and calories when known, "bikeRecoder" small below. It sits low (two columns upright, four lying) so the whole
     * route above it stays in view, and fades in over the first half second.
     */
    private fun drawSummary(canvas: Canvas, progress: Float) {
        val a = (progress * VideoTimeline.OUTRO_FRAMES / SUMMARY_FADE_FRAMES).coerceIn(0f, 1f)
        if (a <= 0f) return
        val totals = inputs.totals
        val cells = buildList {
            add("거리" to RideFormat.distance(totals.distanceM))
            add("이동 시간" to RideFormat.duration(totals.movingTimeMs))
            add("평균 속도" to "${RideFormat.speedKmh(totals.avgSpeedMps)}km/h")
            add("최고 속도" to "${RideFormat.speedKmh(totals.maxSpeedMps)}km/h")
            add("상승 고도" to RideFormat.altitude(totals.ascentM))
            totals.avgBpm?.let { add("평균 심박" to "${it}bpm") }
            totals.calories?.let { add("칼로리" to "${it.roundToInt()}kcal") }
        }
        val columns = if (width > height) 4 else 2
        val rows = (cells.size + columns - 1) / columns
        val cardW = (48 + columns * SUMMARY_COLUMN - 20) * unit
        val cardH = (SUMMARY_HEAD + rows * SUMMARY_ROW + SUMMARY_FOOT) * unit
        val left = (width - cardW) / 2
        val top = height - cardH - 24 * unit
        val alphaInt = (255 * a).roundToInt()
        canvas.drawRoundRect(RectF(left, top, left + cardW, top + cardH), 18 * unit, 18 * unit,
            fill(Color.argb((215 * a).roundToInt(), 255, 255, 255)))
        val dark = Color.rgb(33, 33, 33)
        canvas.drawText(inputs.title, width / 2f, top + 30 * unit, text(18f, dark, bold = true).apply { alpha = alphaInt })
        canvas.drawText(inputs.badge, width / 2f, top + 47 * unit, text(11f, Color.rgb(97, 97, 97)).apply { alpha = alphaInt })
        val label = text(10f, Color.rgb(97, 97, 97), align = Paint.Align.LEFT).apply { alpha = alphaInt }
        val value = text(18f, dark, bold = true, align = Paint.Align.LEFT).apply { alpha = alphaInt }
        cells.forEachIndexed { i, (name, number) ->
            val x = left + (24 + (i % columns) * SUMMARY_COLUMN) * unit
            val y = top + (SUMMARY_HEAD + (i / columns) * SUMMARY_ROW) * unit
            canvas.drawText(name, x, y + 16 * unit, label)
            canvas.drawText(number, x, y + 38 * unit, fitted(Paint(value), number, (SUMMARY_COLUMN - 12) * unit))
        }
        canvas.drawText(BRAND, width / 2f, top + cardH - 12 * unit, text(11f, Color.rgb(120, 120, 120), bold = true).apply { alpha = alphaInt })
    }

    private companion object {
        const val BRAND = "bikeRecoder"
        const val HUD_HEIGHT = 64f
        const val TITLE_FADE_SHARE = 0.5f
        const val PHOTO_SECONDS = 2.5f
        const val PHOTO_FADE_SECONDS = 0.4f
        const val PHOTO_WIDTH_SHARE = 0.8f
        const val PHOTO_HEIGHT_SHARE = 0.7f
        const val PHOTO_START_SCALE = 0.85f
        const val SUMMARY_FADE_FRAMES = 12f
        const val SUMMARY_COLUMN = 140
        const val SUMMARY_HEAD = 56
        const val SUMMARY_ROW = 48
        const val SUMMARY_FOOT = 30
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
    }
}

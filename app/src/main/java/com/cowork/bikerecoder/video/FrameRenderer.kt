package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.core.video.ElevationGraph
import com.cowork.bikerecoder.core.video.FPS
import com.cowork.bikerecoder.core.video.FramePlan
import com.cowork.bikerecoder.core.video.Hud
import com.cowork.bikerecoder.core.video.HudValues
import com.cowork.bikerecoder.core.video.Located
import com.cowork.bikerecoder.core.video.OutroCardLayout
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.SceneKind
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the video's frames (spec §3): a 3D MapLibre snapshot for the plan's [Camera], then on a software Canvas the
 * glowing route with its tail ([RouteOverlay]), the pulsing current point, the haze on a tilted camera, the attribution,
 * the number band with the elevation graph ([VideoOptions.hud]), the day caption, the opening title, the photo card,
 * the closing card with the start and end points, and the fade to black.
 *
 * A plan with [FramePlan.reuseMap] draws over the previous snapshot (when its camera is the same). Photos come from [loadPhoto] (null or
 * an exception = unreadable, the photo scene then shows the map alone); a hardware bitmap is copied to software (ruling V7). Frames are drawn one
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

    private val log: WarnLog = androidWarnLog("FrameRenderer")

    private val maps = MapSnapshots(context, tileSource, width, height)

    /** One line at the top right (spec §1.1): "© 브이월드 · OpenStreetMap" on the satellite map, else the map's own. */
    private val attribution = tileSource.attribution
    private val timeline = inputs.timeline
    private val overlay = RouteOverlay(timeline, pointColors())
    private val ends = listOf(timeline.points.first().point, timeline.points.last().point)
    private val elevation = ElevationGraph.of(timeline)
    private val cells = summaryCells(inputs.totals, segmentCount(inputs))
    private val date = longDate(inputs.title)

    /** The most number cells a frame of this video shows: distance and speed, and altitude and heart rate when known. */
    private val maxCells = 2 + (if (timeline.points.any { it.altitudeM != null }) 1 else 0) + (if (inputs.heartRate.isNotEmpty()) 1 else 0)
    private val hudLayout = FrameLayout.hud(width, height, maxCells)
    private val graphPath: Path? = elevation?.let { graphPath(it, hudLayout.graph) }

    /** One unit: a 360-unit-wide short side, so a 1080 frame has 3 px per unit and the 720p fallback 2. */
    private val unit = min(width, height) / 360f

    private var shot: MapSnapshots.Shot? = null
    private var shotCamera: Camera? = null
    private var projected: RouteOverlay.Projected? = null
    private var projectedMoveMs = -1L
    /** How far below the middle the title starts: clear of the route however the sky view turns (set on its first frame). */
    private var titleOffset: Float? = null
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
            ?: overlay.project(current, camera, listOf(located.point) + ends).also {
                projected = it
                projectedMoveMs = plan.moveMs
            }
        val cue = plan.photo?.takeIf { plan.kind == SceneKind.PHOTO }
        val picture = cue?.let { photoOf(it.mediaId) }

        canvas.drawBitmap(current.bitmap, 0f, 0f, null)
        val position = located.doneIndex + located.fraction
        val xy = route.extras
        val marker = xy[0] to xy[1]
        val title = titleAlpha(plan)
        if (title > 0f) canvas.drawColor(withAlpha(FrameStyle.TITLE_SHADE, FrameStyle.TITLE_SHADE_ALPHA * title))
        when (plan.kind) {
            SceneKind.OUTRO -> overlay.draw(canvas, route, Double.POSITIVE_INFINITY, unit, null, RouteStyle.FULL)
            SceneKind.INTRO_OVERVIEW -> overlay.draw(canvas, route, position, unit, marker, RouteStyle.TITLE)
            else -> {
                overlay.draw(canvas, route, position, unit, marker, RouteStyle.RIDE)
                // The descent's first frames: the title card's route fades out over the ride's.
                if (title > 0f) {
                    canvas.saveLayerAlpha(null, (255 * title).roundToInt())
                    overlay.draw(canvas, route, position, unit, marker, RouteStyle.TITLE)
                    canvas.restore()
                }
            }
        }
        drawHaze(canvas, camera.tilt)
        val photoAlpha = if (cue != null && picture != null) photoPhase(plan).first else 0f
        if (plan.kind == SceneKind.OUTRO) {
            drawEnd(canvas, xy[2], xy[3], FrameStyle.START_GREEN)
            drawEnd(canvas, xy[4], xy[5], FrameStyle.END_BLACK)
        } else {
            // The photo card takes the current point's place while it is up (its time is written just below it).
            drawMarker(canvas, marker.first, marker.second, overlay.colorAt(position), plan.index, 1f - photoAlpha)
        }
        // The photo dims the map, not the numbers.
        if (photoAlpha > 0f) canvas.drawColor(Color.argb((90 * photoAlpha).roundToInt(), 0, 0, 0))
        drawAttribution(canvas)
        if (hudShown(plan)) drawHud(canvas, Hud.at(plan.moveMs, timeline, inputs.heartRate), located)
        plan.caption?.let { drawCaption(canvas, it) }
        if (plan.kind == SceneKind.INTRO_OVERVIEW && titleOffset == null) titleOffset = titleOffsetFor(route)
        if (title > 0f) drawTitle(canvas, title)
        if (cue != null && picture != null) drawPhoto(canvas, picture, cue.takenAt, plan, hudShown(plan))
        if (plan.kind == SceneKind.OUTRO) drawSummary(canvas, plan)
        if (plan.fade > 0f) canvas.drawColor(Color.argb((plan.fade.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0))
    }

    override fun close() {
        maps.close()
        shot?.bitmap?.recycle()
        shot = null
        projected = null
        photo = null
    }

    private fun hudShown(plan: FramePlan) = options.hud && (plan.kind == SceneKind.RIDE || plan.kind == SceneKind.PHOTO)

    /** The title card: whole over the sky view, fading over the descent's first [FrameStyle.TITLE_FADE_FRAMES] frames. */
    private fun titleAlpha(plan: FramePlan): Float = when (plan.kind) {
        SceneKind.INTRO_OVERVIEW -> 1f
        SceneKind.INTRO_DESCENT -> {
            val k = frameOf(plan, VideoTimeline.DESCENT_FRAMES)
            (1f - (k + 1f) / FrameStyle.TITLE_FADE_FRAMES).coerceIn(0f, 1f)
        }
        else -> 0f
    }

    /** Colour of each point's stretch: orange (spec §3.1), or its heart-rate band ([RouteColoring.HEART_RATE]). */
    private fun pointColors(): IntArray {
        val points = timeline.points
        if (options.coloring == RouteColoring.HEART_RATE) {
            val track = points.map { TrackPoint(it.timeMillis, it.point, it.altitudeM, null, 0f) }
            return HeartRateColor.bands(track, inputs.heartRate).map { Color.parseColor(it.colorHex) }.toIntArray()
        }
        return IntArray(points.size) { FrameStyle.ROUTE_ORANGE }
    }

    private suspend fun photoOf(mediaId: Long): Bitmap? {
        if (photoId == mediaId) return photo
        // Not recycled: the loader may hand out a bitmap it keeps (a cache).
        photo = try {
            loadPhoto(mediaId)?.let(MapSnapshots::software)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read photo $mediaId; showing the map only", e)
            null
        }
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

    /** A soft dark shadow under light text, so it reads over any map. */
    private fun Paint.shadowed(alpha: Float = 1f): Paint = apply {
        setShadowLayer(5 * unit, 0f, unit, Color.argb((150 * alpha).roundToInt(), 0, 0, 0))
    }

    /**
     * The current point (spec §3.2): a breathing glow (1 s, from frame [index]), a white disc and the route colour, at
     * opacity [alpha].
     */
    private fun drawMarker(canvas: Canvas, x: Float, y: Float, color: Int, index: Int, alpha: Float) {
        if (alpha <= 0f) return
        if (alpha < 1f) canvas.saveLayerAlpha(null, (255 * alpha).roundToInt())
        val phase = (index / FPS.toFloat() / FrameStyle.PULSE_SECONDS) % 1f
        val radius = FrameStyle.PULSE_FROM_R + (FrameStyle.PULSE_TO_R - FrameStyle.PULSE_FROM_R) * phase
        canvas.drawCircle(x, y, radius * unit, fill(withAlpha(color, FrameStyle.PULSE_ALPHA * (1f - phase))).apply {
            maskFilter = BlurMaskFilter(2 * unit, BlurMaskFilter.Blur.NORMAL)
        })
        canvas.drawCircle(x, y + unit, (FrameStyle.MARKER_WHITE_R + 1) * unit, fill(Color.argb(60, 0, 0, 0)))
        canvas.drawCircle(x, y, FrameStyle.MARKER_WHITE_R * unit, fill(Color.WHITE))
        canvas.drawCircle(x, y, FrameStyle.MARKER_COLOR_R * unit, fill(color))
        if (alpha < 1f) canvas.restore()
    }

    /** A start or end point of the closing frames (spec §3.2): [color] inside a white border. */
    private fun drawEnd(canvas: Canvas, x: Float, y: Float, color: Int) {
        canvas.drawCircle(x, y + unit, (FrameStyle.END_DOT_R + 1) * unit, fill(Color.argb(60, 0, 0, 0)))
        canvas.drawCircle(x, y, FrameStyle.END_DOT_R * unit, fill(Color.WHITE))
        canvas.drawCircle(x, y, (FrameStyle.END_DOT_R - FrameStyle.END_DOT_BORDER) * unit, fill(color))
    }

    /** Sky blue over the top 18 % on a tilted camera (spec §3.3), fully from 30° and less below it. */
    private fun drawHaze(canvas: Canvas, tilt: Double) {
        val strength = (tilt / FrameStyle.HAZE_FULL_TILT).coerceIn(0.0, 1.0).toFloat()
        if (strength <= 0f) return
        val bottom = height * FrameStyle.HAZE_SHARE
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, bottom,
                withAlpha(FrameStyle.HAZE, FrameStyle.HAZE_ALPHA * strength), withAlpha(FrameStyle.HAZE, 0f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), bottom, paint)
    }

    /** Small at the top right, on a faint dark pill so it reads over the haze and busy streets alike. */
    private fun drawAttribution(canvas: Canvas) {
        val paint = text(8f, Color.argb(235, 255, 255, 255), align = Paint.Align.RIGHT)
        val right = width - 8 * unit
        val baseline = 15 * unit
        val pill = RectF(right - paint.measureText(attribution) - 5 * unit, baseline - 9.5f * unit, right + 5 * unit, baseline + 3.5f * unit)
        canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill(Color.argb(120, 0, 0, 0)))
        canvas.drawText(attribution, right, baseline, paint)
    }

    /**
     * The number band (spec §3.4): a dark gradient over the bottom, the elevation graph (when the route has heights)
     * with the stretch ridden in orange, and the distance, speed, altitude and heart rate; a cell without a value is
     * left out.
     */
    private fun drawHud(canvas: Canvas, values: HudValues, located: Located) {
        val band = hudLayout.bandTop
        canvas.drawRect(0f, band, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, band, 0f, height.toFloat(),
                withAlpha(FrameStyle.HUD_DARK, 0f), withAlpha(FrameStyle.HUD_DARK, FrameStyle.HUD_ALPHA), Shader.TileMode.CLAMP,
            )
        })
        if (elevation != null && graphPath != null) drawGraph(canvas, graphPath, elevation.doneFraction(located, timeline).toFloat())

        val cells = buildList {
            add(Triple("거리", RideFormat.distance(values.distanceKm * 1000).removeSuffix("km"), "km"))
            add(Triple("속도", RideFormat.speedKmh(values.speedKmh / 3.6), "km/h"))
            values.altitudeM?.let { add(Triple("고도", RideFormat.altitude(it).removeSuffix("m"), "m")) }
            values.heartBpm?.let { add(Triple("심박", "$it", "bpm")) }
        }
        val layout = FrameLayout.hud(width, height, cells.size, sizedFor = maxCells)
        val label = text(FrameStyle.HUD_LABEL_SIZE, FrameStyle.HUD_LABEL)
        // Tabular digits: a number does not shift sideways as its digits change from frame to frame.
        val number = text(0f, bold = true, align = Paint.Align.LEFT).apply {
            textSize = layout.valueSize
            fontFeatureSettings = "'tnum'"
        }
        val small = text(FrameStyle.HUD_UNIT_SIZE, Color.argb(220, 255, 255, 255), align = Paint.Align.LEFT)
        val gap = 2 * unit
        // One size for every value: the largest that lets each number and its unit fit its cell.
        val shrink = cells.indices.minOf { i ->
            val wide = number.measureText(cells[i].second) + gap + small.measureText(cells[i].third)
            min(1f, layout.values[i].w / wide)
        }
        number.textSize *= shrink
        small.textSize *= shrink
        cells.forEachIndexed { i, (name, value, unitText) ->
            val box = layout.values[i]
            val cx = box.x + box.w / 2
            canvas.drawText(name, cx, FrameLayout.baseline(layout.labels[i]), label)
            val numberW = number.measureText(value)
            val left = cx - (numberW + gap + small.measureText(unitText)) / 2
            val baseline = FrameLayout.baseline(box)
            canvas.drawText(value, left, baseline, number)
            canvas.drawText(unitText, left + numberW + gap, baseline, small)
        }
    }

    /** The elevation outline faint white, its first [done] (0–1) orange, a white bar at the current point. */
    private fun drawGraph(canvas: Canvas, outline: Path, done: Float) {
        val box = hudLayout.graph
        canvas.drawPath(outline, fill(withAlpha(Color.WHITE, FrameStyle.GRAPH_OUTLINE_ALPHA)))
        val x = box.x + box.w * done
        canvas.save()
        canvas.clipRect(box.x, box.y, x, box.y + box.h)
        canvas.drawPath(outline, fill(withAlpha(FrameStyle.ROUTE_ORANGE, FrameStyle.GRAPH_DONE_ALPHA)))
        canvas.restore()
        canvas.drawRect(x - unit / 2, box.y, x + unit / 2, box.y + box.h, fill(Color.WHITE))
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

    /**
     * Where the title starts below the middle: past the farthest point of the route [projected] on the sky view, whose
     * camera turns about the middle at one zoom, so the route stays inside that circle for the whole scene. At least
     * a little below the middle, and never so low that the three lines leave the frame.
     */
    private fun titleOffsetFor(projected: RouteOverlay.Projected): Float {
        var reach = 0f
        for (run in projected.runs) for (i in 0 until run.xy.size / 2) {
            reach = max(reach, hypot(run.xy[2 * i] - width / 2f, run.xy[2 * i + 1] - height / 2f))
        }
        val lowest = height / 2f - TITLE_BLOCK * unit
        return (reach + (FrameStyle.MARKER_WHITE_R + TITLE_GAP) * unit).coerceAtLeast(TITLE_MIN_OFFSET * unit).coerceAtMost(lowest)
    }

    /**
     * The opening title (spec §3.5), below the middle and the route: the date, the ride's name and "1.58 km · 12분" in
     * orange. [alpha] fades it out.
     */
    private fun drawTitle(canvas: Canvas, alpha: Float) {
        val a = (255 * alpha).roundToInt()
        val room = width - 40 * unit
        val cx = width / 2f
        val top = height / 2f + (titleOffset ?: (TITLE_MIN_OFFSET * unit)) + FrameLayout.ASCENT * FrameStyle.TITLE_DATE_SIZE * unit
        val distance = "${RideFormat.distance(inputs.totals.distanceM).removeSuffix("km")} km · ${RideFormat.duration(inputs.totals.movingTimeMs)}"
        // A soft band of shade behind the three lines, so map names under them do not compete.
        val from = top - (FrameLayout.ASCENT * FrameStyle.TITLE_DATE_SIZE + TITLE_SCRIM) * unit
        val to = top + (70 + TITLE_SCRIM) * unit
        val shade = withAlpha(FrameStyle.TITLE_SHADE, TITLE_SCRIM_ALPHA * alpha)
        canvas.drawRect(0f, from, width.toFloat(), to, Paint().apply {
            shader = LinearGradient(
                0f, from, 0f, to, intArrayOf(Color.TRANSPARENT, shade, shade, Color.TRANSPARENT),
                floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP,
            )
        })
        canvas.drawText(date, cx, top, fitted(text(FrameStyle.TITLE_DATE_SIZE, bold = true), date, room).shadowed(alpha).apply { this.alpha = a })
        canvas.drawText(inputs.rideTitle, cx, top + 32 * unit,
            fitted(text(FrameStyle.TITLE_NAME_SIZE, FrameStyle.TITLE_NAME), inputs.rideTitle, room).shadowed(alpha).apply { this.alpha = a })
        canvas.drawText(distance, cx, top + 70 * unit,
            fitted(text(FrameStyle.TITLE_DISTANCE_SIZE, FrameStyle.ROUTE_ORANGE, bold = true), distance, room).shadowed(alpha).apply { this.alpha = a })
    }

    /**
     * The photo (spec §3.6): 80 % of the width (and at most 70 % of the height, and clear of the number band), with a
     * white border, rounded corners and a shadow, its time "오후 8:49" below. It pops in over 7 frames (0.85 → 1.04 →
     * 1), slowly zooms inside (1 → 1.06) and shrinks away over the last 7 (1 → 0.95).
     */
    private fun drawPhoto(canvas: Canvas, picture: Bitmap, takenAt: Long, plan: FramePlan, hud: Boolean) {
        val (alpha, scale) = photoPhase(plan)
        if (alpha <= 0f) return

        val border = FrameStyle.PHOTO_BORDER * unit
        val labelRoom = border + 36 * unit
        val topLimit = 24 * unit
        val bottomLimit = (if (hud) hudLayout.graph.y - 4 * unit else height - 16 * unit) - labelRoom
        val aspect = picture.width.toFloat() / picture.height
        val h = min(min(width * PHOTO_WIDTH_SHARE / aspect, height * PHOTO_HEIGHT_SHARE), bottomLimit - topLimit)
        val w = h * aspect
        val cx = width / 2f
        val cy = min(height / 2f - 16 * unit, bottomLimit - h / 2)
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val outer = RectF(rect.left - border, rect.top - border, rect.right + border, rect.bottom + border)
        val radius = FrameStyle.PHOTO_RADIUS * unit

        canvas.saveLayerAlpha(null, (255 * alpha).roundToInt())
        canvas.scale(scale, scale, cx, cy)
        val shadow = fill(withAlpha(Color.BLACK, FrameStyle.PHOTO_SHADOW_ALPHA)).apply {
            maskFilter = BlurMaskFilter(FrameStyle.PHOTO_SHADOW_BLUR * unit, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(RectF(outer).apply { offset(0f, FrameStyle.PHOTO_SHADOW_DY * unit) }, radius, radius, shadow)
        canvas.drawRoundRect(outer, radius, radius, fill(Color.WHITE))
        canvas.clipPath(Path().apply { addRoundRect(rect, radius - border / 2, radius - border / 2, Path.Direction.CW) })
        // The slow zoom: the same card shows a smaller middle part of the photo.
        val zoom = 1f + (FrameStyle.PHOTO_ZOOM - 1f) * plan.sceneProgress
        val cropW = picture.width / zoom
        val cropH = picture.height / zoom
        val source = Rect(
            ((picture.width - cropW) / 2).roundToInt(), ((picture.height - cropH) / 2).roundToInt(),
            ((picture.width + cropW) / 2).roundToInt(), ((picture.height + cropH) / 2).roundToInt(),
        )
        canvas.drawBitmap(picture, source, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        val label = text(20f, bold = true).shadowed(alpha).apply { this.alpha = (255 * alpha).roundToInt() }
        canvas.drawText(TIME.format(Instant.ofEpochMilli(takenAt).atZone(SEOUL)), cx, cy + (h / 2 + border) * scale + 30 * unit, label)
    }

    /**
     * The closing card (spec §3.7) at [OutroCardLayout.card]: the date, the ride's name, six numbers in two columns
     * (four lying) and "bikeRecoder". Nearly opaque: the outro camera ([outroCamera]) keeps the route above it. It rises
     * into place from frame [VideoTimeline.OUTRO_CARD_FROM] over [VideoTimeline.OUTRO_CARD_FRAMES] frames.
     */
    private fun drawSummary(canvas: Canvas, plan: FramePlan) {
        val k = frameOf(plan, VideoTimeline.OUTRO_FRAMES)
        val a = ((k - VideoTimeline.OUTRO_CARD_FROM + 1f) / VideoTimeline.OUTRO_CARD_FRAMES).coerceIn(0f, 1f)
        if (a <= 0f) return
        val layout = FrameLayout.outro(width, height, cells.size)
        val card = layout.card
        val radius = FrameStyle.CARD_RADIUS * unit
        canvas.saveLayerAlpha(null, (255 * a).roundToInt())
        canvas.translate(0f, FrameStyle.CARD_RISE * unit * (1f - a) * (1f - a))
        val box = RectF(card.x, card.y, card.x + card.w, card.y + card.h)
        canvas.drawRoundRect(RectF(box).apply { offset(0f, 3 * unit) }, radius, radius, fill(Color.argb(80, 0, 0, 0)).apply {
            maskFilter = BlurMaskFilter(10 * unit, BlurMaskFilter.Blur.NORMAL)
        })
        canvas.drawRoundRect(box, radius, radius, fill(Color.argb(FrameStyle.CARD_OPACITY, 255, 255, 255)))
        val cx = card.x + card.w / 2
        canvas.drawText(date, cx, FrameLayout.baseline(layout.date),
            fitted(text(FrameStyle.CARD_DATE_SIZE, DARK, bold = true), date, layout.date.w))
        canvas.drawText(inputs.rideTitle, cx, FrameLayout.baseline(layout.title),
            fitted(text(FrameStyle.CARD_NAME_SIZE, GREY), inputs.rideTitle, layout.title.w))
        val label = text(FrameStyle.CARD_LABEL_SIZE, GREY, align = Paint.Align.LEFT)
        val value = text(FrameStyle.CARD_VALUE_SIZE, DARK, bold = true, align = Paint.Align.LEFT)
        cells.forEachIndexed { i, (_, number) -> fitted(value, number, layout.values[i].w) }
        cells.forEachIndexed { i, (name, number) ->
            canvas.drawText(name, layout.labels[i].x, FrameLayout.baseline(layout.labels[i]), label)
            canvas.drawText(number, layout.values[i].x, FrameLayout.baseline(layout.values[i]), value)
        }
        canvas.drawText(BRAND, cx, FrameLayout.baseline(layout.brand), text(FrameStyle.CARD_BRAND_SIZE, BRAND_GREY, bold = true))
        canvas.restore()
    }

    companion object {
        /**
         * The OUTRO camera for a [width]×[height] frame (the encoder's size, 720p fallback included): the whole route fitted
         * above the closing card ([CameraPath.outro]). Pass it to `CameraPath.cameras(plans, timeline, overview, outro)`.
         */
        fun outroCamera(inputs: VideoInputs, width: Int, height: Int): Camera =
            CameraPath.outro(inputs.timeline.points.map { it.point }, width, height, outroCard(inputs, width, height).y)

        /** Where the closing card is drawn on a [width]×[height] frame. */
        fun outroCard(inputs: VideoInputs, width: Int, height: Int): Box =
            OutroCardLayout.card(width, height, summaryCells(inputs.totals, segmentCount(inputs)).size)

        /**
         * The closing card's six numbers (spec §3.7): distance, moving time, average / top speed, the average heart rate
         * (or "주행 N회", N = [segmentCount]) and the calories (or the ascent).
         */
        internal fun summaryCells(totals: TripTotals, segmentCount: Int): List<Pair<String, String>> = listOf(
            "거리" to RideFormat.distance(totals.distanceM),
            "이동 시간" to RideFormat.duration(totals.movingTimeMs),
            "평균 속도" to "${RideFormat.speedKmh(totals.avgSpeedMps)}km/h",
            "최고 속도" to "${RideFormat.speedKmh(totals.maxSpeedMps)}km/h",
            totals.avgBpm?.let { "평균 심박" to "${it}bpm" } ?: ("주행" to "${segmentCount}회"),
            totals.calories?.let { "칼로리" to "${it.roundToInt()}kcal" } ?: ("상승 고도" to RideFormat.altitude(totals.ascentM)),
        )

        /** The trip's target segments (the timeline numbers them 0…N−1). */
        private fun segmentCount(inputs: VideoInputs): Int = (inputs.timeline.points.lastOrNull()?.segmentIndex ?: -1) + 1

        /** The title's date with the weekday spelled out (spec §3.5): "10월 25일 (일)" → "10월 25일 일요일"; a range as it is. */
        internal fun longDate(title: String): String = WEEKDAY.replace(title) { " ${it.groupValues[1]}요일" }

        private val WEEKDAY = Regex(""" \(([월화수목금토일])\)$""")

        private const val BRAND = "bikeRecoder"
        private const val PHOTO_WIDTH_SHARE = 0.8f
        private const val PHOTO_HEIGHT_SHARE = 0.7f
        private const val DARK = 0xFF212121.toInt()
        private const val GREY = 0xFF757575.toInt()
        private const val BRAND_GREY = 0xFF9E9E9E.toInt()

        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

        /** The photo card's opacity and size in [plan]'s frame: popping in over the first frames, shrinking away over the last. */
        private fun photoPhase(plan: FramePlan): Pair<Float, Float> {
            val k = frameOf(plan, VideoTimeline.PHOTO_FRAMES)
            val outFrom = VideoTimeline.PHOTO_FRAMES - FrameStyle.PHOTO_OUT_FRAMES
            return when {
                k < FrameStyle.PHOTO_IN_FRAMES -> (k / (FrameStyle.PHOTO_IN_FRAMES - 1f)).let { it to popScale(it) }
                k >= outFrom -> ((k - outFrom) / (FrameStyle.PHOTO_OUT_FRAMES - 1f)).let { 1f - it to 1f + (FrameStyle.PHOTO_END_SCALE - 1f) * it }
                else -> 1f to 1f
            }
        }

        /** The title's date starts at least this far below the middle (units)... */
        private const val TITLE_MIN_OFFSET = 10f

        /** ...and this far past the route's reach. */
        private const val TITLE_GAP = 16f

        /** The shade behind the title reaches this far (units) above and below its lines, at most this opaque. */
        private const val TITLE_SCRIM = 28f
        private const val TITLE_SCRIM_ALPHA = 0.4f

        /** The three title lines from the top of the date to below the distance, with a margin to the bottom edge. */
        private const val TITLE_BLOCK = 130f

        /** [plan]'s frame number (from 0) within its scene of [frames] frames. */
        private fun frameOf(plan: FramePlan, frames: Int): Int = (plan.sceneProgress * (frames - 1)).roundToInt()

        /** The photo's size while it pops in, [t] 0 → 1: 0.85 up to 1.04 at 60 %, then settling to 1. */
        private fun popScale(t: Float): Float = if (t < POP_PEAK) {
            val e = 1f - (1f - t / POP_PEAK).let { it * it }
            FrameStyle.PHOTO_START_SCALE + (FrameStyle.PHOTO_OVERSHOOT_SCALE - FrameStyle.PHOTO_START_SCALE) * e
        } else {
            val e = ((t - POP_PEAK) / (1f - POP_PEAK)).let { it * it * (3 - 2 * it) }
            FrameStyle.PHOTO_OVERSHOOT_SCALE + (1f - FrameStyle.PHOTO_OVERSHOOT_SCALE) * e
        }

        private const val POP_PEAK = 0.6f

        /** The elevation outline (spec §3.4) as a closed shape over the bottom of [box]. */
        private fun graphPath(graph: ElevationGraph, box: Box): Path = Path().apply {
            val bottom = box.y + box.h
            fun x(i: Int) = box.x + box.w * graph.xs[i].toFloat()
            moveTo(x(0), bottom)
            for (i in graph.xs.indices) lineTo(x(i), bottom - box.h * graph.ys[i].toFloat())
            lineTo(x(graph.xs.lastIndex), bottom)
            close()
        }

        /** [color] at opacity [alpha] (0–1). */
        private fun withAlpha(color: Int, alpha: Float): Int = (color and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
    }
}

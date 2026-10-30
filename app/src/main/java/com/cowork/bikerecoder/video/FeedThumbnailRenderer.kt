package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.cowork.bikerecoder.core.video.CameraPath
import com.cowork.bikerecoder.map.TileSource

/**
 * The satellite picture of a trip card in the feed (V1.4.0 spec §2.4): the whole route in one 1280×720 snapshot of the
 * VWorld satellite map fitted with a [MARGIN] px margin, drawn as on the summary image — the route glowing orange (always
 * orange, ruling D2), the green start and black end points — with the map's attribution in one line at the bottom right.
 * The sequence is [SummaryImageRenderer]'s map: [CameraPath], [MapSnapshots], [RouteOverlay], [MapMarks].
 *
 * Only on the satellite map: when the start check ([VideoBackend.probeSatellite]) is not [SatelliteCheck.OK] (no key, no
 * connection, VWorld failing) the picture is not made — [render] returns null and the card shows its line drawing. A
 * failing snapshot throws. Returns a software ARGB_8888 bitmap that the caller owns (and recycles).
 */
class FeedThumbnailRenderer internal constructor(
    private val context: Context,
    private val backend: VideoBackend,
    private val plainTiles: TileSource,
    private val satelliteKey: String,
    private val satellite: (plain: TileSource, key: String) -> TileSource,
) {
    constructor(context: Context, backend: VideoBackend, plainTiles: TileSource, satelliteKey: String) :
        this(context, backend, plainTiles, satelliteKey, ::SatelliteTileSource)

    suspend fun render(inputs: VideoInputs): Bitmap? {
        val timeline = inputs.timeline
        val points = timeline.points
        if (points.isEmpty()) return null
        if (backend.probeSatellite(points.first().point) != SatelliteCheck.OK) return null

        val camera = CameraPath.overviewInset(points.map { it.point }, WIDTH, HEIGHT, MARGIN)
        val overlay = RouteOverlay(timeline, IntArray(points.size) { FrameStyle.ROUTE_ORANGE })
        val maps = MapSnapshots(context, satellite(plainTiles, satelliteKey), WIDTH, HEIGHT)
        val shot = try {
            maps.take(camera)
        } finally {
            maps.close()
        }
        try {
            val projected = overlay.project(shot, camera, listOf(points.first().point, points.last().point))
            val image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image)
                canvas.drawBitmap(shot.bitmap, 0f, 0f, null)
                overlay.draw(canvas, projected, Double.POSITIVE_INFINITY, UNIT, split = null, style = RouteStyle.FULL)
                val xy = projected.extras
                MapMarks.startAndEnd(canvas, xy[0], xy[1], xy[2], xy[3], UNIT)
                drawAttribution(canvas)
                return image
            } catch (e: Throwable) {
                image.recycle()
                throw e
            }
        } finally {
            shot.bitmap.recycle()
        }
    }

    /** One small white line on a dark translucent pill at the bottom right (off the route: inside the margin). */
    private fun drawAttribution(canvas: Canvas) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ATTRIBUTION_SIZE * UNIT
            color = Color.WHITE
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
        }
        val pad = 5 * UNIT
        val right = WIDTH - 8 * UNIT
        val bottom = HEIGHT - 8 * UNIT
        val width = text.measureText(VideoMapStyle.ATTRIBUTION)
        val height = text.fontMetrics.let { it.descent - it.ascent }
        val pill = RectF(right - width - 2 * pad, bottom - height - pad, right, bottom)
        canvas.drawRoundRect(pill, pad * 1.5f, pad * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL })
        canvas.drawText(VideoMapStyle.ATTRIBUTION, pill.right - pad, pill.bottom - pad / 2 - text.fontMetrics.descent, text)
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720

        /** The route's room to each edge of the picture, px. */
        const val MARGIN = 64

        /** Pixels per drawing unit, as the summary image (its short side / 360). */
        private const val UNIT = HEIGHT / 360f
        private const val ATTRIBUTION_SIZE = 9f
        private const val PILL = 0x99000000.toInt()
    }
}

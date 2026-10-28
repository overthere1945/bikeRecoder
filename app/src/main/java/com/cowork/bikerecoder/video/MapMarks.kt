package com.cowork.bikerecoder.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * The start and end points (spec §3.2) that the video's closing frames and the summary image both draw over the whole
 * route. Sizes are in units of [unit] pixels.
 */
internal object MapMarks {

    /** The start (green) and end (black) points at ([startX], [startY]) and ([endX], [endY]). */
    fun startAndEnd(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float, unit: Float) {
        endPoint(canvas, startX, startY, FrameStyle.START_GREEN, unit)
        endPoint(canvas, endX, endY, FrameStyle.END_BLACK, unit)
    }

    /** A start or end point: [color] inside a white border, over a faint shadow. */
    private fun endPoint(canvas: Canvas, x: Float, y: Float, color: Int, unit: Float) {
        canvas.drawCircle(x, y + unit, (FrameStyle.END_DOT_R + 1) * unit, fill(Color.argb(60, 0, 0, 0)))
        canvas.drawCircle(x, y, FrameStyle.END_DOT_R * unit, fill(Color.WHITE))
        canvas.drawCircle(x, y, (FrameStyle.END_DOT_R - FrameStyle.END_DOT_BORDER) * unit, fill(color))
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
}

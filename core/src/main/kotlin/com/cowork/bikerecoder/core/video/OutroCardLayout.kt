package com.cowork.bikerecoder.core.video

import kotlin.math.min

/**
 * The closing summary card of the video (spec §2.4) on a [width]×[height] frame: low on the screen, two columns of
 * numbers upright and four lying, so the whole route can be fitted above it ([CameraPath.outro]). Sizes are in units
 * of [unit] pixels (a 360-unit short side: 3 px at 1080p, 2 px at the 720p fallback).
 */
object OutroCardLayout {

    /** Title and badge above the numbers. */
    const val HEAD = 56f

    /** One row of numbers (label + value). */
    const val ROW = 48f

    /** "bikeRecoder" below the numbers. */
    const val FOOT = 30f

    /** One column of numbers. */
    const val COLUMN = 140f

    /** Inner margin left of the first column. */
    const val SIDE = 24f

    /** Gap between the card and the bottom of the frame. */
    const val BOTTOM = 24f

    private const val LAST_COLUMN_TRIM = 20f

    fun unit(width: Int, height: Int): Float = min(width, height) / 360f

    fun columns(width: Int, height: Int): Int = if (width > height) 4 else 2

    /** The card for [cellCount] numbers (at least one row), centred horizontally, [BOTTOM] above the bottom edge. */
    fun card(width: Int, height: Int, cellCount: Int): Box {
        val u = unit(width, height)
        val columns = columns(width, height)
        val rows = (cellCount.coerceAtLeast(1) + columns - 1) / columns
        val w = (2 * SIDE + columns * COLUMN - LAST_COLUMN_TRIM) * u
        val h = (HEAD + rows * ROW + FOOT) * u
        return Box((width - w) / 2, height - h - BOTTOM * u, w, h)
    }
}

package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.OutroCardLayout
import kotlin.math.max
import kotlin.math.min

/**
 * The video's drawing constants (spec §3). Sizes are in units: a 360-unit short side (3 px at 1080p, 2 px at the 720p
 * fallback). Colours are ARGB.
 */
internal object FrameStyle {
    const val ROUTE_ORANGE = 0xFFFF7A1A.toInt()
    const val START_GREEN = 0xFF3CC85A.toInt()
    const val END_BLACK = 0xFF1E1E1E.toInt()
    const val HAZE = 0xFFBED2EB.toInt()
    const val HUD_DARK = 0xFF080C12.toInt()

    // §3.1 the route.
    const val GLOW_WIDTH = 20f
    const val CASING_WIDTH = 10f
    const val LINE_WIDTH = 7f
    const val TAIL_WIDTH = 9f
    const val LEFT_WIDTH = 4f
    const val TAIL_M = 150.0
    const val GLOW_BLUR = 8f
    const val GLOW_ALPHA = 0.55f
    const val TAIL_GLOW_ALPHA = 0.8f
    const val LEFT_ALPHA = 0.5f

    /** The title card's whole route is drawn this much thinner (§3.5). */
    const val TITLE_ROUTE_SCALE = 0.7f

    // §3.2 the current point and the outro's start / end points.
    const val MARKER_WHITE_R = 11f
    const val MARKER_COLOR_R = 8f
    const val PULSE_FROM_R = 14f
    const val PULSE_TO_R = 30f
    const val PULSE_ALPHA = 0.45f
    const val PULSE_SECONDS = 1f
    const val END_DOT_R = 9f
    const val END_DOT_BORDER = 2.5f

    // §3.3 the haze at the top.
    const val HAZE_SHARE = 0.18f
    const val HAZE_ALPHA = 0.6f
    const val HAZE_FULL_TILT = 30.0

    // §3.4 the number band.
    const val HUD_SHARE = 0.27f
    const val HUD_ALPHA = 0.85f

    /** Under the band the route fades out, gone this far (share of the band's height) below the band's top. */
    const val ROUTE_FADE_SHARE = 0.2f
    const val HUD_LABEL = 0xFFC8CDD7.toInt()
    const val HUD_LABEL_SIZE = 13f
    const val HUD_UNIT_SIZE = 13f
    const val HUD_VALUE_SIZE_3 = 36f
    const val HUD_VALUE_SIZE_4 = 30f
    const val GRAPH_HEIGHT = 22f
    const val GRAPH_OUTLINE_ALPHA = 0.28f
    const val GRAPH_DONE_ALPHA = 0.9f

    // §3.5 the title card.
    const val TITLE_SHADE = 0xFF050A14.toInt()
    const val TITLE_SHADE_ALPHA = 0.45f
    const val TITLE_DATE_SIZE = 34f
    const val TITLE_NAME_SIZE = 18f
    const val TITLE_NAME = 0xFFE6EBF5.toInt()
    const val TITLE_DISTANCE_SIZE = 24f

    /** The title fades over the first frames of the descent (§2.2). */
    const val TITLE_FADE_FRAMES = 4

    // §3.6 the photo card.
    const val PHOTO_IN_FRAMES = 7
    const val PHOTO_OUT_FRAMES = 7
    const val PHOTO_START_SCALE = 0.85f
    const val PHOTO_OVERSHOOT_SCALE = 1.04f
    const val PHOTO_END_SCALE = 0.95f
    const val PHOTO_ZOOM = 1.06f
    const val PHOTO_BORDER = 3f
    const val PHOTO_RADIUS = 6f
    const val PHOTO_SHADOW_ALPHA = 0.35f
    const val PHOTO_SHADOW_BLUR = 8f
    const val PHOTO_SHADOW_DY = 3f

    // §3.7 the closing card.
    const val CARD_RADIUS = 16f
    /**
     * Fully opaque. The spec's 248 (V1.3.0) let the white map labels show through as ghost words between the numbers on
     * the satellite map; its intent was that nothing of the map shows through.
     */
    const val CARD_OPACITY = 255
    const val CARD_DATE_SIZE = 24f
    const val CARD_NAME_SIZE = 14f
    const val CARD_LABEL_SIZE = 13f
    const val CARD_VALUE_SIZE = 24f
    const val CARD_BRAND_SIZE = 11f
    const val CARD_ATTRIBUTION_SIZE = 9f

    /** A value's unit in the card ("km", "km/h", "bpm", …) is this much of the value's size, as in the band. */
    const val CARD_UNIT_SHARE = 0.6f

    /** The card rises this far into place while it fades in. */
    const val CARD_RISE = 16f
}

/**
 * Where the number band and the closing card put their parts on a [width]×[height] frame. Text boxes run from a little
 * above the cap height to a little below the baseline, so boxes that do not overlap mean text that does not touch.
 */
internal object FrameLayout {

    /** The number band: [bandTop] where its gradient starts, the elevation [graph], and per cell its label and value. */
    class Hud(val bandTop: Float, val graph: Box, val labels: List<Box>, val values: List<Box>, val valueSize: Float) {
        val parts: List<Box> get() = listOf(graph) + labels + values
    }

    /**
     * The closing card and, inside it, the date, the ride title, a label and value per cell, and the footer:
     * "bikeRecoder" on the left and the map attribution on the right (the outro shows no attribution over the map).
     */
    class Outro(
        val card: Box,
        val date: Box,
        val title: Box,
        val labels: List<Box>,
        val values: List<Box>,
        val brand: Box,
        val attribution: Box,
    ) {
        val parts: List<Box> get() = listOf(date, title) + labels + values + brand + attribution
    }

    /** A text box of [size] units: [ASCENT] of it above the baseline, [DESCENT] below. */
    const val ASCENT = 0.8f
    const val DESCENT = 0.22f
    private const val BOX_EM = ASCENT + DESCENT

    private const val SIDE = 16f
    private const val BOTTOM = 20f
    private const val LABEL_GAP = 8f
    private const val GRAPH_GAP = 10f

    /** Room above the band's content for the gradient to fade in, when 27 % of the height would be too little. */
    private const val FADE_ROOM = 26f

    /** Landscape: the cells take the left 60 % of the band's one row, the graph the rest. */
    private const val WIDE_CELLS_SHARE = 0.6f

    fun unit(width: Int, height: Int): Float = min(width, height) / 360f

    /** A text box with its baseline at [baseline]. */
    fun text(x: Float, baseline: Float, w: Float, size: Float): Box = Box(x, baseline - ASCENT * size, w, BOX_EM * size)

    /** The baseline of a text [box] made by [text]. */
    fun baseline(box: Box): Float = box.y + box.h * ASCENT / BOX_EM

    /**
     * The band with [cellCount] columns. The value size (36u for up to three cells, 30u for four) and so the graph's
     * place follow [sizedFor], the most cells this video ever shows, so neither jumps when a frame lacks a value.
     * Portrait: the graph across the width above the cells. Landscape: one row, the cells on the left 60 % and the graph
     * on the right as high as the values, so the band stays within 27 % of the short height.
     */
    fun hud(width: Int, height: Int, cellCount: Int, sizedFor: Int = cellCount): Hud {
        val u = unit(width, height)
        val cells = cellCount.coerceAtLeast(1)
        val valueSize = (if (max(cells, sizedFor) >= 4) FrameStyle.HUD_VALUE_SIZE_4 else FrameStyle.HUD_VALUE_SIZE_3) * u
        val labelSize = FrameStyle.HUD_LABEL_SIZE * u
        val valueBaseline = height - BOTTOM * u
        val valueTop = valueBaseline - ASCENT * valueSize
        val labelBaseline = valueTop - LABEL_GAP * u - DESCENT * labelSize
        val labelTop = labelBaseline - ASCENT * labelSize
        val graphH = FrameStyle.GRAPH_HEIGHT * u
        val wide = width > height
        val cellsRight = if (wide) width * WIDE_CELLS_SHARE else width - SIDE * u
        val graph = if (wide) {
            val left = cellsRight + GRAPH_GAP * u
            Box(left, valueBaseline - graphH, width - SIDE * u - left, graphH)
        } else {
            Box(SIDE * u, labelTop - GRAPH_GAP * u - graphH, width - 2 * SIDE * u, graphH)
        }
        val contentTop = min(graph.y, labelTop)
        val bandTop = height - max(FrameStyle.HUD_SHARE * height, height - contentTop + FADE_ROOM * u)
        val column = (cellsRight - SIDE * u) / cells
        val pad = 4 * u
        val labels = List(cells) { text(SIDE * u + it * column + pad, labelBaseline, column - 2 * pad, labelSize) }
        val values = List(cells) { text(SIDE * u + it * column + pad, valueBaseline, column - 2 * pad, valueSize) }
        return Hud(bandTop, graph, labels, values, valueSize)
    }

    fun outro(width: Int, height: Int, cellCount: Int): Outro {
        val u = unit(width, height)
        val card = OutroCardLayout.card(width, height, cellCount)
        val columns = OutroCardLayout.columns(width, height)
        val inner = card.w - 24 * u
        val date = text(card.x + 12 * u, card.y + 30 * u, inner, FrameStyle.CARD_DATE_SIZE * u)
        val title = text(card.x + 12 * u, card.y + 49 * u, inner, FrameStyle.CARD_NAME_SIZE * u)
        val cellW = (OutroCardLayout.COLUMN - 12) * u
        fun cellX(i: Int) = card.x + (OutroCardLayout.SIDE + (i % columns) * OutroCardLayout.COLUMN) * u
        fun cellY(i: Int) = card.y + (OutroCardLayout.HEAD + (i / columns) * OutroCardLayout.ROW) * u
        val labels = List(cellCount) { text(cellX(it), cellY(it) + 15 * u, cellW, FrameStyle.CARD_LABEL_SIZE * u) }
        val values = List(cellCount) { text(cellX(it), cellY(it) + 40 * u, cellW, FrameStyle.CARD_VALUE_SIZE * u) }
        val footer = card.y + card.h - 11 * u
        val half = (card.w - 2 * OutroCardLayout.SIDE * u) / 2 - 4 * u
        val brand = text(card.x + OutroCardLayout.SIDE * u, footer, half, FrameStyle.CARD_BRAND_SIZE * u)
        val attribution = text(card.x + card.w - OutroCardLayout.SIDE * u - half, footer, half, FrameStyle.CARD_ATTRIBUTION_SIZE * u)
        return Outro(card, date, title, labels, values, brand, attribution)
    }
}

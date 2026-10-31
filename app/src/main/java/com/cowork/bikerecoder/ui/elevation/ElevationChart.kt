package com.cowork.bikerecoder.ui.elevation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.elevation.ClimbFinder
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.GradeBand
import com.cowork.bikerecoder.core.format.RideFormat
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Test tags of the elevation chart, strip and map screen. */
object ElevationTags {
    const val CHART = "elevation_chart"
    const val NO_ELEVATION = "elevation_none"
    const val SHOW_ON_MAP = "elevation_show_on_map"
    const val SCRUB_LINE = "elevation_scrub_line"
    const val STRIP = "elevation_strip"
    const val STRIP_FLAG = "elevation_strip_flag"
    const val LEGEND_CELL = "elevation_legend_cell"
    const val SCRUB_TEXT = "elevation_scrub_text"
    const val CLOSE = "elevation_close"
    const val MAP = "elevation_map"

    /** The alpha [ElevationStrip] is drawn with (0.4 while dimmed, else 1), the same value its layer applies. */
    val StripAlpha = SemanticsPropertyKey<Float>("ElevationStripAlpha")
}

private var SemanticsPropertyReceiver.stripAlpha by ElevationTags.StripAlpha

/** The band's colour ([GradeBand.argb]). */
internal fun GradeBand.color(): Color = Color(argb.toInt())

/** Height of the km axis under the plot. */
private val AXIS_HEIGHT = 16.dp
private const val STRIP_DIMMED_ALPHA = 0.4f

/**
 * The shared elevation chart (V1.7.0 spec §2.1): the profile's line with the area under it in the grade bands' colours,
 * the highest height top left and the lowest bottom left, km ticks under it and a small tick at every segment / stop
 * boundary. Without a [profile] it says "고도 정보가 없습니다" instead.
 *
 * Small ([scrubbable] false): with [onOpen] the whole chart is a button that opens the "고도" map screen, with
 * [지도에 표시] bottom right. Big ([scrubbable] true): a tap or drag reports the distance under the finger to [onScrub];
 * the caller keeps it and passes it back as [scrubM], where a white vertical line and a dot are drawn (kept after the
 * finger lifts as long as the caller keeps it). The chart holds no scrub position of its own.
 */
@Composable
fun ElevationChart(
    profile: ElevationProfile?,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    scrubbable: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onScrub: (distanceM: Double) -> Unit = {},
    scrubM: Double? = null,
) {
    if (profile == null) {
        Box(modifier.fillMaxWidth().height(height).testTag(ElevationTags.NO_ELEVATION), contentAlignment = Alignment.Center) {
            Text(
                ElevationText.NO_ELEVATION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val climbs = remember(profile) { ClimbFinder.find(profile).size }
    val description = ElevationText.chartDescription(profile.minElevationM, profile.maxElevationM, climbs)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val open = onOpen.takeUnless { scrubbable }
    val colors = chartColors()
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    // Remembered so a recomposition (e.g. every scrub) keeps the cached band areas instead of building them again.
    val drawChart = remember(profile, colors, labelStyle, measurer) {
        Modifier.drawWithCache {
            val plot = Size(size.width, size.height - AXIS_HEIGHT.toPx())
            val shapes = profileShapes(profile, plot, columnPx = 2.dp.toPx())
            val label = labelStyle.copy(color = colors.label)
            onDrawBehind {
                drawProfile(shapes, colors.line)
                drawBoundaries(profile, plot, colors.axis)
                drawKmAxis(profile, plot, measurer, label, colors.axis)
                drawHeightLabel(measurer, RideFormat.altitude(profile.maxElevationM), label, colors.labelBackground, top = true, plot)
                drawHeightLabel(measurer, RideFormat.altitude(profile.minElevationM), label, colors.labelBackground, top = false, plot)
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (open != null) Modifier.clickable(onClickLabel = ElevationText.SHOW_ON_MAP, role = Role.Button, onClick = open) else Modifier)
            .then(
                if (scrubbable) {
                    Modifier.pointerInput(profile) {
                        fun scrubAt(x: Float) {
                            val width = size.width.toFloat()
                            if (width <= 0f) return
                            currentOnScrub((x / width).coerceIn(0f, 1f) * profile.totalM)
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            scrubAt(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                scrubAt(change.position.x)
                                change.consume()
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description }
            .testTag(ElevationTags.CHART),
    ) {
        val plotHeight = maxHeight - AXIS_HEIGHT
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}.then(drawChart))
        if (scrubbable && scrubM != null) {
            val d = scrubM.coerceIn(0.0, profile.totalM)
            // The line first, the dot over it.
            Box(
                Modifier
                    .offset { IntOffset((d / profile.totalM * constraints.maxWidth).roundToInt() - 1.dp.roundToPx(), 0) }
                    .width(2.dp)
                    .height(plotHeight)
                    .background(Color.White)
                    .testTag(ElevationTags.SCRUB_LINE),
            )
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                val plot = Size(size.width, size.height - AXIS_HEIGHT.toPx())
                val at = Offset(xOf(d, profile, plot), yOf(profile.elevationAt(d), profile, plot))
                drawCircle(Color(0xFF424242), radius = 6.dp.toPx(), center = at)
                drawCircle(Color.White, radius = 4.dp.toPx(), center = at)
            }
        }
        if (open != null) {
            ShowOnMapButton(
                onClick = open,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = AXIS_HEIGHT + 4.dp),
            )
        }
    }
}

/** [지도에 표시] on the small chart: a map icon and the text. */
@Composable
private fun ShowOnMapButton(onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag(ElevationTags.SHOW_ON_MAP),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.primary,
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(ElevationText.SHOW_ON_MAP, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The guidance's "앞길 고도" strip (V1.7.0 spec §3.2): [profile] (already cut to the 2 km ahead) in the grade bands'
 * colours, 56dp high, no axis text; a small dot at the left end (the rider), a flag at the right end when it
 * [reachesDestination], alpha 0.4 while [dimmed] (off route or rerouting). Nothing without a profile.
 */
@Composable
fun ElevationStrip(
    profile: ElevationProfile?,
    reachesDestination: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (profile == null) return
    val colors = chartColors()
    val alpha = if (dimmed) STRIP_DIMMED_ALPHA else 1f
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(alpha)
            .semantics { stripAlpha = alpha }
            .testTag(ElevationTags.STRIP),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 6.dp, end = if (reachesDestination) 12.dp else 0.dp)
                .drawWithCache {
                    val shapes = profileShapes(profile, size, columnPx = 2.dp.toPx())
                    onDrawBehind {
                        drawProfile(shapes, colors.line)
                        val at = Offset(0f, yOf(profile.elevationAt(0.0), profile, size))
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = at)
                        drawCircle(colors.line, radius = 3.5.dp.toPx(), center = at)
                    }
                },
        )
        if (reachesDestination) {
            Icon(
                Icons.Filled.Flag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp).testTag(ElevationTags.STRIP_FLAG),
            )
        }
    }
}

/** Compared by value, so the same theme gives an equal instance (and the chart's drawing cache is kept). */
@Immutable
private data class ChartColors(val line: Color, val axis: Color, val label: Color, val labelBackground: Color)

@Composable
private fun chartColors(): ChartColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        ChartColors(
            line = scheme.onSurface.copy(alpha = 0.85f),
            axis = scheme.outline,
            label = scheme.onSurfaceVariant,
            labelBackground = scheme.surface.copy(alpha = 0.75f),
        )
    }
}

/** The filled band areas (each its colour) and the profile line, sampled every [columnPx] across [plot]. */
private class ProfileShapes(val areas: List<Pair<Path, Color>>, val line: Path)

private fun profileShapes(profile: ElevationProfile, plot: Size, columnPx: Float): ProfileShapes {
    val areas = ArrayList<Pair<Path, Color>>()
    val line = Path()
    if (plot.width <= 0f || plot.height <= 0f) return ProfileShapes(areas, line)
    val columns = max(1, (plot.width / columnPx).roundToInt())
    var area: Path? = null
    var areaBand: GradeBand? = null
    for (c in 0..columns) {
        val x = plot.width * c / columns
        val d = profile.totalM * c / columns
        val y = yOf(profile.elevationAt(d), profile, plot)
        // A column takes the band of its middle.
        val band = if (c == columns) areaBand ?: profile.bandAt(d) else profile.bandAt(profile.totalM * (c + 0.5) / columns)
        if (c == 0) line.moveTo(x, y) else line.lineTo(x, y)
        if (area == null) {
            area = Path().apply { moveTo(x, plot.height); lineTo(x, y) }
            areaBand = band
        } else {
            area.lineTo(x, y)
            if (band != areaBand || c == columns) {
                area.lineTo(x, plot.height)
                area.close()
                areas += area to areaBand!!.color()
                area = Path().apply { moveTo(x, plot.height); lineTo(x, y) }
                areaBand = band
            }
        }
    }
    return ProfileShapes(areas, line)
}

private fun DrawScope.drawProfile(shapes: ProfileShapes, lineColor: Color) {
    shapes.areas.forEach { (path, color) -> drawPath(path, color) }
    drawPath(shapes.line, lineColor, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A short tick at the bottom of the plot at every segment / stop boundary. */
private fun DrawScope.drawBoundaries(profile: ElevationProfile, plot: Size, color: Color) {
    val tick = 6.dp.toPx()
    profile.boundariesM.forEach { d ->
        val x = xOf(d, profile, plot)
        drawLine(color, Offset(x, plot.height - tick), Offset(x, plot.height), strokeWidth = 1.5.dp.toPx())
    }
}

/** The base line and the km ticks ([kmTickStepKm]) with their labels under the plot. */
private fun DrawScope.drawKmAxis(profile: ElevationProfile, plot: Size, measurer: TextMeasurer, style: TextStyle, color: Color) {
    drawLine(color, Offset(0f, plot.height), Offset(plot.width, plot.height), strokeWidth = 1.dp.toPx())
    val stepKm = kmTickStepKm(profile.totalM)
    val count = floor(profile.totalM / 1_000.0 / stepKm + 1e-9).toInt()
    for (k in 0..count) {
        val km = k * stepKm
        val x = xOf(km * 1_000.0, profile, plot)
        drawLine(color, Offset(x, plot.height), Offset(x, plot.height + 3.dp.toPx()), strokeWidth = 1.dp.toPx())
        val text = measurer.measure(ElevationText.kmTick(km), style)
        val left = (x - text.size.width / 2f).coerceIn(0f, max(0f, size.width - text.size.width))
        drawText(text, topLeft = Offset(left, plot.height + 2.dp.toPx()))
    }
}

/** The highest ([top]) or lowest height in the plot's top or bottom left corner, on a faint background. */
private fun DrawScope.drawHeightLabel(
    measurer: TextMeasurer,
    label: String,
    style: TextStyle,
    background: Color,
    top: Boolean,
    plot: Size,
) {
    val text = measurer.measure(label, style)
    val pad = 2.dp.toPx()
    val left = 4.dp.toPx()
    val topY = if (top) 2.dp.toPx() else plot.height - text.size.height - 4.dp.toPx()
    drawRoundRect(
        background,
        topLeft = Offset(left - pad, topY),
        size = Size(text.size.width + 2 * pad, text.size.height.toFloat()),
        cornerRadius = CornerRadius(pad, pad),
    )
    drawText(text, topLeft = Offset(left, topY))
}

private fun xOf(distanceM: Double, profile: ElevationProfile, plot: Size): Float =
    if (profile.totalM <= 0.0) 0f else (distanceM / profile.totalM * plot.width).toFloat()

/**
 * The plot's y of [elevationM]: the profile's range with room above (for the top label) and below, at least
 * [MIN_SPAN_M] tall so a flat ride stays flat.
 */
private fun yOf(elevationM: Double, profile: ElevationProfile, plot: Size): Float {
    val span = max(profile.maxElevationM - profile.minElevationM, MIN_SPAN_M)
    val mid = (profile.maxElevationM + profile.minElevationM) / 2
    val lo = mid - span * 0.65
    val hi = mid + span * 0.75
    return ((hi - elevationM) / (hi - lo) * plot.height).toFloat()
}

private const val MIN_SPAN_M = 20.0

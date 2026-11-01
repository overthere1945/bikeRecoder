package com.cowork.bikerecoder.ui.elevation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.MetricSeries
import kotlin.math.max
import kotlin.math.roundToInt

/** Test tags of the speed and heart-rate charts ([지도에 표시] and the scrub line keep [ElevationTags]' own). */
object MetricTags {
    const val SPEED_CHART = "speed_chart"
    const val HEART_CHART = "heart_chart"
    const val NO_SPEED = "speed_none"

    /** The chart's tag for [kind]. */
    fun chart(kind: MetricKind): String = when (kind) {
        MetricKind.SPEED -> SPEED_CHART
        MetricKind.HEART_RATE -> HEART_CHART
    }

    /** The top and bottom labels the chart draws ([ChartText.valueLabel] of the series' highest and lowest). */
    val EndLabels = SemanticsPropertyKey<List<String>>("MetricEndLabels")
}

private var SemanticsPropertyReceiver.endLabels by MetricTags.EndLabels

/**
 * The shared speed / heart-rate chart (V1.8.0 spec §2.1), drawn as [ElevationChart] is: the smoothed [series] as a line
 * with the area under it in the speed or heart-rate bands' colours, its highest value top left and lowest bottom left,
 * km ticks under it and a small tick at every segment boundary; small with [onOpen] a button with [지도에 표시], big
 * ([scrubbable]) reporting the finger's distance to [onScrub] and drawing the line and dot at the caller's [scrubM].
 * Read out as [description]. Where the series has no value the fill and the line stop (a lone value between gaps draws
 * nothing, as it has no neighbour to draw to), and the scrub line has no dot.
 *
 * Without a [series]: speed says "속도 정보가 없습니다", heart rate draws nothing at all (no place for it). A [series] must
 * be of [kind].
 */
@Composable
fun MetricChart(
    series: MetricSeries?,
    kind: MetricKind,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    scrubbable: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onScrub: (distanceM: Double) -> Unit = {},
    scrubM: Double? = null,
) {
    require(series == null || series.kind == kind) { "a ${series?.kind} series drawn as $kind" }
    if (series == null) {
        if (kind == MetricKind.SPEED) NoChartData(ChartText.NO_SPEED, MetricTags.NO_SPEED, modifier, height)
        return
    }
    val colors = chartColors()
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val top = ChartText.valueLabel(kind, series.maxValue)
    val bottom = ChartText.valueLabel(kind, series.minValue)
    // Remembered so a recomposition (e.g. every scrub) keeps the cached band areas instead of building them again.
    val drawChart = remember(series, kind, colors, labelStyle, measurer) {
        Modifier.drawWithCache {
            val plot = Size(size.width, size.height - AXIS_HEIGHT.toPx())
            val shapes = seriesShapes(series, plot, columnPx = 2.dp.toPx())
            val label = labelStyle.copy(color = colors.label)
            onDrawBehind {
                drawProfile(shapes, colors.line)
                drawBoundaries(series.boundariesM, series.totalM, plot, colors.axis)
                drawKmAxis(series.totalM, plot, measurer, label, colors.axis)
                drawEndLabel(measurer, top, label, colors.labelBackground, top = true, plot)
                drawEndLabel(measurer, bottom, label, colors.labelBackground, top = false, plot)
            }
        }
    }
    ChartFrame(
        totalM = series.totalM,
        description = description,
        tag = MetricTags.chart(kind),
        modifier = modifier.semantics { endLabels = listOf(top, bottom) },
        height = height,
        scrubbable = scrubbable,
        onOpen = onOpen,
        onScrub = onScrub,
        scrubM = scrubM,
        gestureKey = series,
        drawChart = drawChart,
        dotY = { d, plot -> series.valueAt(d)?.let { yOf(it, series, plot) } },
    )
}

/**
 * The band areas and the line of [series], sampled every [columnPx] across [plot] as [ElevationChart]'s profile is, but
 * broken wherever [MetricSeries.valueAt] is null: an area closes and the line lifts at the last column with a value and
 * both start again at the next one with a value.
 */
private fun seriesShapes(series: MetricSeries, plot: Size, columnPx: Float): ProfileShapes {
    val areas = ArrayList<Pair<Path, Color>>()
    val line = Path()
    if (plot.width <= 0f || plot.height <= 0f) return ProfileShapes(areas, line)
    val columns = max(1, (plot.width / columnPx).roundToInt())
    var area: Path? = null
    var areaColor = Color.Unspecified
    var lastX = 0f
    fun closeArea(x: Float) {
        area?.let {
            it.lineTo(x, plot.height)
            it.close()
            areas += it to areaColor
        }
        area = null
    }
    for (c in 0..columns) {
        val x = plot.width * c / columns
        val d = series.totalM * c / columns
        val v = series.valueAt(d)
        if (v == null) {
            closeArea(lastX)
            continue
        }
        val y = yOf(v, series, plot)
        val open = area
        // A column takes the colour of its middle, or of its start (which has one, as it has a value) where the middle
        // has none; the last column keeps the area's.
        val color = if (c == columns && open != null) {
            areaColor
        } else {
            colorOf(series.colorHexAt(series.totalM * (c + 0.5) / columns) ?: series.colorHexAt(d)!!)
        }
        if (open == null) {
            line.moveTo(x, y)
            area = Path().apply { moveTo(x, plot.height); lineTo(x, y) }
            areaColor = color
        } else {
            line.lineTo(x, y)
            open.lineTo(x, y)
            if (color != areaColor) {
                closeArea(x)
                area = Path().apply { moveTo(x, plot.height); lineTo(x, y) }
                areaColor = color
            }
        }
        lastX = x
    }
    closeArea(lastX)
    return ProfileShapes(areas, line)
}

/** The colour of "#RRGGBB" [hex]. */
internal fun colorOf(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/** The plot's y of [value]: [yIn] the series' range, at least [minSpan] of its kind tall so an even ride stays flat. */
private fun yOf(value: Double, series: MetricSeries, plot: Size): Float =
    yIn(value, series.minValue, series.maxValue, minSpan(series.kind), plot)

private fun minSpan(kind: MetricKind): Double = when (kind) {
    MetricKind.SPEED -> 10.0
    MetricKind.HEART_RATE -> 20.0
}

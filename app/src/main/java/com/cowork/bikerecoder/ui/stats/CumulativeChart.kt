package com.cowork.bikerecoder.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.ui.feed.groupedInt
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The cumulative line chart of the stats tab (V1.4.0 spec §3.3), drawn on a [Canvas] under the bar chart: the running total
 * from the first day of the period, [CumulativeChartState.current] as the thick line with a bubble at its end that says
 * [CumulativeChartState.endText], [CumulativeChartState.previous] as the grey line, and for a distance goal the dashed pace
 * line from 0 on the first day to the goal on the last day of the period. The axis runs over [CumulativeChartState.length] days;
 * [kind] names it. [metric] and [unitText] say what the values are. The plot reads as one sentence to a screen reader, and the
 * legend stands under it.
 */
@Composable
fun CumulativeChart(chart: CumulativeChartState, kind: PeriodKind, metric: StatMetric, unitText: String, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val previousColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val goalColor = primary.copy(alpha = 0.6f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val bubbleStyle = labelStyle.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val highest = niceTop(maxOf(chart.current.maxOrNull() ?: 0.0, chart.previous.maxOrNull() ?: 0.0, chart.goal ?: 0.0))
    val top = if (metric == StatMetric.TIME) timeTop(highest) else highest
    val tickTexts = tickTexts(metric, top)
    val names = cumulativeAxisNames(kind, chart.length)
    val middle = cumulativeMiddleIndex(kind, chart.currentDays)
    Column(modifier = modifier.fillMaxWidth().testTag(StatsTags.CUMULATIVE_CHART), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("누적", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(unitText, style = labelStyle, color = textColor, maxLines = 1)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(PLOT_HEIGHT)
                .testTag(StatsTags.CUMULATIVE_PLOT)
                .clearAndSetSemantics { contentDescription = chart.description },
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = with(density) { PLOT_HEIGHT.toPx() }
            val gapPx = with(density) { GAP.toPx() }
            val tickLayouts = tickTexts.map { measurer.measure(it, labelStyle, softWrap = false, maxLines = 1) }
            val nameLayouts = names.map { measurer.measure(it, labelStyle, softWrap = false, maxLines = 1) }
            val lineHeight = tickLayouts.maxOf { it.size.height }.toFloat()
            val tickWidth = tickLayouts.maxOf { it.size.width }.toFloat()
            // The plot: ticks on its left, names under it; the top tick is centred on the plot's top line.
            val left = tickWidth + gapPx
            val right = widthPx - gapPx
            val plotTop = lineHeight / 2
            val plotBottom = heightPx - lineHeight - gapPx
            val lastDay = maxOf(chart.length - 1, 1)
            fun xOf(day: Int) = left + (right - left) * day / lastDay
            fun yOf(value: Double) = plotBottom - (plotBottom - plotTop) * (value / top).toFloat()

            Canvas(modifier = Modifier.matchParentSize()) {
                listOf(0.0, top / 2, top).forEachIndexed { i, tick ->
                    val y = yOf(tick)
                    drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                    val layout = tickLayouts[i]
                    drawText(layout, textColor, Offset(tickWidth - layout.size.width, y - layout.size.height / 2f))
                }
                // Names: the first starts at the left of the plot, the middle one is centred on its own day, the last ends at its right.
                val nameY = plotBottom + gapPx
                drawText(nameLayouts[0], textColor, Offset(left, nameY))
                drawText(nameLayouts[1], textColor, Offset(xOf(middle) - nameLayouts[1].size.width / 2f, nameY))
                drawText(nameLayouts[2], textColor, Offset(right - nameLayouts[2].size.width, nameY))

                fun pathOf(values: List<Double>) = Path().apply {
                    values.forEachIndexed { day, value -> if (day == 0) moveTo(xOf(day), yOf(value)) else lineTo(xOf(day), yOf(value)) }
                }
                drawPath(
                    pathOf(chart.previous),
                    previousColor,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                chart.goal?.let {
                    drawLine(
                        goalColor,
                        Offset(xOf(0), yOf(0.0)),
                        Offset(xOf(maxOf(chart.currentDays - 1, 1)), yOf(it)),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    )
                }
                drawPath(
                    pathOf(chart.current),
                    primary,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // A single value (the first day of a period) is a path of no length: a dot says it is there.
                if (chart.current.size == 1) drawCircle(primary, 3.dp.toPx(), Offset(xOf(0), yOf(chart.current[0])))
            }
            if (chart.current.isNotEmpty()) {
                val bubbleText = measurer.measure(chart.endText, bubbleStyle, softWrap = false, maxLines = 1)
                val minDiameter = with(density) { BUBBLE_DIAMETER.toPx() }
                val padding = with(density) { 8.dp.toPx() }
                // A long value (or a big font) widens the circle; it never gets taller than the plot.
                val diameter = maxOf(minDiameter, bubbleText.size.width + padding).coerceAtMost(plotBottom - plotTop)
                val end = Offset(xOf(chart.current.lastIndex), yOf(chart.current.last()))
                val centre = bubbleCentre(end, diameter / 2, left, plotTop, right, plotBottom)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset { IntOffset((centre.x - diameter / 2).roundToInt(), (centre.y - diameter / 2).roundToInt()) }
                        .size(with(density) { diameter.toDp() })
                        .background(primary, CircleShape),
                ) {
                    Text(
                        chart.endText,
                        style = bubbleStyle,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
        Text(
            legendText(chart, primary, previousColor, goalColor, textColor),
            style = labelStyle,
            maxLines = 1,
            modifier = Modifier.testTag(StatsTags.CUMULATIVE_LEGEND),
        )
    }
}

/** "━ 이번 달  ━ 지난달  ┅ 목표", each mark in the colour of its line; the goal is left out when there is none. */
private fun legendText(
    chart: CumulativeChartState,
    current: Color,
    previous: Color,
    goal: Color,
    text: Color,
) = buildAnnotatedString {
    fun entry(mark: String, color: Color, name: String) {
        withStyle(SpanStyle(color = color)) { append(mark) }
        withStyle(SpanStyle(color = text)) { append(" $name") }
    }
    entry("━", current, chart.currentName)
    append("  ")
    entry("━", previous, chart.previousName)
    if (chart.goal != null) {
        append("  ")
        entry("┅", goal, "목표")
    }
}

/** The steps of the top tick within a power of ten (and the next power of ten). */
private val TICK_STEPS = listOf("1", "1.2", "1.5", "2", "2.5", "3", "4", "5", "6", "8", "10").map(::BigDecimal)

/**
 * The top tick of the value axis: the smallest of 1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8 times a power of ten that is at least
 * [max] plus 5 %; 1 when [max] is 0 (an empty period still has a scale to draw on). The middle tick is half of it.
 */
internal fun niceTop(max: Double): Double {
    if (max <= 0.0) return 1.0
    val wanted = max * 1.05
    val power = floor(log10(wanted)).toInt()
    // Exact decimals: 1.2 x 10^-3 is 0.0012, not 0.0012000000000000001.
    return TICK_STEPS.map { it.scaleByPowerOfTen(power).toDouble() }.first { it >= wanted - 1e-9 * wanted }
}

/** The shortest time scale: two minutes, so that the middle tick is a whole minute away from 0 and from the top ("0:00" "0:01" "0:02"). */
internal fun timeTop(top: Double): Double = maxOf(top, 2.0 / 60)

/**
 * The texts of the ticks 0, [top] / 2 and [top] ([top] in the unit of [metric]): exact decimals ("0" "1.25" "2.5", thousands
 * grouped) so that no two are the same, time as "h:mm" ("0:00" "1:15" "2:30") like the end bubble.
 */
internal fun tickTexts(metric: StatMetric, top: Double): List<String> {
    val topValue = BigDecimal.valueOf(top)
    val values = listOf(BigDecimal.ZERO, topValue.divide(BigDecimal(2)), topValue)
    if (metric == StatMetric.TIME) {
        return values.map {
            val minutes = it.multiply(BigDecimal(60)).setScale(0, RoundingMode.HALF_UP).toInt()
            "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
        }
    }
    return values.map {
        val plain = it.stripTrailingZeros()
        if (plain.scale() <= 0) groupedInt(plain.toBigInteger().toInt()) else plain.toPlainString()
    }
}

/**
 * Where the end bubble of radius [radius] is centred: on the line's end [point], pushed inwards so the whole circle stays
 * inside the plot ([left], [top], [right], [bottom]); a circle that does not fit is centred on the plot.
 */
internal fun bubbleCentre(point: Offset, radius: Float, left: Float, top: Float, right: Float, bottom: Float): Offset {
    fun keep(value: Float, low: Float, high: Float) = if (low > high) (low + high) / 2 else value.coerceIn(low, high)
    return Offset(keep(point.x, left + radius, right - radius), keep(point.y, top + radius, bottom - radius))
}

private val PLOT_HEIGHT = 160.dp
private val BUBBLE_DIAMETER = 32.dp
private val GAP = 4.dp

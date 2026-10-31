package com.cowork.bikerecoder.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/**
 * The bar chart of the stats tab (V1.4.0 spec §3.3), drawn on a [Canvas]: one bar per entry of [bars], as tall as its
 * `value` against the largest. A bar that has not come yet is left empty (its label stays). [selected] is the bar
 * whose `valueText` is shown above the chart; a tap on a bar calls [onSelect] with its index. [unitText] stands in the
 * top left corner, and each bar's `shortText` right above it ([valueLabelIndices] says which are written).
 */
@Composable
fun BarChart(
    bars: List<BarState>,
    selected: Int?,
    unitText: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val largest = bars.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: 1.0
    val primary = MaterialTheme.colorScheme.primary
    val dimmed = primary.copy(alpha = 0.4f)
    val ground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The room above the tallest bar for its value: 16 dp, more when the font is scaled up.
    val valueRoom = max(VALUE_ROOM, with(density) { labelStyle.lineHeight.toDp() })
    Column(modifier = modifier.fillMaxWidth().testTag(StatsTags.CHART)) {
        Text(
            selected?.let { bars.getOrNull(it)?.valueText } ?: " ",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().height(28.dp).testTag(StatsTags.BAR_VALUE),
            textAlign = TextAlign.Center,
        )
        Text(
            unitText,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.testTag(StatsTags.BAR_UNIT),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columnPx = with(density) { maxWidth.toPx() } / bars.size
            val gapPx = with(density) { LABEL_GAP.toPx() }
            val widthOf: (String) -> Float = { measurer.measure(it, labelStyle, softWrap = false, maxLines = 1).size.width.toFloat() }
            val written = valueLabelIndices(bars, selected, columnPx, gapPx, widthOf)
            // The names that carry a year (the first bar and every January of a monthly [전체]) are kept before the others.
            val withYear = bars.indices.filter { '’' in bars[it].label }.toSet()
            val named = axisLabelIndices(bars.map { it.label }, selected, withYear, columnPx, gapPx, widthOf)
            Row(modifier = Modifier.fillMaxWidth()) {
                bars.forEachIndexed { index, bar ->
                    val barHeight: Dp = when {
                        bar.upcoming || bar.value <= 0.0 -> 0.dp
                        else -> maxOf(BAR_AREA * (bar.value / largest).toFloat(), 2.dp)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !bar.upcoming) { onSelect(index) }
                            // The tag stays outside of the clearing: it would be cleared too.
                            .testTag(StatsTags.bar(index))
                            // One reading per bar: the value line, not the value above it and the name below it as well.
                            .clearAndSetSemantics {
                                contentDescription = bar.valueText
                                this.selected = index == selected
                            },
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().height(valueRoom + BAR_AREA + GROUND_HEIGHT)) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                val groundHeight = GROUND_HEIGHT.toPx()
                                drawRect(ground, Offset(0f, size.height - groundHeight), Size(size.width, groundHeight))
                                if (barHeight > 0.dp) {
                                    val barWidth = size.width * 0.6f
                                    drawRoundRect(
                                        color = if (selected == null || selected == index) primary else dimmed,
                                        topLeft = Offset((size.width - barWidth) / 2, size.height - groundHeight - barHeight.toPx()),
                                        size = Size(barWidth, barHeight.toPx()),
                                        cornerRadius = CornerRadius(3.dp.toPx()),
                                    )
                                }
                            }
                            if (index in written) {
                                Text(
                                    bar.shortText,
                                    style = labelStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = GROUND_HEIGHT + barHeight)
                                        .wrapContentWidth(unbounded = true)
                                        .testTag(StatsTags.barShort(index)),
                                )
                            }
                        }
                        Text(
                            if (index in named) bar.label else " ",
                            style = labelStyle,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected == index) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

/** From this many bars on, only the tallest bar and the selected one are written. */
private const val MANY_BARS = 13

/**
 * The bars whose `shortText` is written above them: in the order selected, tallest (the first, if several are), then left to
 * right, each one that stays inside the chart (`bars.size` columns of [columnPx]) and keeps [gapPx] clear of those already
 * written. A text may reach past its own column. With [MANY_BARS] or more only the selected and the tallest are tried.
 * [widthPx] is the width of a text.
 */
internal fun valueLabelIndices(
    bars: List<BarState>,
    selected: Int?,
    columnPx: Float,
    gapPx: Float,
    widthPx: (String) -> Float,
): Set<Int> {
    val tallest = bars.indices.filter { !bars[it].upcoming && bars[it].value > 0.0 }.maxByOrNull { bars[it].value }
    val first = listOfNotNull(selected?.takeIf { it in bars.indices }, tallest)
    val order = if (bars.size >= MANY_BARS) first else first + bars.indices
    return keepApart(order.distinct().filter { bars[it].shortText.isNotEmpty() }, columnPx, gapPx, bars.size * columnPx) {
        widthPx(bars[it].shortText)
    }
}

/**
 * The axis names that are written under the bars: tried in the order selected, first bar, the [emphasized] ones, then left
 * to right; a name stays out when it would come within [gapPx] of one already written (so the selected name is always
 * there). [widthPx] is the width of a name; each is centred under its column of [columnPx].
 */
internal fun axisLabelIndices(
    labels: List<String>,
    selected: Int?,
    emphasized: Set<Int>,
    columnPx: Float,
    gapPx: Float,
    widthPx: (String) -> Float,
): Set<Int> {
    val order = (listOfNotNull(selected, 0) + emphasized.sorted() + labels.indices).distinct().filter { it in labels.indices }
    return keepApart(order, columnPx, gapPx, null) { widthPx(labels[it]) }
}

/** The indices of [order] that fit, each centred on its column: inside [withinPx] (if given) and [gapPx] clear of those kept. */
private fun keepApart(order: List<Int>, columnPx: Float, gapPx: Float, withinPx: Float?, widthOf: (Int) -> Float): Set<Int> {
    val kept = LinkedHashMap<Int, ClosedFloatingPointRange<Float>>()
    for (index in order) {
        val width = widthOf(index)
        val centre = (index + 0.5f) * columnPx
        val left = centre - width / 2
        val right = centre + width / 2
        if (withinPx != null && (left < 0f || right > withinPx)) continue
        if (kept.values.any { left < it.endInclusive + gapPx && right + gapPx > it.start }) continue
        kept[index] = left..right
    }
    return kept.keys
}

private val BAR_AREA = 120.dp
private val GROUND_HEIGHT = 1.5.dp
private val VALUE_ROOM = 16.dp
private val LABEL_GAP = 4.dp

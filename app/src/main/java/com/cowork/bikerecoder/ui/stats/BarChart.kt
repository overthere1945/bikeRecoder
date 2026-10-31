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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
    val labelStep = (bars.size + MAX_AXIS_LABELS - 1) / MAX_AXIS_LABELS
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
            val written = valueLabelIndices(bars, selected, columnPx) {
                measurer.measure(it, labelStyle, softWrap = false, maxLines = 1).size.width.toFloat()
            }
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
                            .semantics(mergeDescendants = true) {
                                contentDescription = bar.valueText
                                this.selected = index == selected
                            }
                            .testTag(StatsTags.bar(index)),
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
                        val named = index % labelStep == 0 || index == selected
                        Text(
                            if (named) bar.label else " ",
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

/** The most axis names to write; with more bars every `ceil(n / 12)`th gets its name. */
private const val MAX_AXIS_LABELS = 12

/**
 * The bars whose `shortText` is written above them. With 12 bars or fewer every bar that has one, but only where the text fits
 * the bar's column of [columnPx] (it is left out, never shrunk or clipped). With [MANY_BARS] or more only the tallest bar
 * (the first, if several are) and the [selected] one, which may reach into the neighbouring columns (three columns wide) and
 * leave out the tallest when the two would touch. [widthPx] is the width of a text.
 */
internal fun valueLabelIndices(bars: List<BarState>, selected: Int?, columnPx: Float, widthPx: (String) -> Float): Set<Int> {
    val many = bars.size >= MANY_BARS
    val candidates: List<Int> = if (!many) {
        bars.indices.toList()
    } else {
        val tallest = bars.indices.filter { !bars[it].upcoming && bars[it].value > 0.0 }.maxByOrNull { bars[it].value }
        // maxByOrNull keeps the first of equals; the selected bar goes last so it wins a clash.
        listOfNotNull(tallest, selected?.takeIf { it != tallest && it in bars.indices })
    }
    val room = if (many) columnPx * 3 else columnPx
    val fitting = candidates.filter { bars[it].shortText.isNotEmpty() && widthPx(bars[it].shortText) <= room }
    if (many && fitting.size == 2) {
        val (a, b) = fitting
        val touching = kotlin.math.abs(a - b) * columnPx < (widthPx(bars[a].shortText) + widthPx(bars[b].shortText)) / 2
        if (touching) return setOf(b)
    }
    return fitting.toSet()
}

private val BAR_AREA = 120.dp
private val GROUND_HEIGHT = 1.5.dp
private val VALUE_ROOM = 16.dp

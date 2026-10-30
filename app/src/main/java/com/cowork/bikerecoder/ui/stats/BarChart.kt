package com.cowork.bikerecoder.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The bar chart of the stats tab (V1.4.0 spec §3.3), drawn on a [Canvas]: one bar per entry of [bars], as tall as its
 * `value` against the largest. A bar that has not come yet is left empty (its label stays). [selected] is the bar
 * whose `valueText` is shown above the chart; a tap on a bar calls [onSelect] with its index.
 */
@Composable
fun BarChart(bars: List<BarState>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val largest = bars.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: 1.0
    val primary = MaterialTheme.colorScheme.primary
    val dimmed = primary.copy(alpha = 0.4f)
    val ground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Column(modifier = modifier.fillMaxWidth().testTag(StatsTags.CHART)) {
        Text(
            selected?.let { bars.getOrNull(it)?.valueText } ?: " ",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().height(28.dp).testTag(StatsTags.BAR_VALUE),
            textAlign = TextAlign.Center,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            bars.forEachIndexed { index, bar ->
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
                    Canvas(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
                        val groundHeight = 1.5.dp.toPx()
                        drawRect(ground, Offset(0f, size.height - groundHeight), Size(size.width, groundHeight))
                        if (!bar.upcoming) {
                            val barWidth = size.width * 0.6f
                            val room = size.height - groundHeight
                            val height = when {
                                bar.value <= 0.0 -> 0f
                                else -> maxOf((bar.value / largest).toFloat() * room, 2.dp.toPx())
                            }
                            if (height > 0f) {
                                drawRoundRect(
                                    color = if (selected == null || selected == index) primary else dimmed,
                                    topLeft = Offset((size.width - barWidth) / 2, room - height),
                                    size = Size(barWidth, height),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                )
                            }
                        }
                    }
                    Text(
                        bar.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (selected == index) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val CHART_HEIGHT = 120.dp

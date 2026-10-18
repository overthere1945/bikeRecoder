package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.recording.ChartPoint
import java.util.Locale

/**
 * A line chart on a Compose [Canvas] (no chart library): horizontal axis = distance (km, from 0 so charts of one
 * segment line up), vertical axis labelled with the lowest and highest value in [unit]. Without points it says so.
 */
@Composable
fun LineChart(points: List<ChartPoint>, unit: String, modifier: Modifier) {
    if (points.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("기록이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val minValue = points.minOf { it.value }
    val maxValue = points.maxOf { it.value }
    val maxKm = points.last().distanceKm
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxHeight().padding(end = 4.dp), horizontalAlignment = Alignment.End) {
                Text("${axisValue(maxValue)}$unit", style = labelStyle, color = labelColor)
                Spacer(Modifier.weight(1f))
                Text("${axisValue(minValue)}$unit", style = labelStyle, color = labelColor)
            }
            Canvas(Modifier.weight(1f).fillMaxSize()) {
                // A flat line is drawn in the middle.
                val span = (maxValue - minValue).takeIf { it > 0.0 }
                val xOf = { km: Double -> if (maxKm > 0.0) (km / maxKm * size.width).toFloat() else 0f }
                val yOf = { v: Double ->
                    if (span == null) size.height / 2 else ((maxValue - v) / span * size.height).toFloat()
                }
                drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                if (points.size == 1) {
                    drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(xOf(points[0].distanceKm), yOf(points[0].value)))
                    return@Canvas
                }
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = xOf(p.distanceKm)
                    val y = yOf(p.value)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0km", style = labelStyle, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text("${axisKm(maxKm)}km", style = labelStyle, color = labelColor)
        }
    }
}

private fun axisValue(v: Double) = String.format(Locale.ROOT, "%.0f", v)

private fun axisKm(km: Double) = String.format(Locale.ROOT, "%.1f", km)

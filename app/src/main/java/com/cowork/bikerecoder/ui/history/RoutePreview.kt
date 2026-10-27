package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlin.math.cos

private val PREVIEW_SIZE = 72.dp
private val PREVIEW_PADDING = 6.dp
private val PREVIEW_LINE = 2.5.dp
private val PREVIEW_CORNER = 8.dp

/**
 * A trip's route shape without map tiles (spec §6.11): every route of [routes] (points and "#RRGGBB" colour) drawn as a
 * 2.5 dp line, all fitted together into a 72 dp square with 6 dp padding, north up, keeping the shape's aspect. With no
 * route only the pale rounded background.
 */
@Composable
fun RoutePreview(routes: List<Pair<List<GeoPoint>, String>>, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surface
    Spacer(
        modifier = modifier.size(PREVIEW_SIZE).drawWithCache {
            val all = fitToBox(routes.flatMap { it.first }, size.width, size.height, PREVIEW_PADDING.toPx())
            var from = 0
            val lines = routes.map { (points, color) ->
                val offsets = all.subList(from, from + points.size)
                from += points.size
                offsets to Color(android.graphics.Color.parseColor(color))
            }
            val stroke = Stroke(width = PREVIEW_LINE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val corner = CornerRadius(PREVIEW_CORNER.toPx())
            onDrawBehind {
                drawRoundRect(background, cornerRadius = corner)
                for ((offsets, color) in lines) {
                    when (offsets.size) {
                        0 -> Unit
                        1 -> drawCircle(color, radius = stroke.width / 2, center = offsets[0])
                        else -> drawPath(
                            Path().apply {
                                moveTo(offsets[0].x, offsets[0].y)
                                for (i in 1 until offsets.size) lineTo(offsets[i].x, offsets[i].y)
                            },
                            color,
                            style = stroke,
                        )
                    }
                }
            }
        },
    )
}

/**
 * [points] placed in a [w] × [h] box inside [pad] on every side, keeping their shape: longitude is shortened by the
 * cosine of the middle latitude (so a square on the ground stays square), the scale is the largest that fits, the
 * shape is centred and north is up. A single place (or a line with no width or height) is centred across.
 */
fun fitToBox(points: List<GeoPoint>, w: Float, h: Float, pad: Float): List<Offset> {
    if (points.isEmpty()) return emptyList()
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val k = cos(Math.toRadians((minLat + maxLat) / 2))
    val xs = points.map { it.lon * k }
    val minX = xs.min()
    val spanX = xs.max() - minX
    val spanY = maxLat - minLat
    val innerW = (w - 2 * pad).toDouble()
    val innerH = (h - 2 * pad).toDouble()
    val scale = when {
        spanX > 0 && spanY > 0 -> minOf(innerW / spanX, innerH / spanY)
        spanX > 0 -> innerW / spanX
        spanY > 0 -> innerH / spanY
        else -> 0.0
    }
    val left = pad + (innerW - spanX * scale) / 2
    val top = pad + (innerH - spanY * scale) / 2
    return points.mapIndexed { i, p ->
        Offset((left + (xs[i] - minX) * scale).toFloat(), (top + (maxLat - p.lat) * scale).toFloat())
    }
}

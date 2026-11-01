package com.cowork.bikerecoder.video

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.ui.activity.ActivityStyle
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The activity badge on the video's title card (V1.9.0 spec §2.1, §5.1): a rounded square in the badge colours of the
 * light theme with the activity's Material icon on it. The icon is the screens' own [ActivityStyle.icon] vector, drawn by
 * its path data (the vectors of these icons are plain fills without groups or transforms), so the video and the app
 * show the same picture.
 */
internal object TitleBadge {

    /** The icon's share of the badge's side, as in the app's badge. */
    private const val ICON_SHARE = 0.65f

    /** The corner radius per side: 10 dp on the 34 dp card badge. */
    private const val CORNER_SHARE = 10f / 34f

    private val paths = ConcurrentHashMap<ActivityType, IconShape>()

    /** [type]'s badge with its top left at ([left], [top]), [size] pixels wide, at opacity [alpha] (0–1). */
    fun draw(canvas: Canvas, type: ActivityType, left: Float, top: Float, size: Float, alpha: Float) {
        val (background, content) = ActivityStyle.badgeColors(type, dark = false)
        val box = RectF(left, top, left + size, top + size)
        val radius = size * CORNER_SHARE
        canvas.drawRoundRect(box, radius, radius, fill(background.toArgb(), alpha))

        val icon = shapeOf(type)
        val side = size * ICON_SHARE
        val scale = side / icon.viewport
        canvas.save()
        canvas.translate(left + (size - side) / 2, top + (size - side) / 2)
        canvas.scale(scale, scale)
        val paint = fill(content.toArgb(), alpha)
        icon.paths.forEach { canvas.drawPath(it, paint) }
        canvas.restore()
    }

    private fun fill(color: Int, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = (this.alpha * alpha.coerceIn(0f, 1f)).roundToInt()
    }

    private fun shapeOf(type: ActivityType): IconShape = paths.getOrPut(type) { IconShape.of(ActivityStyle.icon(type)) }

    /** An icon as Android paths (each with its own fill rule) in viewport units, and the viewport's side. */
    private class IconShape(val paths: List<Path>, val viewport: Float) {
        companion object {
            fun of(vector: ImageVector): IconShape {
                val out = ArrayList<Path>()
                fun collect(group: VectorGroup) {
                    for (node in group) when (node) {
                        is VectorPath -> {
                            val shape = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                            shape.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                            out += shape
                        }
                        is VectorGroup -> collect(node)
                    }
                }
                collect(vector.root)
                return IconShape(out, vector.viewportWidth)
            }
        }
    }
}

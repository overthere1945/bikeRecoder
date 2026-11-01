package com.cowork.bikerecoder.video

import androidx.compose.ui.graphics.toArgb
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.RidePoint
import com.cowork.bikerecoder.core.video.RideTimeline
import com.cowork.bikerecoder.ui.activity.ActivityStyle
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/** The feed picture's route lines take their segment's activity colour (V1.9.0 spec §2.2). */
class FeedThumbnailColorsTest {

    private fun point(segmentIndex: Int) = RidePoint(0, GeoPoint(37.5, 127.0), null, 0L, segmentIndex, 0)

    @Test
    fun eachPointTakesItsSegmentsActivityColour() {
        val timeline = RideTimeline(listOf(point(0), point(0), point(1), point(2)))
        val colors = FeedThumbnailRenderer.lineColors(timeline, listOf(ActivityType.RUN, ActivityType.HIKE))

        assertArrayEquals(
            intArrayOf(
                ActivityStyle.color(ActivityType.RUN).toArgb(),
                ActivityStyle.color(ActivityType.RUN).toArgb(),
                ActivityStyle.color(ActivityType.HIKE).toArgb(),
                ActivityStyle.color(ActivityType.BIKE).toArgb(), // a segment the list does not know: a bike's
            ),
            colors,
        )
    }
}

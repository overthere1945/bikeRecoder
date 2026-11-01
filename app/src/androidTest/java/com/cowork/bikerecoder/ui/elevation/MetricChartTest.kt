package com.cowork.bikerecoder.ui.elevation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.MetricSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** The shared speed / heart-rate chart (V1.8.0 spec §2.1). */
class MetricChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** [hillTrack]'s 3 km at 2 s a point, the speed rising from 0 to 36 km/h and back down. */
    private val track: List<TrackPoint> = hillTrack().mapIndexed { i, p ->
        p.copy(speedMps = (if (i <= 150) i / 15f else (300 - i) / 15f))
    }
    private val axis = TrackAxis.of(listOf(track))!!
    private val speed = MetricSeries.speed(axis, listOf(track))!!

    /** A heart rate every 2 s, but none from 200 s to 400 s (points 100 to 200): no bpm from about 1.15 to 1.85 km. */
    private val heart = MetricSeries.heartRate(
        axis,
        listOf(track),
        track.filter { it.timeMillis < 200_000L || it.timeMillis > 400_000L }
            .map { HeartRateSample(it.timeMillis, 120 + (it.timeMillis / 10_000L).toInt()) },
    )!!

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            MaterialTheme { Surface { Column(Modifier.padding(16.dp)) { content() } } }
        }
    }

    @Test
    fun speedNullShowsText() {
        var opened = 0
        show { MetricChart(null, MetricKind.SPEED, "속도 그래프", onOpen = { opened++ }) }

        composeRule.onNodeWithText("속도 정보가 없습니다").assertIsDisplayed()
        composeRule.onNodeWithTag(MetricTags.chart(MetricKind.SPEED)).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).assertDoesNotExist()
        assertEquals(0, opened)
    }

    @Test
    fun heartNullDrawsNothing() {
        show {
            Column(Modifier.testTag(HOST)) { MetricChart(null, MetricKind.HEART_RATE, "심박 그래프", onOpen = {}) }
        }

        composeRule.onNodeWithTag(HOST).assertHeightIsEqualTo(0.dp)
        composeRule.onNodeWithTag(MetricTags.chart(MetricKind.HEART_RATE)).assertDoesNotExist()
        composeRule.onNodeWithText("속도 정보가 없습니다").assertDoesNotExist()
    }

    @Test
    fun endLabelsAreMinMax() {
        show {
            MetricChart(speed, MetricKind.SPEED, "속도 그래프")
            MetricChart(heart, MetricKind.HEART_RATE, "심박 그래프")
        }

        // The smoothed series' highest on top, its lowest at the bottom.
        val top = ChartText.valueLabel(MetricKind.SPEED, speed.maxValue)
        val bottom = ChartText.valueLabel(MetricKind.SPEED, speed.minValue)
        assertNotEquals(top, bottom)
        assertTrue(top.endsWith("km/h") && bottom.endsWith("km/h"))
        composeRule.onNodeWithTag(MetricTags.chart(MetricKind.SPEED))
            .assert(SemanticsMatcher.expectValue(MetricTags.EndLabels, listOf(top, bottom)))
        composeRule.onNodeWithTag(MetricTags.chart(MetricKind.HEART_RATE)).assert(
            SemanticsMatcher.expectValue(
                MetricTags.EndLabels,
                listOf(
                    ChartText.valueLabel(MetricKind.HEART_RATE, heart.maxValue),
                    ChartText.valueLabel(MetricKind.HEART_RATE, heart.minValue),
                ),
            ),
        )
    }

    @Test
    fun smallChartOpens() {
        var opened = 0
        show { MetricChart(speed, MetricKind.SPEED, "속도 그래프", onOpen = { opened++ }) }

        composeRule.onNodeWithTag(MetricTags.chart(MetricKind.SPEED))
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsEqualTo(120.dp)
            .performClick()
        assertEquals(1, opened)

        composeRule.onNodeWithText("지도에 표시").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).performClick()
        assertEquals(2, opened)
        composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun scrubReportsDistance() {
        val scrubbed = mutableListOf<Double>()
        var at by mutableStateOf<Double?>(null)
        show {
            MetricChart(
                heart,
                MetricKind.HEART_RATE,
                "심박 그래프",
                height = 160.dp,
                scrubbable = true,
                onScrub = { scrubbed += it; at = it },
                scrubM = at,
            )
        }

        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).assertDoesNotExist()
        composeRule.onNodeWithTag(MetricTags.chart(MetricKind.HEART_RATE)).performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            moveTo(Offset(width * 0.4f, height / 2f))
            // Into the run without heart rate: the line is still drawn there.
            moveTo(Offset(width * 0.5f, height / 2f))
            up()
        }
        composeRule.waitForIdle()

        assertTrue(scrubbed.size >= 2)
        assertEquals(heart.totalM * 0.25, scrubbed.first(), heart.totalM * 0.02)
        assertEquals(heart.totalM * 0.5, scrubbed.last(), heart.totalM * 0.02)
        assertNull(heart.valueAt(scrubbed.last()))
        composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).assertIsDisplayed()
        val chart = composeRule.onNodeWithTag(MetricTags.chart(MetricKind.HEART_RATE)).getBoundsInRoot()
        val line = composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).getBoundsInRoot()
        val lineCenter = (line.left + line.right) / 2 - chart.left
        assertEquals((chart.right - chart.left).value * 0.5f, lineCenter.value, (chart.right - chart.left).value * 0.03f)

        at = null
        composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun descriptionIsRead() {
        val description = ChartText.heartDescription(132, 171)
        show { MetricChart(heart, MetricKind.HEART_RATE, description, onOpen = {}) }

        composeRule.onNodeWithTag(MetricTags.chart(MetricKind.HEART_RATE)).assertContentDescriptionEquals(description)
    }

    @Test
    fun nullRunBreaksTheLine() {
        var surface = Color.Unspecified
        show {
            surface = MaterialTheme.colorScheme.surface
            MetricChart(heart, MetricKind.HEART_RATE, "심박 그래프")
        }
        assertNull(heart.valueAt(heart.totalM * 0.5))
        assertTrue(heart.valueAt(heart.totalM * 0.2) != null)

        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(MetricTags.chart(MetricKind.HEART_RATE)).captureToImage().toPixelMap()
        // The plot is all but the km axis under it; stop 1dp short of its base line.
        val plotBottom = with(composeRule.density) { image.height - (AXIS_HEIGHT + 1.dp).roundToPx() }
        // Neither fill nor line in the middle of the run without heart rate, from top to bottom of the plot.
        val mid = image.width / 2
        for (y in 0 until plotBottom) assertClose(surface, image[mid, y], "x=$mid y=$y")
        // Where there is heart rate, the fill is drawn.
        assertTrue(far(surface, image[image.width / 5, plotBottom - 2]))
    }

    private fun assertClose(expected: Color, actual: Color, where: String) {
        assertTrue("$where: $actual is not the background $expected", !far(expected, actual))
    }

    private fun far(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > 0.06f

    private companion object {
        const val HOST = "metric_host"
    }
}

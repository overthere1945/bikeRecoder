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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.elevation.ClimbFinder
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A 3 km ride north-east from Yeouido: flat, 600 m at 8% up, flat, then a gentle 5% down. */
internal fun hillTrack(): List<TrackPoint> {
    val start = GeoPoint(37.5260, 126.9240)
    val stepM = 10.0
    return List(301) { i ->
        val d = i * stepM
        val ele = when {
            d <= 1_000.0 -> 20.0
            d <= 1_600.0 -> 20.0 + (d - 1_000.0) * 0.08
            d <= 2_000.0 -> 68.0
            else -> 68.0 - (d - 2_000.0) * 0.05
        }
        val meters = d / 111_320.0
        TrackPoint(i * 2_000L, GeoPoint(start.lat + meters * 0.7, start.lon + meters * 0.9), ele, 5f, 5f)
    }
}

internal fun hillProfile(): ElevationProfile = ElevationProfile.fromSegments(listOf(hillTrack()))!!

/** The shared elevation chart and the guidance strip (V1.7.0 spec §2.1, §3.2). */
class ElevationChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val profile = hillProfile()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            MaterialTheme { Surface { Column(Modifier.padding(16.dp)) { content() } } }
        }
    }

    @Test
    fun noProfileShowsText() {
        var opened = 0
        show { ElevationChart(null, onOpen = { opened++ }) }

        composeRule.onNodeWithText("고도 정보가 없습니다").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.CHART).assertDoesNotExist()
        assertEquals(0, opened)
    }

    @Test
    fun smallChartOpens() {
        var opened = 0
        show { ElevationChart(profile, onOpen = { opened++ }) }

        composeRule.onNodeWithTag(ElevationTags.CHART)
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals(1, opened)

        composeRule.onNodeWithText("지도에 표시").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).performClick()
        assertEquals(2, opened)
        // The small chart does not scrub.
        composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun scrubReportsDistanceAndShowsLine() {
        val scrubbed = mutableListOf<Double>()
        show { ElevationChart(profile, height = 160.dp, scrubbable = true, onScrub = { scrubbed += it }) }

        composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.CHART).performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            moveTo(Offset(width * 0.5f, height / 2f))
            moveTo(Offset(width * 0.75f, height / 2f))
            up()
        }
        composeRule.waitForIdle()

        assertTrue(scrubbed.size >= 2)
        assertEquals(profile.totalM * 0.25, scrubbed.first(), profile.totalM * 0.02)
        assertEquals(profile.totalM * 0.75, scrubbed.last(), profile.totalM * 0.02)
        // Kept after the finger lifts.
        composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun descriptionReadsMinMaxClimbs() {
        show { ElevationChart(profile, onOpen = {}) }

        assertEquals(1, ClimbFinder.find(profile).size)
        val expected = "고도 그래프, 최저 ${altitude(profile.minElevationM)}, 최고 ${altitude(profile.maxElevationM)}, 오르막 1곳"
        assertEquals(expected, ElevationText.chartDescription(profile.minElevationM, profile.maxElevationM, 1))
        composeRule.onNodeWithTag(ElevationTags.CHART).assertContentDescriptionEquals(expected)
    }

    @Test
    fun stripDimmed() {
        var dimmed by mutableStateOf(true)
        val ahead = profile.slice(500.0, 2_500.0)
        show { ElevationStrip(ahead, reachesDestination = true, dimmed = dimmed) }

        composeRule.onNodeWithTag(ElevationTags.STRIP)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(ElevationTags.Dimmed, true))
        composeRule.onNodeWithTag(ElevationTags.STRIP_FLAG).assertIsDisplayed()

        dimmed = false
        composeRule.onNodeWithTag(ElevationTags.STRIP).assert(SemanticsMatcher.expectValue(ElevationTags.Dimmed, false))
    }

    private fun altitude(m: Double) = RideFormat.altitude(m)
}

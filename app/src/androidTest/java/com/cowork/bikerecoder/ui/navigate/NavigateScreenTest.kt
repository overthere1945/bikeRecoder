package com.cowork.bikerecoder.ui.navigate

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.model.TurnType
import com.cowork.bikerecoder.core.navigation.AheadOfRider
import com.cowork.bikerecoder.core.navigation.AheadState
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.navigation.Progress
import com.cowork.bikerecoder.ui.elevation.ElevationTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The guidance panel's "앞길 고도" line and strip (V1.7.0 spec §3.2, §3.4), on the panel itself with a fixed state. */
class NavigateScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 6 km north with a point every 250 m: flat, a 1.2 km climb at about 6% from 2 km, then rolling down. */
    private val route: Route = run {
        val n = 25
        val elevations = (0 until n).map { i ->
            val km = i * 0.25
            val climb = 72.0 * ((km - 2.0) / 1.2).coerceIn(0.0, 1.0)
            val down = -40.0 * ((km - 4.0) / 2.0).coerceIn(0.0, 1.0)
            30.0 + climb + down
        }
        Route(
            points = (0 until n).map { GeoPoint(37.50 + it * 0.00225, 127.03) },
            cumulativeM = (0 until n).map { it * 250.0 },
            instructions = listOf(Instruction(8, TurnType.RIGHT, 0, 2_000.0)),
            stopPointIndices = listOf(n - 1),
            summary = RouteSummary(6_000.0, 72, 0.8, RouteProfile.CYCLEWAY_FIRST),
            elevationsM = elevations,
        )
    }
    private val profile = ElevationProfile.fromRoute(route)!!

    private fun nav(
        d: Double,
        ahead: AheadState?,
        withProfile: Boolean = true,
        rerouting: Boolean = false,
    ) = NavState(
        route = route,
        progress = Progress(d, 3.0, 6_000.0 - d, route.instructions.first().takeIf { d < 2_000.0 }, (2_000.0 - d).takeIf { it > 0 }),
        speedMps = 5.2,
        etaMillis = 1_767_000_000_000L,
        gpsWeak = false,
        rerouting = rerouting,
        profile = if (withProfile) profile else null,
        ahead = ahead,
    )

    private fun showPanel(state: NavState) {
        composeRule.setContent {
            MaterialTheme {
                BottomPanel(
                    nav = state,
                    recording = false,
                    muted = false,
                    onMute = {},
                    onOverview = {},
                    onEnd = {},
                    onCancelGuidance = {},
                )
            }
        }
    }

    private fun stripAlpha(): Float =
        composeRule.onNodeWithTag(ElevationTags.STRIP).fetchSemanticsNode().config[ElevationTags.StripAlpha]

    @Test
    fun aheadLineClimbAhead() {
        showPanel(nav(1_700.0, AheadState.ClimbAhead(inM = 300.0, lengthM = 1_200.0, avgGradePct = 5.6)))

        composeRule.onNodeWithText("300m 앞 오르막 · 1.2km · 평균 6%").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.STRIP).assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.STRIP_FLAG).assertDoesNotExist()
        assertEquals(1f, stripAlpha())
        composeRule.onNodeWithText("남은 거리").assertIsDisplayed()
    }

    @Test
    fun aheadLineInClimb() {
        // While a reroute runs the strip is dimmed.
        showPanel(nav(2_750.0, AheadState.InClimb(remainingM = 450.0, avgGradePct = 5.5), rerouting = true))

        composeRule.onNodeWithText("오르막 남은 거리 450m · 평균 6%").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.STRIP).assertIsDisplayed()
        assertEquals(0.4f, stripAlpha())
    }

    @Test
    fun aheadLineFlat() {
        // 1.5 km before the destination: the strip ends there, with the flag.
        showPanel(nav(4_500.0, AheadState.Flat))

        composeRule.onNodeWithText("앞 2 km 평탄").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.STRIP).assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.STRIP_FLAG).assertIsDisplayed()
        assertEquals(1f, stripAlpha())
    }

    @Test
    fun stripHiddenWithoutElevation() {
        showPanel(nav(1_700.0, ahead = null, withProfile = false))

        composeRule.onNodeWithTag(NavigateTags.AHEAD_LINE).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.STRIP).assertDoesNotExist()
        composeRule.onNodeWithText("앞 2 km 평탄").assertDoesNotExist()
        // The panel as before V1.7.0.
        composeRule.onNodeWithText("남은 거리").assertIsDisplayed()
        composeRule.onNodeWithText("도착 예정").assertIsDisplayed()
        composeRule.onNodeWithText("속도").assertIsDisplayed()
        composeRule.onNodeWithText("전체 경로").assertIsDisplayed()
    }

    /**
     * Download/bikeRecoderTest/v170-navigate.png: the turn card over a grey map placeholder and the panel with a climb
     * 300 m ahead (the real map is left out: its tiles stall in the test process).
     */
    @Test
    fun screenshot() {
        val d = 1_700.0
        val state = nav(d, AheadState.ClimbAhead(inM = 300.0, lengthM = 1_200.0, avgGradePct = 5.6))
        assertEquals(AheadOfRider.WINDOW_M, AheadOfRider.strip(profile, d)!!.totalM, 1e-6)
        composeRule.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFCFD8DC))) {
                            Box(Modifier.padding(12.dp)) { TurnCard(state) }
                        }
                        Box(Modifier.padding(12.dp)) {
                            BottomPanel(
                                nav = state,
                                recording = false,
                                muted = false,
                                onMute = {},
                                onOverview = {},
                                onEnd = {},
                                onCancelGuidance = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(NavigateTags.AHEAD_LINE).assertIsDisplayed()
        screenshot("v170-navigate")
    }

    /** The whole screen into Download/bikeRecoderTest/[name].png (an older file of the name is deleted first). */
    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val resolver = instrumentation.targetContext.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/bikeRecoderTest/"
        runCatching {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("$name.png", relative),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "NavigateScreenTest"
    }
}

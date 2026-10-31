package com.cowork.bikerecoder.ui.elevation

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.elevation.GradeBand
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.ui.history.NoTiles
import com.cowork.bikerecoder.ui.history.ReadOnlyRides
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The "고도" map screen (V1.7.0 spec §2.2) on its real view model, opened from the plan preview's handoff: the legend,
 * scrubbing (the line under the chart and the dot on the map), [×] and going back with nothing to show.
 */
class ElevationMapScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val track = hillTrack()
    private val profile = hillProfile()
    private val path = track.map { it.point }
    private val handoff = ElevationHandoff()

    /** What the map slot was last given. */
    @Volatile private var content: ElevationMapContent? = null
    private var closed = 0

    private fun viewModel(handOver: Boolean = true): ElevationMapViewModel {
        if (handOver) handoff.put(profile, path)
        return ElevationMapViewModel(ElevationSource.Plan, ReadOnlyRides(emptyList()), handoff)
    }

    private fun show(vm: ElevationMapViewModel) {
        composeRule.setContent {
            MaterialTheme {
                ElevationMapScreen(
                    viewModel = vm,
                    tileSource = NoTiles,
                    onClose = { closed++ },
                    map = { c, modifier -> content = c; Box(modifier) },
                )
            }
        }
        composeRule.waitUntil(5_000) { content != null }
    }

    @Test
    fun legendHasSevenBands() {
        show(viewModel())

        composeRule.onNodeWithText("고도").assertIsDisplayed()
        composeRule.onAllNodesWithTag(ElevationTags.LEGEND_CELL).assertCountEquals(7)
        GradeBand.entries.forEach { composeRule.onNodeWithText(it.legend).assertIsDisplayed() }
        composeRule.onNodeWithTag(ElevationTags.SCRUB_TEXT).assertDoesNotExist()
        val c = content!!
        assertEquals(listOf(path), c.paths)
        assertEquals(path.size, c.colors.single().size)
        assertEquals(path.first() to path.last(), c.endpoints)
        assertNull(c.marker)
    }

    @Test
    fun scrubShowsLine() {
        val vm = viewModel()
        show(vm)

        composeRule.onNodeWithTag(ElevationTags.CHART).performTouchInput {
            down(Offset(width * 0.4f, height / 2f))
            moveTo(Offset(width * 0.45f, height / 2f))
            up()
        }
        composeRule.waitForIdle()

        val d = requireNotNull((vm.uiState.value as ElevationMapUiState.Ready).scrubM)
        assertEquals(profile.totalM * 0.45, d, profile.totalM * 0.02)
        composeRule.onNodeWithTag(ElevationTags.SCRUB_TEXT)
            .assertIsDisplayed()
            .assertTextEquals(ElevationText.scrubLine(d, profile.elevationAt(d), profile.gradeAt(d)))
        composeRule.onAllNodesWithTag(ElevationTags.LEGEND_CELL).assertCountEquals(0)
        composeRule.onNodeWithTag(ElevationTags.SCRUB_LINE, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(profile.pointAt(d), content!!.marker)
    }

    @Test
    fun closeGoesBack() {
        show(viewModel())

        composeRule.onNodeWithContentDescription("닫기").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.CLOSE).performClick()
        composeRule.waitForIdle()

        assertEquals(1, closed)
    }

    @Test
    fun planWithoutHandoffGoesBackOnce() {
        val vm = viewModel(handOver = false)
        composeRule.setContent {
            MaterialTheme { ElevationMapScreen(vm, NoTiles, onClose = { closed++ }, map = { _, m -> Box(m) }) }
        }
        composeRule.waitUntil(5_000) { closed > 0 }
        composeRule.waitForIdle()

        assertEquals(1, closed)
    }

    /** Download/bikeRecoderTest/v170-elevation-map.png: the real map, scrubbed to the top of the climb. */
    @Test
    fun screenshot() {
        val vm = viewModel()
        val tiles = OpenFreeMapSource(OkHttpClient(), cacheFile = null)
        composeRule.setContent {
            MaterialTheme { ElevationMapScreen(vm, tiles, onClose = { closed++ }) }
        }
        composeRule.waitUntil(5_000) { vm.uiState.value is ElevationMapUiState.Ready }
        composeRule.onNodeWithTag(ElevationTags.CHART).performTouchInput {
            down(Offset(width * 0.52f, height / 2f))
            up()
        }
        // Tiles and the framing animation.
        Thread.sleep(10_000)
        screenshot("v170-elevation-map")
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
        const val TAG = "ElevationMapScreenTest"
    }
}

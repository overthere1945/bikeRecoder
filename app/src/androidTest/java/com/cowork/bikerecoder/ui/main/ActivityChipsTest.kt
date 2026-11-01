package com.cowork.bikerecoder.ui.main

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.ui.activity.ActivityChips
import com.cowork.bikerecoder.ui.activity.ActivityText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The map screen's activity chips (V1.9.0 spec §1.4, §2.1): four in order, the chosen one selected, each read as its
 * activity's name, and the choice kept in the settings (`last_activity`) for the next time the screen opens. Uses the
 * device's settings DataStore: the chip's value is put back after each test. Screenshot `v190-chips`
 * (Download/bikeRecoderTest).
 */
class ActivityChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
    private lateinit var before: ActivityType

    @Before
    fun keepTheChip() = runBlocking { before = repo.settings.first().lastActivity }

    @After
    fun putTheChipBack() = runBlocking { repo.setLastActivity(before) }

    private val names = listOf("자전거", "걷기", "달리기", "등산")

    @Test
    fun fourChipsInOrder() {
        composeRule.setContent { MaterialTheme { ActivityChips(ActivityType.BIKE, onSelect = {}) } }

        names.forEach { composeRule.onNodeWithContentDescription(it).assertIsDisplayed() }
        val lefts = names.map { composeRule.onNodeWithContentDescription(it).getBoundsInRoot().left }
        assertEquals(lefts.sorted(), lefts)
        assertEquals(4, lefts.distinct().size)
    }

    @Test
    fun theChosenChipIsSelected() {
        var selected by mutableStateOf(ActivityType.BIKE)
        composeRule.setContent { MaterialTheme { ActivityChips(selected, onSelect = { selected = it }) } }
        composeRule.onNodeWithContentDescription("자전거").assertIsSelected()

        composeRule.onNodeWithContentDescription("달리기").performClick()

        assertEquals(ActivityType.RUN, selected)
        composeRule.onNodeWithContentDescription("달리기").assertIsSelected()
        listOf("자전거", "걷기", "등산").forEach { composeRule.onNodeWithContentDescription(it).assertIsNotSelected() }
    }

    @Test
    fun eachChipIsReadAsTheActivityName() {
        composeRule.setContent { MaterialTheme { ActivityChips(ActivityType.HIKE, onSelect = {}) } }

        ActivityType.entries.forEach { type ->
            val name = ActivityText.name(type)
            composeRule.onNodeWithContentDescription(name).assertContentDescriptionEquals(name)
            // The badge inside is not read a second time.
            val read = composeRule.onAllNodesWithContentDescription(name, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(1, read.size)
        }
    }

    @Test
    fun theChoiceIsRememberedWhenTheScreenOpensAgain() {
        runBlocking { repo.setLastActivity(ActivityType.BIKE) }
        var shown by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (shown) {
                    val saved by remember { repo.settings.map { it.lastActivity } }.collectAsState(initial = null)
                    val scope = rememberCoroutineScope()
                    saved?.let { ActivityChips(it, onSelect = { type -> scope.launch { repo.setLastActivity(type) } }) }
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("등산").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("등산").performClick()
        composeRule.waitUntil(5_000) { runBlocking { repo.settings.first().lastActivity } == ActivityType.HIKE }
        shown = false
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithContentDescription("등산").fetchSemanticsNodes().isEmpty())
        shown = true
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("등산").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("등산").assertIsSelected()
        composeRule.onNodeWithContentDescription("자전거").assertIsNotSelected()
    }

    /** Download/bikeRecoderTest/v190-chips.png: the chips with a non-bike chip (running) selected. */
    @Test
    fun screenshotChips() {
        composeRule.setContent {
            MaterialTheme { Surface { ActivityChips(ActivityType.RUN, onSelect = {}) } }
        }
        composeRule.onNodeWithContentDescription("달리기").assertIsSelected()
        screenshot("v190-chips")
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
        const val TAG = "ActivityChipsTest"
    }
}

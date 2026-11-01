package com.cowork.bikerecoder.ui.main

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NoOffline
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.ui.activity.ActivityChipTags
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.activity.ActivityTypeButton
import com.cowork.bikerecoder.ui.activity.ActivityTypeTags
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The map screen's activity type button and its sheet (V1.9.1 §A, replacing the four chips of V1.9.0 spec §1.4): read out as
 * "활동 종류, {이름}. 눌러서 바꾸기", a sheet of four rows (the chosen one selected), the choice kept in the settings
 * (`last_activity`) for the next time the screen opens, and the button gone while a recording runs, as [● 기록 시작] is. Uses the
 * device's settings DataStore: the value is put back after each test. Screenshots `v191-type-button` and `v191-type-sheet`
 * (Download/bikeRecoderTest).
 */
class ActivityTypeButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repo = SettingsRepository(context)
    private lateinit var before: ActivityType
    private lateinit var db: AppDatabase

    @Before
    fun keepTheSetting() = runBlocking {
        before = repo.settings.first().lastActivity
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun putTheSettingBack() = runBlocking {
        repo.setLastActivity(before)
        db.close()
    }

    private val names = listOf("자전거", "걷기", "달리기", "등산")

    private fun button() = composeRule.onNodeWithTag(ActivityTypeTags.BUTTON)
    private fun row(type: ActivityType) = composeRule.onNodeWithTag(ActivityTypeTags.row(type))

    @Test
    fun theButtonIsReadWithTheActivityAndHowToChangeIt() {
        var selected by mutableStateOf(ActivityType.BIKE)
        composeRule.setContent { MaterialTheme { ActivityTypeButton(selected, onSelect = {}) } }

        ActivityType.entries.forEach { type ->
            selected = type
            composeRule.waitForIdle()
            button().assertContentDescriptionEquals("활동 종류, ${ActivityText.name(type)}. 눌러서 바꾸기")
            button().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            button().assertHasClickAction()
            // The name is written on it, and the icon inside is not read a second time.
            button().assert(hasText(ActivityText.name(type)))
        }
    }

    @Test
    fun tappingOpensTheSheetWithTheFourActivitiesInOrder() {
        composeRule.setContent { MaterialTheme { ActivityTypeButton(ActivityType.WALK, onSelect = {}) } }
        assertTrue(composeRule.onAllNodesWithTag(ActivityTypeTags.SHEET).fetchSemanticsNodes().isEmpty())

        button().performClick()

        composeRule.onNodeWithTag(ActivityTypeTags.SHEET).assertIsDisplayed()
        composeRule.onNodeWithText("활동 종류").assertIsDisplayed()
        val tops = ActivityType.entries.map { type ->
            row(type).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            row(type).assert(hasText(ActivityText.name(type)))
            row(type).getBoundsInRoot().top
        }
        assertEquals(tops.sorted(), tops)
        assertEquals(names.size, tops.distinct().size)
    }

    @Test
    fun theChosenRowIsSelectedAndEveryRowIsARadioButton() {
        composeRule.setContent { MaterialTheme { ActivityTypeButton(ActivityType.RUN, onSelect = {}) } }
        button().performClick()

        row(ActivityType.RUN).assertIsSelected()
        listOf(ActivityType.BIKE, ActivityType.WALK, ActivityType.HIKE).forEach { row(it).assertIsNotSelected() }
        ActivityType.entries.forEach {
            row(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
    }

    @Test
    fun aRowChoosesTheActivityAndClosesTheSheet() {
        var selected by mutableStateOf(ActivityType.BIKE)
        composeRule.setContent { MaterialTheme { ActivityTypeButton(selected, onSelect = { selected = it }) } }
        button().performClick()

        row(ActivityType.HIKE).performClick()
        composeRule.waitForIdle()

        assertEquals(ActivityType.HIKE, selected)
        assertTrue(composeRule.onAllNodesWithTag(ActivityTypeTags.SHEET).fetchSemanticsNodes().isEmpty())
        button().assertContentDescriptionEquals("활동 종류, 등산. 눌러서 바꾸기")
        // Opened again, the new activity is the selected row.
        button().performClick()
        row(ActivityType.HIKE).assertIsSelected()
        row(ActivityType.BIKE).assertIsNotSelected()
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
                    saved?.let { ActivityTypeButton(it, onSelect = { type -> scope.launch { repo.setLastActivity(type) } }) }
                }
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ActivityTypeTags.BUTTON).fetchSemanticsNodes().isNotEmpty() }

        button().performClick()
        row(ActivityType.HIKE).performClick()
        composeRule.waitUntil(5_000) { runBlocking { repo.settings.first().lastActivity } == ActivityType.HIKE }
        shown = false
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag(ActivityTypeTags.BUTTON).fetchSemanticsNodes().isEmpty())
        shown = true
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ActivityTypeTags.BUTTON).fetchSemanticsNodes().isNotEmpty() }

        button().assertContentDescriptionEquals("활동 종류, 등산. 눌러서 바꾸기")
    }

    // The map screen around the button.

    private val blank = object : TileSource {
        override val styleUrl = "blank"
        override val attribution = ""
        override suspend fun styleJson(): String =
            """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#dfe6d8"}}]}"""
    }

    private val noSearch = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> = error("not searched")
        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> = error("not searched")
        override suspend fun nearby(point: GeoPoint): SearchResult<List<Place>> = error("not searched")
    }

    private val ride = MutableStateFlow<RideUiState>(RideUiState.Idle)
    private val lastActivity = MutableStateFlow(ActivityType.BIKE)

    private fun recording() = RideUiState.Active(
        tripId = 4,
        tripType = TripType.SINGLE_DAY,
        segmentId = 1,
        state = RecordingState.Recording,
        stats = RideStats.ZERO,
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )

    private fun showMainScreen() {
        val trips = RoomTripStore(db)
        val viewModel = MainViewModel(
            TripManager(trips, NoOffline, clock = System::currentTimeMillis), trips, noSearch, MutableStateFlow(NavUiState.Idle),
            ride = ride, lastActivity = lastActivity, saveActivity = { lastActivity.value = it },
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier) {
                    MainScreen(
                        viewModel = viewModel,
                        tileSource = blank,
                        currentLocation = MutableStateFlow<LocationFix?>(null),
                        onOpenSearch = {},
                        onResumeTrip = {},
                        onShowRide = {},
                        onStartRecording = {},
                        onContinueRecording = {},
                        onSetDestination = { _, _ -> },
                        onAddWaypoint = { _, _ -> },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun startButton() = composeRule.onNode(hasClickAction() and hasText("● 기록 시작"))

    @Test
    fun theMapScreenHasTheTypeButtonLeftOfStartAndNoChips() {
        showMainScreen()

        button().assertIsDisplayed()
        startButton().assertIsDisplayed()
        val type = button().getBoundsInRoot()
        val start = startButton().getBoundsInRoot()
        assertTrue("the type button is left of [● 기록 시작]", type.right <= start.left)
        // Same height, one row.
        assertEquals((start.bottom - start.top).value, (type.bottom - type.top).value, 1f)
        assertEquals(start.top.value, type.top.value, 1f)
        ActivityType.entries.forEach {
            assertTrue(composeRule.onAllNodesWithTag(ActivityChipTags.chip(it)).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun theMapScreenButtonFollowsTheSavedActivity() {
        lastActivity.value = ActivityType.RUN
        showMainScreen()

        button().assertContentDescriptionEquals("활동 종류, 달리기. 눌러서 바꾸기")
        button().performClick()
        row(ActivityType.WALK).performClick()
        composeRule.waitForIdle()

        assertEquals(ActivityType.WALK, lastActivity.value)
        button().assertContentDescriptionEquals("활동 종류, 걷기. 눌러서 바꾸기")
    }

    @Test
    fun theButtonIsHiddenWhileARecordingRunsAsStartIs() {
        showMainScreen()
        button().assertIsDisplayed()
        startButton().assertIsDisplayed()

        ride.value = recording()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag(ActivityTypeTags.BUTTON).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("● 기록 시작").fetchSemanticsNodes().isEmpty())

        ride.value = RideUiState.Idle
        composeRule.waitForIdle()
        button().assertIsDisplayed()
        startButton().assertIsDisplayed()
    }

    /** Download/bikeRecoderTest/v191-type-button.png and v191-type-sheet.png: the map screen with a non-bike activity (running). */
    @Test
    fun screenshotButtonAndSheet() {
        lastActivity.value = ActivityType.RUN
        showMainScreen()
        button().assertContentDescriptionEquals("활동 종류, 달리기. 눌러서 바꾸기")
        screenshot("v191-type-button")

        button().performClick()
        row(ActivityType.RUN).assertIsSelected()
        screenshot("v191-type-sheet")
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
        const val TAG = "ActivityTypeButtonTest"
    }
}

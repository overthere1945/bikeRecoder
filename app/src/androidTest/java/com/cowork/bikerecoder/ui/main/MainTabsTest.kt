package com.cowork.bikerecoder.ui.main

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.stats.Streak
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NoOffline
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.ui.Routes
import com.cowork.bikerecoder.ui.feed.FeedCardState
import com.cowork.bikerecoder.ui.feed.FeedContent
import com.cowork.bikerecoder.ui.feed.FeedMonth
import com.cowork.bikerecoder.ui.feed.FeedTags
import com.cowork.bikerecoder.ui.feed.WeekStripState
import com.cowork.bikerecoder.ui.rideRoute
import com.cowork.bikerecoder.ui.settings.SettingsContent
import com.cowork.bikerecoder.ui.settings.SettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The tab frame of the main screen (V1.4.0 spec §1.1, §1.4) as BikeNavHost builds it: the real map screen (an empty
 * trip database, a blank style), the stateless summary feed and settings, a stand-in for the stats tab, and a trip and a
 * record screen on top of main.
 */
class MainTabsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: AppDatabase
    private lateinit var mainViewModel: MainViewModel
    private var ride by mutableStateOf<RideUiState>(RideUiState.Idle)
    private lateinit var nav: NavHostController

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

    private val trip = FeedCardState(
        tripId = 3,
        title = "저녁 라이딩 · 서울 강서구",
        dateLine = "10월 25일 (일) 오후 6:12",
        inProgress = false,
        distanceM = 32_100.0,
        movingTimeMs = 6_300_000,
        ascentM = 123.4,
        avgSpeedMps = 32_100.0 / 6_300.0,
        avgBpm = null,
        photoMediaIds = emptyList(),
        morePhotos = 0,
        video = null,
        trophy = false,
        thumbKey = null,
        thumbFile = null,
        routes = listOf(listOf(GeoPoint(37.50, 127.00), GeoPoint(37.52, 127.03), GeoPoint(37.51, 127.08)) to "#FF6D00"),
    )
    private var feedMonths = listOf(FeedMonth("2026년 10월 · 32.10km · 1회", listOf(trip)))
    private val strip = WeekStripState(64_200.0, 4, 12_300.0, 100, Streak(4, includesThisWeek = true))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val trips = RoomTripStore(db)
        mainViewModel = MainViewModel(TripManager(trips, NoOffline, clock = System::currentTimeMillis), trips, noSearch, MutableStateFlow(NavUiState.Idle))
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun recording(state: RecordingState = RecordingState.Recording) = RideUiState.Active(
        tripId = 4,
        tripType = TripType.SINGLE_DAY,
        segmentId = 1,
        state = state,
        stats = RideStats.ZERO.copy(distanceM = 12_400.0, movingTimeMs = 48 * 60_000L + 30_000L),
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )

    /** The main route (tabs) with a trip screen and the record screen above it, like BikeNavHost's. */
    @Composable
    private fun Host() {
        val controller = rememberNavController().also { nav = it }
        NavHost(controller, startDestination = Routes.MAIN) {
            composable(Routes.MAIN) { entry ->
                val isCurrent = { controller.currentBackStackEntry?.id == entry.id }
                MainTabs(
                    band = rideBandText(ride, NavUiState.Idle),
                    onBand = { rideRoute(NavUiState.Idle, ride)?.let { if (isCurrent()) controller.navigate(it) } },
                ) { tab, scrollToTop, select ->
                    when (tab) {
                        MainTab.MAP -> MainScreen(
                            viewModel = mainViewModel,
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
                        MainTab.FEED -> FeedContent(
                            weekStrip = strip,
                            months = feedMonths,
                            ready = true,
                            hiddenShortCount = 0,
                            dialog = null,
                            photo = { _, _ -> null },
                            onOpenTrip = { tripId -> if (isCurrent()) controller.navigate("trip/$tripId") },
                            onOpenVideo = {},
                            onOpenStatsWeek = { select(MainTab.STATS) },
                            onStartRecording = { select(MainTab.MAP) },
                            onSetGoals = {},
                            onRequestDelete = {},
                            onConfirmDelete = {},
                            onDismiss = {},
                            onRequestCleanup = {},
                            onConfirmCleanup = {},
                            scrollToTop = scrollToTop,
                        )
                        MainTab.STATS -> Box(Modifier.fillMaxSize()) {
                            Text("통계 맨 위로 $scrollToTop", modifier = Modifier.testTag(STATS_SCROLL))
                        }
                        MainTab.SETTINGS -> SettingsContent(
                            state = SettingsUiState(),
                            versionDisplay = "V1.4.0",
                            onBack = null,
                            onSetProfile = {},
                            onSetVoice = {},
                            onSetKeepScreenOn = {},
                            onSetWifiOnly = {},
                            onSetAutoStartRecording = {},
                            onSetAutoPause = {},
                            onCheckUpdate = {},
                            onDownload = {},
                            onDeleteSegment = {},
                            onDeleteAllMaps = {},
                        )
                    }
                }
            }
            composable("trip/{id}") { Text("여행 기록 화면") }
            composable(Routes.RECORD_PATTERN) { Text("기록 화면") }
        }
    }

    private fun show() {
        composeRule.setContent { MaterialTheme { Host() } }
        composeRule.waitForIdle()
    }

    private fun tab(tab: MainTab) = composeRule.onNodeWithTag(MainTabsTags.tab(tab))

    private fun back() {
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** Nothing would handle back: the system's back leaves the app (the activity finishes). */
    private fun backLeavesTheApp(): Boolean {
        composeRule.waitForIdle()
        var leaves = false
        composeRule.runOnUiThread { leaves = !composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return leaves
    }

    @Test
    fun movesBetweenTheFourTabs() {
        show()
        for (t in MainTab.entries) tab(t).assertIsDisplayed()
        for ((t, name) in listOf(MainTab.MAP to "지도", MainTab.FEED to "요약", MainTab.STATS to "통계", MainTab.SETTINGS to "설정")) {
            composeRule.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
            assertTrue(t.label == name)
        }
        tab(MainTab.MAP).assertIsSelected()
        composeRule.onNodeWithText("장소, 주소 검색").assertIsDisplayed()

        tab(MainTab.FEED).performClick()
        tab(MainTab.FEED).assertIsSelected()
        tab(MainTab.MAP).assertIsNotSelected()
        composeRule.onNodeWithText("이번 주 64.2 km · 4회").assertIsDisplayed()
        composeRule.onNodeWithText("장소, 주소 검색").assertDoesNotExist()

        tab(MainTab.STATS).performClick()
        composeRule.onNodeWithTag(STATS_SCROLL).assertTextEquals("통계 맨 위로 0")

        tab(MainTab.SETTINGS).performClick()
        tab(MainTab.SETTINGS).assertIsSelected()
        composeRule.onNodeWithText("기본 경로 성향").assertIsDisplayed()
        // The settings tab has no back arrow (spec §1.1).
        composeRule.onNodeWithContentDescription("뒤로").assertDoesNotExist()

        tab(MainTab.MAP).performClick()
        composeRule.onNodeWithText("장소, 주소 검색").assertIsDisplayed()
    }

    /** The map screen's MapView (there is one while [지도] shows). */
    private fun mapView(): MapView {
        fun find(view: View): MapView? = view as? MapView ?: (view as? ViewGroup)?.children?.firstNotNullOfOrNull { find(it) }
        var found: MapView? = null
        composeRule.runOnUiThread { found = find(composeRule.activity.window.decorView) }
        return checkNotNull(found) { "no map on screen" }
    }

    /** [block] on the map once it is ready, on the main thread. */
    private fun <T> onMap(block: (MapLibreMap) -> T): T {
        val view = mapView()
        val result = CompletableFuture<T>()
        composeRule.runOnUiThread { view.getMapAsync { result.complete(block(it)) } }
        return result.get(10, TimeUnit.SECONDS)
    }

    /** Review I1: back on [지도] after another tab the map is where it was left (center, zoom, bearing), not on the user. */
    @Test
    fun theMapKeepsItsPlaceAcrossTabs() {
        show()
        val there = CameraPosition.Builder().target(LatLng(35.1, 129.0)).zoom(15.5).bearing(42.0).build()
        onMap { it.moveCamera(CameraUpdateFactory.newCameraPosition(there)) }
        // The camera stops and is kept by the tab.
        Thread.sleep(1_000)
        composeRule.waitForIdle()

        tab(MainTab.FEED).performClick()
        composeRule.onNodeWithText("이번 주 64.2 km · 4회").assertIsDisplayed()
        tab(MainTab.MAP).performClick()
        composeRule.onNodeWithText("장소, 주소 검색").assertIsDisplayed()

        val back = onMap { it.cameraPosition }
        assertEquals(35.1, back.target!!.latitude, 1e-6)
        assertEquals(129.0, back.target!!.longitude, 1e-6)
        assertEquals(15.5, back.zoom, 1e-6)
        assertEquals(42.0, back.bearing, 1e-6)
    }

    @Test
    fun tappingTheOpenTabAgainScrollsUpOnlyThere() {
        show()
        tab(MainTab.STATS).performClick()
        tab(MainTab.STATS).performClick()
        tab(MainTab.STATS).performClick()
        composeRule.onNodeWithTag(STATS_SCROLL).assertTextEquals("통계 맨 위로 2")

        // The map again: nothing happens (it stays the map).
        tab(MainTab.MAP).performClick()
        tab(MainTab.MAP).performClick()
        tab(MainTab.MAP).assertIsSelected()
        composeRule.onNodeWithText("장소, 주소 검색").assertIsDisplayed()
        tab(MainTab.STATS).performClick()
        composeRule.onNodeWithTag(STATS_SCROLL).assertTextEquals("통계 맨 위로 2")
    }

    @Test
    fun backOnTheSummaryGoesToTheMapAndThenLeaves() {
        show()
        assertTrue("the map tab of a fresh main screen lets back leave the app", backLeavesTheApp())

        tab(MainTab.FEED).performClick()
        assertFalse(backLeavesTheApp())
        back()
        tab(MainTab.MAP).assertIsSelected()
        composeRule.onNodeWithText("장소, 주소 검색").assertIsDisplayed()
        assertTrue(backLeavesTheApp())

        for (t in listOf(MainTab.STATS, MainTab.SETTINGS)) {
            tab(t).performClick()
            back()
            tab(MainTab.MAP).assertIsSelected()
        }
    }

    @Test
    fun backFromATripReturnsToTheSummary() {
        show()
        tab(MainTab.FEED).performClick()
        composeRule.onNodeWithTag(FeedTags.card(3)).performClick()
        composeRule.onNodeWithText("여행 기록 화면").assertIsDisplayed()

        back()

        tab(MainTab.FEED).assertIsSelected()
        composeRule.onNodeWithText("이번 주 64.2 km · 4회").assertIsDisplayed()
    }

    @Test
    fun theTabSurvivesRotation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { MaterialTheme { Host() } }
        tab(MainTab.STATS).performClick()
        tab(MainTab.STATS).assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()

        tab(MainTab.STATS).assertIsSelected()
        composeRule.onNodeWithTag(STATS_SCROLL).assertIsDisplayed()
    }

    @Test
    fun startRecordingOnTheEmptySummaryOpensTheMap() {
        feedMonths = emptyList()
        show()
        tab(MainTab.FEED).performClick()
        composeRule.onNodeWithText("첫 주행을 기록해 보세요").assertIsDisplayed()

        composeRule.onNodeWithText("기록 시작").performClick()

        tab(MainTab.MAP).assertIsSelected()
        composeRule.onNodeWithText("● 기록 시작").assertIsDisplayed()
    }

    @Test
    fun theMapHasNoMenuButton() {
        show()
        composeRule.onNodeWithText("장소, 주소 검색").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("메뉴").assertDoesNotExist()
        composeRule.onNodeWithText("☰").assertDoesNotExist()
        composeRule.onNodeWithText("누적 지도").assertDoesNotExist()
    }

    @Test
    fun bandHidesOnTheMapShowsElsewhereAndOpensTheRecordScreen() {
        ride = recording()
        show()
        composeRule.onNodeWithTag(MainTabsTags.BAND).assertDoesNotExist()

        tab(MainTab.FEED).performClick()
        composeRule.onNodeWithTag(MainTabsTags.BAND).assertIsDisplayed()
        composeRule.onNodeWithText("● 기록 중 · 12.4 km · 48분").assertIsDisplayed()
        tab(MainTab.STATS).performClick()
        composeRule.onNodeWithTag(MainTabsTags.BAND).assertIsDisplayed()

        ride = recording(RecordingState.Paused(PauseKind.MANUAL))
        composeRule.onNodeWithText("Ⅱ 일시정지 · 12.4 km · 48분").assertIsDisplayed()
        ride = RideUiState.Idle
        composeRule.onNodeWithTag(MainTabsTags.BAND).assertDoesNotExist()

        ride = recording()
        composeRule.onNodeWithTag(MainTabsTags.BAND).performClick()
        composeRule.onNodeWithText("기록 화면").assertIsDisplayed()
        assertEquals(Routes.RECORD_PATTERN, nav.currentDestination?.route)
    }

    /** The summary starts below the status bar and ends above the band and the tab bar (edge-to-edge as the app). */
    @Test
    fun tabContentKeepsClearOfTheSystemBarsAndTheBand() {
        ride = recording()
        show()
        tab(MainTab.FEED).performClick()
        val density = composeRule.activity.resources.displayMetrics.density
        var statusBarPx = 0
        composeRule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)!!
            statusBarPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        }
        assertTrue("the test device has a status bar", statusBarPx > 0)

        val strip = composeRule.onNodeWithText("이번 주 64.2 km · 4회").getBoundsInRoot()
        assertTrue("the strip (${strip.top}) is below the status bar ($statusBarPx px)", strip.top >= Dp(statusBarPx / density))
        val band = composeRule.onNodeWithTag(MainTabsTags.BAND).getBoundsInRoot()
        val bar = tab(MainTab.FEED).getBoundsInRoot()
        assertTrue("the band sits above the tab bar", band.bottom <= bar.top)
    }

    /** For the record: Download/bikeRecoderTest/v140-band.png (pulled by hand), the summary with the recording band. */
    @Test
    fun screenshotBand() {
        ride = recording()
        show()
        tab(MainTab.FEED).performClick()
        composeRule.onNodeWithTag(MainTabsTags.BAND).assertIsDisplayed()
        composeRule.waitForIdle()
        Thread.sleep(500)
        screenshot("v140-band")
    }

    /** The whole screen into Download/bikeRecoderTest/[name].png (an older file of the name is deleted first). */
    private fun screenshot(name: String) {
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
        const val TAG = "MainTabsTest"
        const val STATS_SCROLL = "stats-scroll"
    }
}

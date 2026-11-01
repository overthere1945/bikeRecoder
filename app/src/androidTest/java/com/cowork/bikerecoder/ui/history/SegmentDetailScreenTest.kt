package com.cowork.bikerecoder.ui.history

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.HealthAvailability
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ui.elevation.ChartTab
import com.cowork.bikerecoder.ui.elevation.ChartText
import com.cowork.bikerecoder.ui.elevation.ElevationTags
import com.cowork.bikerecoder.ui.elevation.MetricTags
import com.cowork.bikerecoder.ui.health.HeartRateGate
import com.cowork.bikerecoder.ui.health.TrackColorTags
import com.cowork.bikerecoder.ui.photo.fakeThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The segment detail's heart rate on its real view model (fake stores, fake Health Connect): [기본] [심박] only with
 * detailed heart rate, the colours and their key, and the permission card / "Health Connect를 사용할 수 없습니다" in
 * place of "심박 가져오는 중" (spec §3.3, §3.4, ruling R8).
 */
class SegmentDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val segment = RideSegment(
        id = 1, tripId = 1, startedAt = 1_000L, endedAt = 4_000L, localDate = LocalDate.of(2026, 10, 18),
        totals = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000),
    )
    private val rides = ReadOnlyRides(listOf(segment), pointsPerSegment = 4, elevationAt = { 20.0 + it * 5.0 })
    private val postStore = FakePostStore { listOf(segment) }
    private val heartRate = FakeHeartRateSource()
    private val processor = PostProcessor(
        rides, postStore, FakePhotoSource(), heartRate, clock = { 0L }, scope = scope, warnLog = { _, _ -> },
    )
    private val settings = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    /** What the map slot was last given. */
    @Volatile private var content: DetailMapContent? = null

    @Before
    fun setUp() = runBlocking { settings.update { it.copy(heartRatePromptDismissed = false) } }

    @After
    fun tearDown() = scope.cancel()

    private fun ready(detailed: Boolean) = runBlocking {
        postStore.replaceSamples(1, listOf(HeartRateSample(1_000L, 95), HeartRateSample(4_000L, 165)))
        postStore.saveExtras(
            SegmentExtras(
                1, hrStatus = HrStatus.READY, hrLastTriedAt = 1L, hrDetailed = detailed, avgBpm = 130, maxBpm = 165,
                calories = 54.0,
            ),
        )
    }

    private fun show(gate: HeartRateGate? = null, onOpenChart: (ChartTab) -> Unit = {}) {
        val vm = SegmentDetailViewModel(rides, 1, postStore, processor)
        composeRule.setContent {
            MaterialTheme {
                SegmentDetailScreen(
                    vm, NoTiles, fakeThumbnails(), onBack = {}, onOpenChart = onOpenChart, heartRateGate = gate,
                ) { c, modifier ->
                    content = c
                    Box(modifier.testTag(MAP))
                }
            }
        }
        composeRule.waitUntil(5_000) { content?.track?.isNotEmpty() == true }
    }

    /** V1.7.0 spec §2.4: the single-colour altitude line chart is the small elevation chart. */
    @Test
    fun elevationChartReplacesLineChart() {
        val opened = mutableListOf<ChartTab>()
        show(onOpenChart = { opened += it })

        composeRule.onNodeWithText("고도").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.CHART).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("고도 정보가 없습니다").assertDoesNotExist()
        // The old chart wrote its lowest and highest value (20m, 35m) as text; the elevation chart draws them.
        composeRule.onNodeWithText("35m").assertDoesNotExist()

        showOnMapIn(ElevationTags.CHART).performScrollTo().performClick()
        composeRule.onNodeWithTag(ElevationTags.CHART).performClick()
        assertEquals(listOf(ChartTab.ELEVATION, ChartTab.ELEVATION), opened)
    }

    /**
     * V1.8.0 spec §2.3: the speed and heart-rate line charts are [MetricChart]s in the same place under the same titles,
     * read out with the segment's stored figures, each opening the big map on its own tab.
     */
    @Test
    fun metricChartsReplaceLineCharts() {
        // Not detailed: no [심박] chip on the map to share the chart's title.
        ready(detailed = false)
        val opened = mutableListOf<ChartTab>()
        show(onOpenChart = { opened += it })
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertExists() }.isSuccess
        }

        // The scrolling column lays everything out; positions (unlike bounds) are not clipped to its viewport.
        val elevation = span(composeRule.onNodeWithTag(ElevationTags.CHART))
        val speedTitle = span(composeRule.onNodeWithText(ChartText.TITLE_SPEED))
        val speed = span(composeRule.onNodeWithTag(MetricTags.SPEED_CHART))
        val heartTitle = span(composeRule.onNodeWithText(ChartText.TITLE_HEART))
        val heart = span(composeRule.onNodeWithTag(MetricTags.HEART_CHART))
        assertTrue("속도 under the elevation chart", speedTitle.first >= elevation.second)
        assertTrue("speed chart under its title", speed.first >= speedTitle.second)
        assertTrue("심박 under the speed chart", heartTitle.first >= speed.second)
        assertTrue("heart-rate chart under its title", heart.first >= heartTitle.second)
        // The old line charts wrote their range as text ("18km/h", "165bpm"); only the rows above say km/h and bpm now.
        composeRule.onAllNodesWithText("km/h", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("bpm", substring = true).assertCountEquals(1)
        val totals = segment.totals
        composeRule.onNodeWithTag(MetricTags.SPEED_CHART)
            .assertContentDescriptionEquals(ChartText.speedDescription(totals.avgSpeedMps * 3.6, totals.maxSpeedMps * 3.6))
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertContentDescriptionEquals("심박 그래프, 평균 130bpm, 최고 165bpm")

        for (tag in listOf(MetricTags.SPEED_CHART, MetricTags.HEART_CHART)) {
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            showOnMapIn(tag).performClick()
        }
        assertEquals(listOf(ChartTab.SPEED, ChartTab.SPEED, ChartTab.HEART_RATE, ChartTab.HEART_RATE), opened)
    }

    /**
     * The heart-rate chart is shown as before: once the heart rate is READY with samples to chart, detailed or not;
     * not while it is not READY, even with samples stored.
     */
    @Test
    fun heartRateGateUnchanged() {
        runBlocking {
            postStore.replaceSamples(1, listOf(HeartRateSample(1_000L, 95), HeartRateSample(4_000L, 165)))
            postStore.saveExtras(SegmentExtras(1, hrStatus = HrStatus.PENDING, hrLastTriedAt = 1L))
        }
        show()
        composeRule.onNodeWithTag(MetricTags.SPEED_CHART).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertDoesNotExist()
        composeRule.onAllNodesWithText(ChartText.TITLE_HEART).assertCountEquals(0)

        ready(detailed = false)
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertExists() }.isSuccess
        }
        composeRule.onNodeWithText(ChartText.TITLE_HEART).performScrollTo().assertIsDisplayed()
    }

    /** The node's top and bottom in the root (px), not clipped to the scrolling column's viewport. */
    private fun span(node: SemanticsNodeInteraction): Pair<Float, Float> {
        val n = node.fetchSemanticsNode()
        return n.positionInRoot.y to n.positionInRoot.y + n.size.height
    }

    /** [지도에 표시] inside the chart tagged [chartTag] (each chart has its own). */
    private fun showOnMapIn(chartTag: String) =
        composeRule.onNode(hasTestTag(ElevationTags.SHOW_ON_MAP) and hasAnyAncestor(hasTestTag(chartTag)), useUnmergedTree = true)

    /** Download/bikeRecoderTest/v180-segment-charts.png: the segment's speed and heart-rate charts. */
    @Test
    fun screenshotCharts() {
        ready(detailed = true)
        show()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertExists() }.isSuccess
        }
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).performScrollTo()
        screenshot("v180-segment-charts")
    }

    @Test
    fun heartRateThatIsNotDetailedOffersNoHeartRateColours() {
        ready(detailed = false)
        show()

        composeRule.onNodeWithText("평균 심박 130 · 최고 165 bpm · 54 kcal").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("워치에서 운동을 시작하면 자세한 심박이 기록됩니다").assertExists()
        composeRule.onNodeWithTag(TrackColorTags.HEART_RATE).assertDoesNotExist()
        composeRule.onNodeWithTag(TrackColorTags.DEFAULT).assertDoesNotExist()
        assertEquals(null, content?.trackColors)
        assertEquals("no controls: the usual framing", null, content?.fitPadding)
    }

    /** Review Important 1: the toggle and the key lie inside the top band the framing keeps free, in both modes. */
    @Test
    fun theColourControlsStayInTheBandTheFramingKeepsFree() {
        ready(detailed = true)
        show()
        composeRule.waitUntil(5_000) { content?.fitPadding != null }
        assertEquals("kept free already in [기본]", COLOR_CONTROLS_FIT_PADDING, content?.fitPadding)

        composeRule.onNodeWithTag(TrackColorTags.HEART_RATE).performClick()
        composeRule.waitUntil(5_000) { content?.trackColors != null }
        assertEquals(COLOR_CONTROLS_FIT_PADDING, content?.fitPadding)

        val map = composeRule.onNodeWithTag(MAP).getBoundsInRoot()
        val toggle = composeRule.onNodeWithTag(TrackColorTags.TOGGLE).getBoundsInRoot()
        val legend = composeRule.onNodeWithTag(TrackColorTags.LEGEND).getBoundsInRoot()
        assertTrue("toggle ${toggle.bottom - map.top}", toggle.bottom - map.top <= COLOR_CONTROLS_TOP)
        assertTrue("legend ${legend.bottom - map.top}", legend.bottom - map.top <= COLOR_CONTROLS_TOP)
        assertTrue("compact legend ${legend.height}", legend.height <= 64.dp)
        assertTrue("side by side: ${legend.right} < ${toggle.left}", legend.right < toggle.left)
    }

    @Test
    fun detailedHeartRateColoursTheTrackAndShowsTheKey() {
        ready(detailed = true)
        show()

        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag(TrackColorTags.HEART_RATE).assertExists() }.isSuccess }
        composeRule.onNodeWithText("160 이상").assertDoesNotExist()
        assertEquals(null, content?.trackColors)

        composeRule.onNodeWithTag(TrackColorTags.HEART_RATE).performClick()
        composeRule.waitUntil(5_000) { content?.trackColors != null }
        composeRule.onNodeWithText("160 이상").assertIsDisplayed()
        composeRule.onNodeWithText("기록 없음").assertIsDisplayed()
        // 1 s and 2 s are nearest 95 (blue), 3 s and 4 s nearest 165 (red): one colour per track point.
        assertEquals(listOf("#1E88E5", "#1E88E5", "#E53935", "#E53935"), content?.trackColors)
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("기본").performClick()
        composeRule.waitUntil(5_000) { content?.trackColors == null }
        composeRule.onNodeWithText("160 이상").assertDoesNotExist()
    }

    /** Review minor 5: READY with no chart points yet shows no empty heart-rate chart. */
    @Test
    fun noHeartRateChartWithoutPoints() {
        runBlocking {
            postStore.saveExtras(SegmentExtras(1, hrStatus = HrStatus.READY, hrLastTriedAt = 1L, hrDetailed = false, avgBpm = 130, maxBpm = 165))
        }
        show()
        composeRule.onNodeWithText("평균 심박 130 · 최고 165 bpm · – kcal").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("심박").assertCountEquals(0)
    }

    @Test
    fun withoutPermissionTheCardShowsInsteadOfPending() {
        heartRate.permitted = false
        show(HeartRateGate(heartRate, settings, onGranted = {}))

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("워치 심박을 가져오려면 Health Connect 접근을 허용해 주세요").assertExists() }.isSuccess
        }
        composeRule.onNodeWithText("심박 가져오는 중").assertDoesNotExist()
        composeRule.onNodeWithText("허용").assertExists()

        // [나중에]: not asked again.
        composeRule.onNodeWithText("나중에").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("나중에").assertDoesNotExist() }.isSuccess
        }
        assertEquals(true, runBlocking { settings.settings.first().heartRatePromptDismissed })
        composeRule.onNodeWithText("심박 가져오는 중").assertDoesNotExist()
    }

    @Test
    fun withoutHealthConnectItSaysSo() {
        heartRate.availability = HealthAvailability.NOT_INSTALLED
        show(HeartRateGate(heartRate, settings, onGranted = {}))

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("Health Connect를 사용할 수 없습니다").assertExists() }.isSuccess
        }
        composeRule.onNodeWithText("심박 가져오는 중").assertDoesNotExist()
        composeRule.onNodeWithText("워치 심박을 가져오려면 Health Connect 접근을 허용해 주세요").assertDoesNotExist()
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
        const val MAP = "map-slot"
        const val TAG = "SegmentDetailScreenTest"
    }

    @Test
    fun refetchReadsTheSegmentAgain() {
        show(HeartRateGate(heartRate, settings, onGranted = {}))
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("심박 가져오는 중").assertExists() }.isSuccess
        }
        // The pass on open has read it once.
        composeRule.waitUntil(5_000) { heartRate.calls.size >= 1 }
        val before = heartRate.calls.size

        heartRate.reads = { _, _ -> HrRead.Samples(listOf(HeartRateSample(2_000L, 128)), null) }
        composeRule.onNodeWithText("다시 가져오기").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("평균 심박 128 · 최고 128 bpm · – kcal").assertExists() }.isSuccess
        }
        assertEquals(before + 1, heartRate.calls.size)
    }
}

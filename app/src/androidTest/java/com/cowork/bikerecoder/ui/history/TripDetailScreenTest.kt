package com.cowork.bikerecoder.ui.history

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.series.MetricSeries
import com.cowork.bikerecoder.export.ExportFormat
import com.cowork.bikerecoder.export.ExportTags
import com.cowork.bikerecoder.ui.elevation.ChartTab
import com.cowork.bikerecoder.ui.elevation.ChartText
import com.cowork.bikerecoder.ui.elevation.ElevationTags
import com.cowork.bikerecoder.ui.elevation.MetricTags
import com.cowork.bikerecoder.ui.elevation.hillProfile
import com.cowork.bikerecoder.ui.elevation.hillTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The stateless trip detail on fake data (spec §3.5). The feed's own tests are in ui/feed/FeedScreenTest. */
class TripDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val date = LocalDate.of(2026, 10, 12)

    private val segment = RideSegment(
        id = 11,
        tripId = 7,
        startedAt = 1_760_245_200_000, // 2026-10-12 14:00 Seoul
        endedAt = 1_760_245_200_000 + 6_300_000,
        localDate = date,
        totals = SegmentTotals.ZERO.copy(distanceM = 32_100.0, movingTimeMs = 6_300_000),
    )

    private val opened = mutableListOf<Long>()
    private var confirmed = 0

    @Test
    fun tripOpensItsSegment() {
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = { opened += it },
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("여행 합계").assertIsDisplayed()
        composeRule.onNodeWithText("2026년 10월 12일").assertIsDisplayed()
        composeRule.onNodeWithText(segmentRowText(segment)).performClick()
        assertEquals(listOf(11L), opened)
    }

    @Test
    fun notDeletableShowsWhy() {
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = HistoryDialog.Message(MSG_OPEN_SEGMENT_NOT_DELETABLE),
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = { confirmed++ },
                    onDismiss = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(MSG_OPEN_SEGMENT_NOT_DELETABLE).assertIsDisplayed()
        composeRule.onNodeWithText("삭제").assertDoesNotExist()
        composeRule.onNodeWithText("확인").performClick()
        assertEquals(0, confirmed)
    }

    /** Spec §6.9: [다시 가져오기] in the trip's totals, also with no heart-rate line; disabled while a pass runs. */
    @Test
    fun tripRefetchButtonIsShownAndCallsBack() {
        var refetches = 0
        var refetching by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    onRefetchHeartRate = { refetches++ },
                    refetching = refetching,
                )
            }
        }

        composeRule.onNodeWithText("다시 가져오기").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, refetches)
        composeRule.runOnIdle { refetching = true }
        composeRule.onNodeWithText("다시 가져오기").assertIsNotEnabled()
    }

    @Test
    fun tripWithoutRefetchCallbackHasNoButton() {
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("다시 가져오기").assertDoesNotExist()
    }

    /** V1.5.0 spec §1.1: the paper plane before [✎] on the title row opens the share sheet; none without [onShare]. */
    @Test
    fun shareIconOpens() {
        var shares = 0
        var canShare by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    title = "저녁 라이딩 · 서울 강서구",
                    onRename = {},
                    onShare = if (canShare) ({ shares++ }) else null,
                )
            }
        }

        val share = composeRule.onNodeWithContentDescription("공유").assertIsDisplayed()
        val rename = composeRule.onNodeWithContentDescription(RENAME_TITLE).assertIsDisplayed()
        assertTrue("before ✎", share.getBoundsInRoot().right <= rename.getBoundsInRoot().left)
        share.performClick()
        assertEquals(1, shares)

        canShare = false
        composeRule.onNodeWithContentDescription("공유").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(RENAME_TITLE).assertIsDisplayed()
    }

    /** V1.6.0 spec §4.2: "{파일 이름}에서 가져옴" under the title row of an imported trip; nothing for a trip recorded here. */
    @Test
    fun importedFromLine() {
        var importedFrom by mutableStateOf<String?>("Relive_0514.gpx")
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    title = "저녁 라이딩 · 서울 강서구",
                    onRename = {},
                    importedFrom = importedFrom,
                )
            }
        }

        val title = composeRule.onNodeWithText("저녁 라이딩 · 서울 강서구").assertIsDisplayed()
        val line = composeRule.onNodeWithText("Relive_0514.gpx에서 가져옴").assertIsDisplayed()
        assertTrue("under the title", line.getBoundsInRoot().top >= title.getBoundsInRoot().bottom)

        importedFrom = null
        composeRule.onNodeWithText("Relive_0514.gpx에서 가져옴").assertDoesNotExist()
        composeRule.onNodeWithText("저녁 라이딩 · 서울 강서구").assertIsDisplayed()
    }

    private fun showExportable(
        onExport: (ExportFormat) -> Unit,
        enabled: () -> Boolean = { true },
        exporting: () -> Boolean = { false },
        title: String? = "저녁 라이딩 · 서울 강서구",
    ) {
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    title = title,
                    onRename = {},
                    onShare = {},
                    onExport = onExport,
                    exportEnabled = enabled(),
                    exporting = exporting(),
                )
            }
        }
    }

    /** V1.7.0 spec §4.1, §4.2: [⋮] "더 보기" at the right end of the title row; each item exports and closes the menu. */
    @Test
    fun overflowMenuExports() {
        val asked = mutableListOf<ExportFormat>()
        var exporting by mutableStateOf(false)
        showExportable(onExport = { asked += it }, exporting = { exporting })

        val more = composeRule.onNodeWithContentDescription("더 보기").assertIsDisplayed().assertIsEnabled()
        val rename = composeRule.onNodeWithContentDescription(RENAME_TITLE).getBoundsInRoot()
        assertTrue("after ✎", more.getBoundsInRoot().left >= rename.right)
        composeRule.onNodeWithText("GPX로 내보내기").assertDoesNotExist()

        more.performClick()
        composeRule.onNodeWithText("KML로 내보내기").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("기록을 마친 뒤 내보낼 수 있습니다").assertDoesNotExist()
        composeRule.onNodeWithText("GPX로 내보내기").assertIsEnabled().performClick()
        assertEquals(listOf(ExportFormat.GPX), asked)
        composeRule.onNodeWithText("GPX로 내보내기").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("더 보기").performClick()
        composeRule.onNodeWithText("KML로 내보내기").performClick()
        assertEquals(listOf(ExportFormat.GPX, ExportFormat.KML), asked)

        // While it is saved: a small progress indicator, and the [⋮] waits.
        composeRule.onNodeWithTag(ExportTags.PROGRESS).assertDoesNotExist()
        exporting = true
        composeRule.onNodeWithTag(ExportTags.PROGRESS).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("더 보기").assertIsNotEnabled()
        exporting = false
        composeRule.onNodeWithTag(ExportTags.PROGRESS).assertDoesNotExist()
    }

    /** V1.7.0 spec §4.1: while a segment is open, both items are dimmed and the note says why. */
    @Test
    fun recordingTripMenuDisabled() {
        val asked = mutableListOf<ExportFormat>()
        showExportable(onExport = { asked += it }, enabled = { false })

        composeRule.onNodeWithContentDescription("더 보기").assertIsEnabled().performClick()
        composeRule.onNodeWithText("GPX로 내보내기").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("KML로 내보내기").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("기록을 마친 뒤 내보낼 수 있습니다").assertIsDisplayed()
        assertTrue(asked.isEmpty())
    }

    /** A trip without a title yet (no ride of 10 m finished) still has the [⋮], at the right end of an empty title row. */
    @Test
    fun untitledTripHasMenu() {
        val asked = mutableListOf<ExportFormat>()
        showExportable(onExport = { asked += it }, title = null)

        composeRule.onNodeWithContentDescription("더 보기").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription(RENAME_TITLE).assertDoesNotExist()
        composeRule.onNodeWithText("KML로 내보내기").performClick()
        assertEquals(listOf(ExportFormat.KML), asked)
    }

    /** A new trip's first segment being recorded: no title yet, the menu is there but dimmed with the note. */
    @Test
    fun untitledRecordingTripMenuDisabled() {
        val asked = mutableListOf<ExportFormat>()
        showExportable(onExport = { asked += it }, enabled = { false }, title = null)

        composeRule.onNodeWithContentDescription("더 보기").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("GPX로 내보내기").assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("KML로 내보내기").assertIsNotEnabled()
        composeRule.onNodeWithText("기록을 마친 뒤 내보낼 수 있습니다").assertIsDisplayed()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun noExporterNoMenu() {
        showTotals(climbTotals)
        composeRule.onNodeWithContentDescription("더 보기").assertDoesNotExist()
    }

    private val climbTotals = segment.totals.copy(ascentM = 123.0, descentM = 45.0, maxAltitudeM = 210.0)

    /** [hillTrack]'s 3 km at 2 s a point, the speed rising from 0 to 36 km/h and back down. */
    private val hillRide: List<TrackPoint> = hillTrack().mapIndexed { i, p ->
        p.copy(speedMps = (if (i <= 150) i / 15f else (300 - i) / 15f))
    }
    private val hillAxis = TrackAxis.of(listOf(hillRide))!!
    private val hillSpeed = MetricSeries.speed(hillAxis, listOf(hillRide))!!
    private val hillHeart = MetricSeries.heartRate(
        hillAxis,
        listOf(hillRide),
        hillRide.map { HeartRateSample(it.timeMillis, 110 + (it.timeMillis / 12_000L).toInt()) },
        detailed = listOf(true),
    )!!

    private fun showTotals(
        total: SegmentTotals,
        profile: ElevationProfile? = null,
        profileLoaded: Boolean = true,
        onOpenChart: ((ChartTab) -> Unit)? = null,
        map: (@Composable (Modifier) -> Unit)? = null,
        speedSeries: MetricSeries? = null,
        heartSeries: MetricSeries? = null,
        heartDescription: String = "",
    ) {
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = total,
                    dialog = null,
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    map = map,
                    title = "저녁 라이딩 · 서울 강서구",
                    onRename = {},
                    profile = profile,
                    profileLoaded = profileLoaded,
                    onOpenChart = onOpenChart,
                    speedSeries = speedSeries,
                    heartSeries = heartSeries,
                    heartDescription = heartDescription,
                )
            }
        }
    }

    /** V1.7.0 spec §2.3: the second row is 평균/최고, the third 누적 상승 · 누적 하강 · 최고 고도 (`--` when unknown). */
    @Test
    fun totalsThirdRowAscentDescentMax() {
        showTotals(climbTotals)

        val average = composeRule.onNodeWithText("평균/최고").assertIsDisplayed().getBoundsInRoot()
        val ascent = composeRule.onNodeWithText("누적 상승").assertIsDisplayed().getBoundsInRoot()
        val descent = composeRule.onNodeWithText("누적 하강").assertIsDisplayed().getBoundsInRoot()
        val max = composeRule.onNodeWithText("최고 고도").assertIsDisplayed().getBoundsInRoot()
        composeRule.onNodeWithText("123m").assertIsDisplayed()
        composeRule.onNodeWithText("45m").assertIsDisplayed()
        composeRule.onNodeWithText("210m").assertIsDisplayed()
        assertTrue("third row under the second", ascent.top >= average.bottom)
        assertEquals(ascent.top, descent.top)
        assertEquals(ascent.top, max.top)
        assertTrue("in order", ascent.right <= descent.left && descent.right <= max.left)
    }

    @Test
    fun totalsMaxAltitudeUnknownShowsDashes() {
        showTotals(climbTotals.copy(maxAltitudeM = null))

        composeRule.onNodeWithText("최고 고도").assertIsDisplayed()
        composeRule.onNodeWithText("--").assertIsDisplayed()
    }

    /** V1.7.0 spec §2.3: the small chart under the card, and [지도에 표시] on it; both open the "고도" map screen. */
    @Test
    fun chartAndShowOnMapOpenElevation() {
        val opened = mutableListOf<ChartTab>()
        showTotals(climbTotals, profile = hillProfile(), onOpenChart = { opened += it })

        composeRule.onNodeWithTag(ElevationTags.CHART).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("고도 정보가 없습니다").assertDoesNotExist()
        composeRule.onNodeWithText("지도에 표시").assertIsDisplayed()
        val card = composeRule.onNodeWithText("여행 합계").getBoundsInRoot()
        assertTrue("chart under the card", composeRule.onNodeWithTag(ElevationTags.CHART).getBoundsInRoot().top >= card.bottom)

        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).performClick()
        composeRule.onNodeWithTag(ElevationTags.CHART).performClick()
        assertEquals(listOf(ChartTab.ELEVATION, ChartTab.ELEVATION), opened)
    }

    /** V1.8.0 spec §2.2: under the card 고도, 속도, 심박, each with its small title. */
    @Test
    fun threeTitlesInOrder() {
        val total = climbTotals.copy(distanceM = 3_000.0, movingTimeMs = 600_000, maxSpeedMps = 11.0)
        showTotals(
            total, profile = hillProfile(), speedSeries = hillSpeed, heartSeries = hillHeart,
            heartDescription = "심박 그래프, 평균 131bpm, 최고 160bpm",
        )

        composeRule.onNodeWithTag(MetricTags.HEART_CHART).performScrollTo().assertIsDisplayed()
        // Positions (unlike bounds) are not clipped to the list's viewport, so a node scrolled half out keeps its size.
        val card = span(composeRule.onNodeWithText("여행 합계"))
        val elevation = span(composeRule.onNodeWithText(ChartText.TITLE_ELEVATION))
        val speed = span(composeRule.onNodeWithText(ChartText.TITLE_SPEED))
        val heart = span(composeRule.onNodeWithText(ChartText.TITLE_HEART))
        val elevationChart = span(composeRule.onNodeWithTag(ElevationTags.CHART))
        val speedChart = span(composeRule.onNodeWithTag(MetricTags.SPEED_CHART))
        val heartChart = span(composeRule.onNodeWithTag(MetricTags.HEART_CHART))
        for ((name, s) in listOf("고도" to elevation, "속도" to speed, "심박" to heart, "고도 chart" to elevationChart)) {
            assertTrue("$name has a height", s.second > s.first)
        }
        assertTrue("고도 under the card", elevation.first >= card.second)
        assertTrue("고도 chart under its title", elevationChart.first >= elevation.second)
        assertTrue("속도 under the 고도 chart", speed.first >= elevationChart.second)
        assertTrue("속도 chart under its title", speedChart.first >= speed.second)
        assertTrue("심박 under the 속도 chart", heart.first >= speedChart.second)
        assertTrue("심박 chart under its title", heartChart.first >= heart.second)
        // Read out with the trip's stored totals (3 km in 10 min = 18 km/h, highest 11 m/s = 39.6 km/h), not the series.
        composeRule.onNodeWithTag(MetricTags.SPEED_CHART).assertContentDescriptionEquals("속도 그래프, 평균 18km/h, 최고 40km/h")
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertContentDescriptionEquals("심박 그래프, 평균 131bpm, 최고 160bpm")
    }

    /** Without a heart-rate series there is no 심박 title nor chart; 속도 stays, and says so without a series. */
    @Test
    fun noHeartRateHidesHeartChart() {
        showTotals(climbTotals, profile = hillProfile(), speedSeries = null, heartSeries = null)

        composeRule.onNodeWithText(ChartText.TITLE_SPEED).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ChartText.NO_SPEED).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ChartText.TITLE_HEART).assertDoesNotExist()
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertDoesNotExist()
    }

    /** The node's top and bottom in the root (px), not clipped to the scrolling list's viewport. */
    private fun span(node: SemanticsNodeInteraction): Pair<Float, Float> {
        val n = node.fetchSemanticsNode()
        return n.positionInRoot.y to n.positionInRoot.y + n.size.height
    }

    /** Each chart (and its [지도에 표시]) opens the big map on its own tab. */
    @Test
    fun openChartPassesTab() {
        val opened = mutableListOf<ChartTab>()
        showTotals(climbTotals, profile = hillProfile(), speedSeries = hillSpeed, heartSeries = hillHeart, onOpenChart = { opened += it })

        for (tag in listOf(ElevationTags.CHART, MetricTags.SPEED_CHART, MetricTags.HEART_CHART)) {
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            composeRule.onNode(hasTestTag(ElevationTags.SHOW_ON_MAP) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .performClick()
        }
        assertEquals(
            listOf(ChartTab.ELEVATION, ChartTab.ELEVATION, ChartTab.SPEED, ChartTab.SPEED, ChartTab.HEART_RATE, ChartTab.HEART_RATE),
            opened,
        )
    }

    @Test
    fun noAltitudeShowsText() {
        showTotals(climbTotals.copy(maxAltitudeM = null), profile = null)

        composeRule.onNodeWithText("고도 정보가 없습니다").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.CHART).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).assertDoesNotExist()
    }

    /** While the profile and series are still being computed nothing is said about them, no title either (V1.8.0). */
    @Test
    fun profileNotLoadedShowsNothing() {
        showTotals(climbTotals, profile = null, profileLoaded = false)

        composeRule.onNodeWithText("여행 합계").assertIsDisplayed()
        composeRule.onNodeWithText("고도 정보가 없습니다").assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.CHART).assertDoesNotExist()
        composeRule.onNodeWithText(ChartText.NO_SPEED).assertDoesNotExist()
        composeRule.onNodeWithText(ChartText.TITLE_ELEVATION).assertDoesNotExist()
        composeRule.onNodeWithText(ChartText.TITLE_SPEED).assertDoesNotExist()
    }

    /** Download/bikeRecoderTest/v170-elevation-trip.png: the totals with their third row and the small chart. */
    @Test
    fun screenshot() {
        showTotals(
            climbTotals.copy(ascentM = 148.0, descentM = 131.0, maxAltitudeM = 68.0),
            profile = hillProfile(),
            onOpenChart = {},
            map = { modifier -> Box(modifier.height(240.dp).background(Color(0xFFCFD8DC))) },
        )
        composeRule.onNodeWithTag(ElevationTags.CHART).performScrollTo()
        screenshot("v170-elevation-trip")
    }

    /** Download/bikeRecoderTest/v180-trip-charts.png: the three small charts under the totals, scrolled to the last. */
    @Test
    fun screenshotCharts() {
        showTotals(
            climbTotals.copy(distanceM = 3_000.0, movingTimeMs = 600_000, maxSpeedMps = 11.0, ascentM = 48.0, descentM = 40.0, maxAltitudeM = 68.0),
            profile = hillProfile(),
            onOpenChart = {},
            map = { modifier -> Box(modifier.height(240.dp).background(Color(0xFFCFD8DC))) },
            speedSeries = hillSpeed,
            heartSeries = hillHeart,
            heartDescription = "심박 그래프, 평균 131bpm, 최고 160bpm",
        )
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).performScrollTo()
        screenshot("v180-trip-charts")
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
        const val TAG = "TripDetailScreenTest"
    }
}

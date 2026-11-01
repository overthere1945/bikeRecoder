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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.elevation.GradeBand
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.ui.history.NoTiles
import com.cowork.bikerecoder.ui.history.ReadOnlyRides
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.math.sin

/**
 * The big map screen (V1.7.0 spec §2.2, V1.8.0 spec §2.4) on its real view model, opened from the plan preview's
 * handoff (elevation only) or on a recorded segment with speed and heart rate: the tabs, the legend, scrubbing (the line
 * under the chart and the dot on the map) kept across tabs, [×] and going back with nothing to show.
 */
class ElevationMapScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val track = hillTrack()
    private val profile = hillProfile()
    private val path = track.map { it.point }
    private val handoff = ElevationHandoff()

    /** [hillTrack] ridden: about 22 km/h on the flat, 11 up the climb, 20 over the top and 31 down. */
    private val ridden: List<TrackPoint> = hillTrack().mapIndexed { i, p ->
        val d = i * 10.0
        val kmh = when {
            d <= 1_000.0 -> 22.0 + 3.0 * sin(i / 7.0)
            d <= 1_600.0 -> 11.0
            d <= 2_000.0 -> 20.0
            else -> 31.0
        }
        p.copy(speedMps = (kmh / 3.6).toFloat())
    }
    private val segment = RideSegment(
        id = SEGMENT, tripId = 1, startedAt = 0L, endedAt = ridden.last().timeMillis, localDate = LocalDate.of(2026, 10, 18),
        totals = SegmentTotals.ZERO.copy(distanceM = 3_000.0, movingTimeMs = 600_000L, maxSpeedMps = 9.0),
    )
    private val rides = object : RideStore by ReadOnlyRides(listOf(segment)) {
        override suspend fun points(segmentId: Long) = if (segmentId == SEGMENT) ridden else emptyList()
    }

    /**
     * A heart rate every 2 s (125 bpm on the flat, up to 170 at the top of the climb, 150 over it, 128 down), but none
     * from 440 s to 560 s: no bpm from about 2.35 to 2.65 km.
     */
    private val posts = FakePostStore { listOf(segment) }.apply {
        samplesById[SEGMENT] = ridden.filter { it.timeMillis < 440_000L || it.timeMillis > 560_000L }.map { p ->
            val d = p.timeMillis / 200.0
            val bpm = when {
                d <= 1_000.0 -> 125.0
                d <= 1_600.0 -> 125.0 + (d - 1_000.0) / 600.0 * 45.0
                d <= 2_000.0 -> 150.0
                else -> 128.0
            }
            HeartRateSample(p.timeMillis, bpm.toInt())
        }
    }

    /** What the map slot was last given. */
    @Volatile private var content: ElevationMapContent? = null
    private var closed = 0

    private fun viewModel(handOver: Boolean = true): ElevationMapViewModel {
        if (handOver) handoff.put(profile, path)
        return ElevationMapViewModel(ElevationSource.Plan, ReadOnlyRides(emptyList()), posts, handoff)
    }

    private fun segmentViewModel(tab: ChartTab = ChartTab.ELEVATION) =
        ElevationMapViewModel(ElevationSource.Segment(SEGMENT), rides, posts, handoff, tab)

    private fun ElevationMapViewModel.ready() = uiState.value as ElevationMapUiState.Ready

    /** The scrub line drawn in the chart tagged [chartTag]. */
    private fun scrubLineIn(chartTag: String) =
        composeRule.onNode(hasTestTag(ElevationTags.SCRUB_LINE) and hasAnyAncestor(hasTestTag(chartTag)), useUnmergedTree = true)

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

    @Test
    fun tabsSwitchLegendAndScrubLine() {
        val vm = segmentViewModel()
        show(vm)
        val ready = vm.ready()
        val speed = requireNotNull(ready.speed)
        val heart = requireNotNull(ready.heartRate)

        // [고도][속도][심박], on elevation: its chart, 7 cells and the grade colours.
        composeRule.onNodeWithTag(ChartTab.ROW_TAG).assertIsDisplayed()
        ChartTab.entries.forEach { composeRule.onNodeWithTag(it.tag).assertIsDisplayed().assertTextEquals(it.title) }
        composeRule.onNodeWithTag(ElevationTags.CHART).assertIsDisplayed()
        composeRule.onAllNodesWithTag(ElevationTags.LEGEND_CELL).assertCountEquals(7)
        val elevationColors = content!!.colors

        composeRule.onNodeWithTag(ChartTab.SPEED.tag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MetricTags.SPEED_CHART).assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.CHART).assertDoesNotExist()
        composeRule.onAllNodesWithTag(ElevationTags.LEGEND_CELL).assertCountEquals(5)
        ChartText.SPEED_LEGEND.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(vm.ready().colors, content!!.colors)
        assertNotEquals(elevationColors, content!!.colors)

        composeRule.onNodeWithTag(ChartTab.HEART_RATE.tag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).assertIsDisplayed()
        composeRule.onAllNodesWithTag(ElevationTags.LEGEND_CELL).assertCountEquals(5)
        ChartText.HEART_LEGEND.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(vm.ready().colors, content!!.colors)

        // Scrubbed on heart rate: the line in that chart and the "거리 · bpm" line instead of the legend.
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).performTouchInput {
            down(Offset(width * 0.3f, height / 2f))
            up()
        }
        composeRule.waitForIdle()
        val d = requireNotNull(vm.ready().scrubM)
        assertEquals(heart.totalM * 0.3, d, heart.totalM * 0.02)
        composeRule.onNodeWithTag(ElevationTags.SCRUB_TEXT)
            .assertTextEquals(ChartText.scrubLine(MetricKind.HEART_RATE, d, heart.valueAt(d)))
        composeRule.onAllNodesWithTag(ElevationTags.LEGEND_CELL).assertCountEquals(0)
        scrubLineIn(MetricTags.HEART_CHART).assertIsDisplayed()
        val marker = requireNotNull(content!!.marker)

        // Other tabs keep the distance and the dot: only the line under the chart changes.
        composeRule.onNodeWithTag(ChartTab.SPEED.tag).performClick()
        composeRule.waitForIdle()
        assertEquals(d, vm.ready().scrubM)
        composeRule.onNodeWithTag(ElevationTags.SCRUB_TEXT)
            .assertTextEquals(ChartText.scrubLine(MetricKind.SPEED, d, speed.valueAt(d)))
        scrubLineIn(MetricTags.SPEED_CHART).assertIsDisplayed()
        assertEquals(marker, content!!.marker)

        composeRule.onNodeWithTag(ChartTab.ELEVATION.tag).performClick()
        composeRule.waitForIdle()
        val p = requireNotNull(vm.ready().profile)
        composeRule.onNodeWithTag(ElevationTags.SCRUB_TEXT)
            .assertTextEquals(ElevationText.scrubLine(d, p.elevationAt(d), p.gradeAt(d)))
        scrubLineIn(ElevationTags.CHART).assertIsDisplayed()
        assertEquals(marker, content!!.marker)
    }

    @Test
    fun scrubInHeartRateGapShowsDashes() {
        val vm = segmentViewModel(ChartTab.HEART_RATE)
        show(vm)

        // 2.5 km: in the run without heart rate.
        composeRule.onNodeWithTag(MetricTags.HEART_CHART).performTouchInput {
            down(Offset(width * 0.833f, height / 2f))
            up()
        }
        composeRule.waitForIdle()

        val d = requireNotNull(vm.ready().scrubM)
        assertNull(vm.ready().heartRate!!.valueAt(d))
        composeRule.onNodeWithTag(ElevationTags.SCRUB_TEXT)
            .assertTextEquals("${SummaryFormatter.distance(d)} · --bpm")
        scrubLineIn(MetricTags.HEART_CHART).assertIsDisplayed()
    }

    @Test
    fun singleTabShowsTitle() {
        show(viewModel())

        // The plan has elevation only: the title, no tabs.
        composeRule.onNodeWithText("고도").assertIsDisplayed()
        composeRule.onNodeWithTag(ChartTab.ROW_TAG).assertDoesNotExist()
        ChartTab.entries.forEach { composeRule.onNodeWithTag(it.tag).assertDoesNotExist() }
        composeRule.onNodeWithTag(ElevationTags.CHART).assertIsDisplayed()
    }

    /** Download/bikeRecoderTest/v180-map-speed.png: the real map on the speed tab, scrubbed onto the climb. */
    @Test
    fun screenshotSpeed() {
        shoot(ChartTab.SPEED, MetricTags.SPEED_CHART, at = 0.45f, name = "v180-map-speed")
    }

    /** Download/bikeRecoderTest/v180-map-heart.png: the real map on the heart-rate tab, scrubbed to the top of the climb. */
    @Test
    fun screenshotHeart() {
        shoot(ChartTab.HEART_RATE, MetricTags.HEART_CHART, at = 0.53f, name = "v180-map-heart")
    }

    /** The segment's big map on [tab] with real tiles, its chart [chartTag] scrubbed [at] across, saved as [name]. */
    private fun shoot(tab: ChartTab, chartTag: String, at: Float, name: String) {
        val vm = segmentViewModel(tab)
        val tiles = OpenFreeMapSource(OkHttpClient(), cacheFile = null)
        composeRule.setContent {
            MaterialTheme { ElevationMapScreen(vm, tiles, onClose = { closed++ }) }
        }
        composeRule.waitUntil(5_000) { vm.uiState.value is ElevationMapUiState.Ready }
        composeRule.onNodeWithTag(chartTag).performTouchInput {
            down(Offset(width * at, height / 2f))
            up()
        }
        // Tiles and the framing animation.
        Thread.sleep(10_000)
        screenshot(name)
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
        const val SEGMENT = 1L
    }
}

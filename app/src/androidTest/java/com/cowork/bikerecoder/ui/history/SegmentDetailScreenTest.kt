package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
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
    private val rides = ReadOnlyRides(listOf(segment), pointsPerSegment = 4)
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

    private fun show(gate: HeartRateGate? = null) {
        val vm = SegmentDetailViewModel(rides, 1, postStore, processor)
        composeRule.setContent {
            MaterialTheme {
                SegmentDetailScreen(vm, NoTiles, fakeThumbnails(), onBack = {}, heartRateGate = gate) { c, modifier ->
                    content = c
                    Box(modifier.testTag(MAP))
                }
            }
        }
        composeRule.waitUntil(5_000) { content?.track?.isNotEmpty() == true }
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
        composeRule.onNodeWithText("165bpm").performScrollTo().assertIsDisplayed()

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

    private companion object {
        const val MAP = "map-slot"
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

package com.cowork.bikerecoder.ui.feed

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.stats.Streak
import com.cowork.bikerecoder.ui.activity.ActivityStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The feed cards of the activity types (V1.9.0 spec §2): a bike, a run, a hike and a mixed trip, each with its badge(s), its
 * three numbers and its bottom line, and the weekly goal bar on the bike's distance only. Screenshot `v190-feed-cards`.
 */
class FeedCardActivityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val route = listOf(
        GeoPoint(37.50, 127.00), GeoPoint(37.51, 127.02), GeoPoint(37.53, 127.03), GeoPoint(37.54, 127.07),
        GeoPoint(37.52, 127.10),
    )

    private val photo: suspend (Long, Int) -> ImageBitmap? = { _, _ -> null }

    private fun card(
        id: Long,
        title: String,
        dominant: ActivityType,
        activities: List<ActivityType> = listOf(dominant),
        distanceM: Double,
        movingTimeMs: Long,
        ascentM: Double = 0.0,
        avgBpm: Int? = null,
        hikeElapsedMs: Long = 0L,
        maxAltitudeM: Double? = null,
        colors: List<ActivityType> = listOf(dominant),
    ) = FeedCardState(
        tripId = id,
        title = title,
        dateLine = "10월 25일 (일) 오전 9:05",
        inProgress = false,
        distanceM = distanceM,
        movingTimeMs = movingTimeMs,
        ascentM = ascentM,
        avgSpeedMps = distanceM / (movingTimeMs / 1000.0),
        avgBpm = avgBpm,
        photoMediaIds = emptyList(),
        morePhotos = 0,
        video = null,
        trophy = false,
        thumbKey = null,
        thumbFile = null,
        routes = colors.map { route to ActivityStyle.hex(it) },
        canShare = true,
        activities = activities,
        dominant = dominant,
        hikeElapsedMs = hikeElapsedMs,
        maxAltitudeM = maxAltitudeM,
    )

    private val bike = card(
        1, "저녁 라이딩 · 서울 강서구", ActivityType.BIKE, distanceM = 32_100.0, movingTimeMs = 6_300_000, ascentM = 123.4, avgBpm = 132,
    )
    private val run = card(
        2, "아침 달리기 · 서울 마포구", ActivityType.RUN, distanceM = 10_000.0, movingTimeMs = 3_320_000, avgBpm = 150,
    )
    private val hike = card(
        3, "아침 등산 · 강원 속초시", ActivityType.HIKE, distanceM = 6_400.0, movingTimeMs = 9_000_000, ascentM = 520.0,
        hikeElapsedMs = 12_300_000, maxAltitudeM = 1_708.0,
    )
    private val mixed = card(
        4, "아침 달리기", ActivityType.RUN, activities = listOf(ActivityType.RUN, ActivityType.WALK, ActivityType.BIKE, ActivityType.HIKE),
        distanceM = 18_000.0, movingTimeMs = 5_400_000, colors = listOf(ActivityType.RUN, ActivityType.WALK, ActivityType.BIKE),
    )

    /** Bike goal 100 km; this week's 64.2 km includes a run, the bike's own distance is 20 km. */
    private val strip = WeekStripState(64_200.0, 4, 12_300.0, 100, Streak(4, includesThisWeek = true), goalDistanceM = 20_000.0)

    private fun show() {
        composeRule.setContent {
            MaterialTheme {
                FeedContent(
                    weekStrip = strip,
                    months = listOf(FeedMonth("2026년 10월 · 66.5 km · 4회", listOf(bike, run, hike, mixed))),
                    ready = true,
                    hiddenShortCount = 0,
                    dialog = null,
                    photo = photo,
                    onOpenTrip = {},
                    onOpenVideo = {},
                    onOpenStatsWeek = {},
                    onStartRecording = {},
                    onSetGoals = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onRequestCleanup = {},
                    onConfirmCleanup = {},
                )
            }
        }
    }

    private fun scrollTo(id: Long) {
        composeRule.onNodeWithTag(FeedTags.LIST).performScrollToNode(hasTestTag(FeedTags.card(id)))
    }

    private fun inCard(id: Long, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(FeedTags.card(id)))

    private fun assertTexts(id: Long, vararg texts: String) {
        for (text in texts) composeRule.onNode(inCard(id, text), useUnmergedTree = true).assertExists("$text on card $id")
    }

    /** How many badges of [name] the card [id] draws (each is read by the activity's name). */
    private fun badgeCount(id: Long, name: String): Int = composeRule.onAllNodes(
        hasContentDescription(name) and hasAnyAncestor(hasTestTag(FeedTags.badges(id))), useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun badgeNames(id: Long): List<String> =
        ActivityType.entries.map { ActivityStyle.name(it) }.filter { badgeCount(id, it) > 0 }

    /** A bike card shows its bike badge, 거리·이동 시간·평균 속도 and the bike's bottom line. */
    @Test
    fun bikeCard() {
        show()
        scrollTo(1)

        composeRule.onNodeWithTag(FeedTags.badges(1), useUnmergedTree = true).assertExists()
        assertEquals(listOf("자전거"), badgeNames(1))
        assertTexts(1, "거리", "32.1 km", "이동 시간", "1시간 45분", "평균 속도", "18.3 km/h", "평균 18.3 km/h · ♥ 132")
    }

    /** A run card: 거리·평균 페이스·이동 시간 and the pace line. */
    @Test
    fun runCard() {
        show()
        scrollTo(2)

        assertEquals(listOf("달리기"), badgeNames(2))
        assertTexts(2, "평균 페이스", "5'32\"/km", "55분", "평균 5'32\"/km · ♥ 150")
    }

    /** A hike card: 산행 시간·누적 상승·최고 고도 and "상승 … · 평균 …". */
    @Test
    fun hikeCard() {
        show()
        scrollTo(3)

        assertEquals(listOf("등산"), badgeNames(3))
        assertTexts(3, "산행 시간", "3시간 25분", "누적 상승", "520 m", "최고 고도", "1,708 m", "상승 520 m · 평균 2.6 km/h")
    }

    /** A mixed trip shows at most three overlapping badges, each read by its name, and its dominant activity's cells. */
    @Test
    fun mixedCardHasAtMostThreeOverlappingBadges() {
        show()
        scrollTo(4)

        assertEquals("the fourth type (등산) is not drawn", listOf("자전거", "걷기", "달리기"), badgeNames(4))
        for (name in listOf("자전거", "걷기", "달리기")) assertEquals(1, badgeCount(4, name))
        assertTexts(4, "평균 페이스") // the dominant is the run: its cells
        composeRule.onNode(inCard(4, "산행 시간"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun screenshot() {
        show()
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.waitForIdle()

        screenshot("v190-feed-cards")
    }

    /** The whole screen into Download/bikeRecoderTest/[name].png. */
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/bikeRecoderTest/")
        }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "FeedCardActivityTest"
    }
}

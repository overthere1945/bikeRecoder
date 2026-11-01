package com.cowork.bikerecoder.ui.history

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.RoomRideStore
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.photo.fakeThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * "활동 종류 바꾸기" (V1.9.0 spec §1.5) on the segment screen with its real view model over an in-memory database: the
 * [⋮] item opens the dialog with its note and the four activities, the current one selected; picking 달리기 and [바꾸기]
 * changes the badge, recomputes the totals and gives the trip its new automatic title. Screenshot `v190-change-activity`.
 */
class ChangeActivityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var rides: RoomRideStore
    private var segmentId = 0L
    private var tripId = 0L

    // A walk of 400 s at 3 m/s whose stored distance (1.23 km) is not what the run rules measure from its points.
    private val startedAt = LocalDate.of(2026, 10, 25).atTime(19, 0).atZone(SEOUL).toInstant().toEpochMilli()
    private val storedTotals = SegmentTotals.ZERO.copy(distanceM = 1_234.0, movingTimeMs = 400_000L, elapsedMs = 400_000L)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        rides = RoomRideStore(db)
        tripId = RoomTripStore(db).insertTrip(
            Trip(0, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.BALANCED, 1_000L, 2_000L, 3_000L),
        )
        val step = 3.0 / 111_320.0
        val points = List(401) { i ->
            TrackPoint(startedAt + i * 1_000L, GeoPoint(37.5 + i * step, 127.0), 30.0, 3f, 5f)
        }
        segmentId = rides.openSegment(tripId, startedAt, LocalDate.of(2026, 10, 25), ActivityType.WALK)
        rides.appendPoints(segmentId, points, storedTotals)
        rides.closeSegment(segmentId, startedAt + 400_000L, storedTotals)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun show() {
        val posts = FakePostStore { runBlocking { rides.segments(tripId) } }
        val processor = PostProcessor(
            rides, posts, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = scope, warnLog = { _, _ -> },
        )
        val vm = SegmentDetailViewModel(rides, segmentId, posts, processor)
        composeRule.setContent {
            MaterialTheme {
                SegmentDetailScreen(vm, NoTiles, fakeThumbnails(), onBack = {}) { _, modifier -> Box(modifier.testTag("map")) }
            }
        }
        // The more button comes with the loaded segment.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ChangeActivityTags.MORE).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun walkToRunChangesTotalsBadgeAndTitle() {
        show()
        // The walk as stored.
        composeRule.onNodeWithContentDescription("걷기").assertIsDisplayed()
        composeRule.onNodeWithText("1.23km").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(ChangeActivityTags.MORE).performClick()
        composeRule.onNodeWithTag(ChangeActivityTags.ITEM).assertTextEquals(ActivityText.CHANGE_ACTIVITY).performClick()

        composeRule.onNodeWithTag(ChangeActivityTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(ChangeActivityTags.NOTE).assertTextEquals("일시정지 중에 저장되지 않은 위치는 되살릴 수 없습니다.")
        ActivityType.entries.forEach { composeRule.onNodeWithTag(ChangeActivityTags.row(it)).assertIsDisplayed() }
        composeRule.onNodeWithTag(ChangeActivityTags.row(ActivityType.WALK)).assertIsSelected()
        composeRule.onNodeWithTag(ChangeActivityTags.row(ActivityType.RUN)).assertIsNotSelected()
        composeRule.onNodeWithTag(ChangeActivityTags.CONFIRM).assertIsNotEnabled()

        composeRule.onNodeWithTag(ChangeActivityTags.row(ActivityType.RUN)).performClick()
        composeRule.onNodeWithTag(ChangeActivityTags.row(ActivityType.RUN)).assertIsSelected()
        composeRule.onNodeWithTag(ChangeActivityTags.CONFIRM).assertIsEnabled()
        composeRule.waitForIdle()
        screenshot("v190-change-activity")

        composeRule.onNodeWithTag(ChangeActivityTags.CONFIRM).performClick()

        // The screen follows the store: the run's badge and the totals recomputed from the points.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("달리기").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ChangeActivityTags.DIALOG).assertDoesNotExist()
        composeRule.onNodeWithText("1.23km").assertDoesNotExist()
        composeRule.onNodeWithText("평균 페이스").performScrollTo().assertIsDisplayed()

        // The trip's automatic title follows the new activity.
        val posts = FakePostStore { runBlocking { rides.segments(tripId) } }
        val processor = PostProcessor(
            rides, posts, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = scope, warnLog = { _, _ -> },
        )
        val title = runBlocking {
            withTimeout(5_000) { TripDetailViewModel(rides, tripId, posts, processor).title.first { it != null } }
        }
        assertTrue("title was $title", title!!.endsWith("달리기"))
    }

    /** Cancelling leaves the segment as it was. */
    @Test
    fun cancelLeavesTheSegment() {
        show()
        composeRule.onNodeWithTag(ChangeActivityTags.MORE).performClick()
        composeRule.onNodeWithTag(ChangeActivityTags.ITEM).performClick()
        composeRule.onNodeWithTag(ChangeActivityTags.row(ActivityType.HIKE)).performClick()
        composeRule.onNodeWithTag(ChangeActivityTags.CANCEL).performClick()

        composeRule.onNodeWithTag(ChangeActivityTags.DIALOG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("걷기").assertIsDisplayed()
        composeRule.onNodeWithText("1.23km").performScrollTo().assertIsDisplayed()
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
        const val TAG = "ChangeActivityTest"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.PhotoCandidate
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.map.MapPhoto
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.ui.photo.fakeThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The segment and trip detail screens on their real view models (fake stores): the map gets the previews of the
 * visible photos only, while the strip counts both (spec §2.3).
 */
class DetailMapPhotosTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val day1 = LocalDate.of(2026, 10, 17)
    private val day2 = LocalDate.of(2026, 10, 18)
    private val segments = listOf(segment(1, day1, 1_000L), segment(2, day2, 100_000L))
    private val rides = ReadOnlyRides(segments)
    private val postStore = FakePostStore { segments }
    private val gallery = FakePhotoSource()
    private val processor = PostProcessor(
        rides, postStore, gallery, FakeHeartRateSource(), clock = { 0L }, scope = scope, warnLog = { _, _ -> },
    )

    /** What the map slot was last given. */
    @Volatile private var mapPhotos: List<MapPhoto>? = null

    @After
    fun tearDown() = scope.cancel()

    /** Photos 1 (visible) and 2 (hidden) on segment 1, photo 3 (visible) on segment 2. */
    private fun photos() = runBlocking {
        postStore.upsertPhotos(1, listOf(placed(11, 1_500L), placed(12, 1_600L)))
        postStore.upsertPhotos(2, listOf(placed(13, 100_500L)))
        postStore.setHidden(postStore.photos.value.single { it.mediaId == 12L }.id, hidden = true)
        // Still in the gallery (far from every ride), so opening the screen prunes nothing.
        gallery.photos = listOf(11L, 12L, 13L).map { PhotoCandidate(it, 9_000_000_000L + it, exif = null) }
    }

    private fun mediaIdsOnTheMap(): List<Long> {
        val byId = postStore.photos.value.associateBy { it.id }
        return mapPhotos.orEmpty().map { byId.getValue(it.photoId).mediaId }
    }

    @Test
    fun segmentDetailMapShowsOnlyVisiblePhotos() {
        photos()
        val vm = SegmentDetailViewModel(rides, 1, postStore, processor)
        composeRule.setContent {
            MaterialTheme {
                SegmentDetailScreen(vm, NoTiles, fakeThumbnails(), onBack = {}) { content, modifier ->
                    mapPhotos = content.photos
                    Box(modifier)
                }
            }
        }

        composeRule.waitUntil(5_000) { mapPhotos.orEmpty().isNotEmpty() }
        composeRule.onNodeWithText("사진 1장").assertIsDisplayed()
        composeRule.onNodeWithText("숨긴 사진 1장").assertIsDisplayed()
        assertEquals(listOf(11L), mediaIdsOnTheMap())
    }

    @Test
    fun tripDetailMapShowsOnlyEveryDaysVisiblePhotos() {
        photos()
        val vm = TripDetailViewModel(rides, TRIP, postStore, processor)
        composeRule.setContent {
            MaterialTheme {
                TripDetailScreen(vm, NoTiles, fakeThumbnails(), onOpenSegment = {}, onBack = {}) { content, modifier ->
                    mapPhotos = content.photos
                    Box(modifier)
                }
            }
        }

        composeRule.waitUntil(5_000) { mapPhotos.orEmpty().size >= 2 }
        composeRule.onNodeWithText("사진 2장").assertIsDisplayed()
        composeRule.onNodeWithText("숨긴 사진 1장").assertIsDisplayed()
        assertEquals(listOf(11L, 13L), mediaIdsOnTheMap())
    }

    private fun segment(id: Long, date: LocalDate, startedAt: Long) = RideSegment(
        id = id, tripId = TRIP, startedAt = startedAt, endedAt = startedAt + 2_000L, localDate = date,
        totals = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000),
    )

    private fun placed(mediaId: Long, takenAt: Long) =
        PlacedPhoto(mediaId, takenAt, GeoPoint(37.5, 127.0), PhotoPositionSource.TRACK)

    private companion object {
        const val TRIP = 1L
    }
}

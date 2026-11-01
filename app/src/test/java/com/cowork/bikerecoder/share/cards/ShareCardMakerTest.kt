package com.cowork.bikerecoder.share.cards

import android.graphics.Bitmap
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.CardSize
import com.cowork.bikerecoder.core.video.RidePoint
import com.cowork.bikerecoder.core.video.RideTimeline
import com.cowork.bikerecoder.share.ShareCache
import com.cowork.bikerecoder.video.TripTotals
import com.cowork.bikerecoder.video.VideoInputs
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The card maker's kept trip (spec §3.1): one trip's inputs, facts and satellite decision until [ShareCardMaker.forget].
 * Bitmaps cannot be made on the JVM, so the fake drawer throws [NotDrawn] once the trip is prepared: every card call
 * here ends in it (or in the argument / photo errors checked before drawing).
 */
class ShareCardMakerTest {

    @TempDir
    lateinit var dir: File

    private class NotDrawn : RuntimeException()

    private object NotDrawing : CardDrawer {
        override suspend fun satellite(inputs: VideoInputs, facts: CardFacts, size: CardSize): Bitmap = throw NotDrawn()
        override fun photoCard(inputs: VideoInputs, facts: CardFacts, size: CardSize, photo: Bitmap, takenAt: GeoPoint?): Bitmap =
            throw NotDrawn()
        override fun photoSlide(photo: Bitmap, size: CardSize): Bitmap = throw NotDrawn()
        override fun sticker(inputs: VideoInputs, facts: CardFacts): Bitmap = throw NotDrawn()
    }

    private val loaded = mutableListOf<Long>()
    private var probes = 0
    private var loadGate: CompletableDeferred<Unit>? = null

    private fun maker() = AndroidShareCardMaker(
        cache = ShareCache(dir, uriOf = { it.path }, now = { 0L }),
        load = { tripId ->
            loaded += tripId
            loadGate?.await()
            inputs(tripId)
        },
        region = { null },
        satelliteAt = { probes++; null },
        renderer = { NotDrawing },
        photo = { null },
    )

    private fun inputs(tripId: Long) = VideoInputs(
        tripId = tripId,
        timeline = RideTimeline(
            listOf(0, 1).map { k -> RidePoint(k * 1_000L, GeoPoint(36.4 + k * 1e-3, 128.2), null, 1_000_000L + k * 1_000L, 0, 0) },
        ),
        photos = emptyList(),
        heartRate = emptyList(),
        dayStarts = emptyList(),
        title = "11월 1일 (일)",
        rideTitle = "아침 라이딩",
        totals = TripTotals(
            distanceM = 1_000.0, movingTimeMs = 300_000L, avgSpeedMps = 3.3, maxSpeedMps = 6.0, ascentM = 0.0,
            avgBpm = null, calories = null,
        ),
        canColorByHeart = false,
        firstStartedAt = 1_000_000L,
    )

    /** A satellite card of [tripId]: prepares the trip, then the fake drawer throws. */
    private suspend fun satelliteCard(maker: ShareCardMaker, tripId: Long = 1) {
        val failure = runCatching { maker.card(tripId, CardKind.SATELLITE, CardSize.FEED, null) }.exceptionOrNull()
        assertTrue(failure is NotDrawn, "got $failure")
    }

    @Test
    fun keepsTheTripWithoutForget() = runTest {
        val maker = maker()

        satelliteCard(maker)
        satelliteCard(maker)

        assertEquals(listOf(1L), loaded)
        assertEquals(1, probes)
    }

    @Test
    fun forgetReloadsTheTrip() = runTest {
        val maker = maker()

        satelliteCard(maker)
        maker.forget()
        satelliteCard(maker)

        assertEquals(listOf(1L, 1L), loaded)
        assertEquals(2, probes)
    }

    @Test
    fun anotherTripReplacesTheKeptOne() = runTest {
        val maker = maker()

        satelliteCard(maker, 1)
        satelliteCard(maker, 2)
        satelliteCard(maker, 1)

        assertEquals(listOf(1L, 2L, 1L), loaded)
    }

    @Test
    fun forgetDuringALoadDoesNotKeepIt() = runTest {
        val maker = maker()
        loadGate = CompletableDeferred()
        val first = async { satelliteCard(maker) }
        runCurrent()

        maker.forget()
        loadGate!!.complete(Unit)
        first.await()
        satelliteCard(maker)

        assertEquals(listOf(1L, 1L), loaded)
    }

    @Test
    fun photoCardNeedsAPhoto() = runTest {
        val failure = runCatching { maker().card(1, CardKind.PHOTO, CardSize.FEED, null) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "got $failure")
    }

    @Test
    fun unreadablePhotoThrowsItsId() = runTest {
        val failure = runCatching { maker().card(1, CardKind.PHOTO, CardSize.STORY, 42) }.exceptionOrNull()

        assertEquals(42L, (failure as? PhotoUnreadableException)?.mediaId, "got $failure")
    }
}

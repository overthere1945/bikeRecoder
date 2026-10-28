package com.cowork.bikerecoder.video

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.BikeApp
import com.cowork.bikerecoder.BuildConfig
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.data.TripEntity
import com.cowork.bikerecoder.data.toEntity
import com.cowork.bikerecoder.search.KakaoLocalClient
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.math.abs

/**
 * The whole job on the phone, through the app's own wiring ([AppContainer.videoJob]): a completed test trip (about
 * 1.85 km around Yeouido, no photos, started 90 s before its first fix) is seeded into the app's database, then a real short portrait video (HUD on, no
 * music) and a summary image are made — real map tiles, encoder and gallery. The gallery items and the trip are
 * deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class VideoJobDeviceTest {

    private val app = ApplicationProvider.getApplicationContext<BikeApp>()
    private val container: AppContainer get() = app.container
    private val resolver: ContentResolver = app.contentResolver
    private var tripId = 0L
    private val made = mutableListOf<Uri>()

    @Before
    fun seedTrip() = runBlocking {
        val track = VideoTestTrip.points()
        // Started at 08:58:30, 90 s before the first GPS fix: the file is named after the start (spec §3.5).
        val start = VideoTestTrip.START - 90_000
        val end = track.last().timeMillis
        val db = container.db
        tripId = db.tripDao().insertTrip(
            TripEntity(0, TripType.SINGLE_DAY.name, TripStatus.COMPLETED.name, RouteProfile.BALANCED.name, start, end, end),
        )
        val totals = SegmentTotals(1_850.0, end - start, end - start, 6.5, 24.0, 20.0, 20.0, 49.0)
        val segmentId = db.rideDao().insertSegment(RideSegment(0, tripId, start, end, LocalDate.of(2026, 10, 25), totals).toEntity())
        db.rideDao().insertPoints(track.map { it.toEntity(segmentId) })
        container.videoJob.acknowledge()
    }

    @After
    fun cleanUp() = runBlocking {
        for (uri in made) {
            container.mediaStoreWriter.delete(uri)
            assertFalse("$uri left in the gallery", container.mediaStoreWriter.exists(uri))
        }
        container.db.tripDao().deleteTrip(tripId)
        container.videoJob.acknowledge()
    }

    private suspend fun awaitEnd(): VideoJobState = withTimeout(20 * 60_000L) {
        container.videoJob.state.first { it is VideoJobState.Done || it is VideoJobState.Failed || it is VideoJobState.Cancelled }
    }

    @Test
    fun shortPortraitVideoIsMadeSavedAndRecorded() = runBlocking {
        val job = container.videoJob
        val options = VideoOptions(aspect = Aspect.PORTRAIT, length = VideoLength.SHORT, photos = true, hud = true, musicUri = null)
        val started = SystemClock.elapsedRealtime()

        assertEquals(StartResult.STARTED, job.startVideo(tripId, options))
        val end = awaitEnd()
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        assertTrue("ended with $end", end is VideoJobState.Done)
        val done = end as VideoJobState.Done
        val uri = Uri.parse(done.media.uri).also { made += it }
        Log.i(
            TAG,
            "video made in $seconds s, ${container.videoJobStore.secondsPerFrame()} s per map frame, downscaled=${done.downscaled}, " +
                "${container.videoJobStore.bytesPerFrame()} satellite bytes per frame",
        )
        // The build's VWorld key: made on the satellite map.
        assertFalse("satellite fallback", done.satelliteFallback)

        assertPublished(uri)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, uri, null)
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val size = video.getInteger(MediaFormat.KEY_WIDTH) to video.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue("size $size", size == (1080 to 1920) || size == (720 to 1280))
            assertEquals(done.downscaled, size == (720 to 1280))
            val durationS = video.getLong(MediaFormat.KEY_DURATION) / 1e6
            assertTrue("duration $durationS s", abs(durationS - 30.0) <= 1.0)
            assertEquals("no music track", 1, extractor.trackCount)
        } finally {
            extractor.release()
        }
        assertRecorded(done.media, MediaKind.VIDEO)
        assertTrue((container.videoJobStore.secondsPerFrame() ?: 0.0) > 0.0)
    }

    @Test
    fun portraitImageIsMadeSavedAndRecorded() = runBlocking {
        val started = SystemClock.elapsedRealtime()

        assertEquals(StartResult.STARTED, container.videoJob.startImage(tripId, Aspect.PORTRAIT))
        val end = awaitEnd()
        assertTrue("ended with $end", end is VideoJobState.Done)
        val done = end as VideoJobState.Done
        val uri = Uri.parse(done.media.uri).also { made += it }
        Log.i(TAG, "image made in ${(SystemClock.elapsedRealtime() - started) / 1000.0} s")

        assertFalse("satellite fallback", done.satelliteFallback)
        assertPortraitPng(uri)
        assertRecorded(done.media, MediaKind.IMAGE)
    }

    /** VWorld refuses the key (spec §1.3): the plain map stands in, the image is made all the same and says so. */
    @Test
    fun wrongKeyFallsBackToPlain() = runBlocking {
        assertTrue("this build has no VWorld key", BuildConfig.VWORLD_API_KEY.isNotBlank())
        val job = container.videoJob(container.videoBackend(probeKey = "WRONGKEY"))

        assertEquals(StartResult.STARTED, job.startImage(tripId, Aspect.PORTRAIT))
        val end = withTimeout(5 * 60_000L) {
            job.state.first { it is VideoJobState.Done || it is VideoJobState.Failed || it is VideoJobState.Cancelled }
        }
        assertTrue("ended with $end", end is VideoJobState.Done)
        val done = end as VideoJobState.Done
        val uri = Uri.parse(done.media.uri).also { made += it }

        assertTrue("satellite fallback", done.satelliteFallback)
        assertPortraitPng(uri)
        assertRecorded(done.media, MediaKind.IMAGE)
    }

    /** The real Kakao answer for the test trip's start in Yeouido (spec §3.8). */
    @Test
    fun regionOfTheTestTripIsYeongdeungpo() = runBlocking {
        val kakao = KakaoLocalClient(container.okHttp, BuildConfig.KAKAO_REST_API_KEY)

        val region = kakao.regionOf(VideoTestTrip.points().first().point)

        assertEquals(SearchResult.Ok<Pair<String, String>?>("서울특별시" to "영등포구"), region)
    }

    private fun assertPortraitPng(uri: Uri) {
        assertPublishedBlocking(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(1080 to 1920, bounds.outWidth to bounds.outHeight)
    }

    private fun assertPublishedBlocking(uri: Uri) = runBlocking { assertPublished(uri) }

    /** In the gallery and no longer pending; the job left nothing pending behind. */
    private suspend fun assertPublished(uri: Uri) {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME), args, null)!!.use {
            assertTrue("$uri is not in the gallery", it.moveToFirst())
            assertEquals("still pending", 0, it.getInt(0))
            assertTrue(it.getString(1), it.getString(1).startsWith("bikeRecoder_20261025_0858"))
        }
        assertNull(container.videoJobStore.pending())
    }

    private suspend fun assertRecorded(media: TripMedia, kind: MediaKind) {
        val rows = container.tripMedia.observe(tripId).first()
        assertEquals(listOf(media), rows)
        assertEquals(kind, media.kind)
    }

    private companion object {
        const val TAG = "VideoJobDeviceTest"
    }
}

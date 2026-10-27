package com.cowork.bikerecoder.ui.video

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.BikeApp
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.TripEntity
import com.cowork.bikerecoder.data.toEntity
import com.cowork.bikerecoder.ui.history.TripDetailScreen
import com.cowork.bikerecoder.ui.history.TripDetailViewModel
import com.cowork.bikerecoder.ui.record.SegmentSummaryContent
import com.cowork.bikerecoder.ui.record.SummaryExtrasContent
import com.cowork.bikerecoder.ui.record.SummaryExtrasState
import com.cowork.bikerecoder.ui.record.SummaryMap
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.VideoTestTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * The whole making on the phone through the screens, wired as the app wires them (the activity's [VideoViewModel] with
 * [VideoHost] over the trip detail; the preview when the job is done): a completed test trip (about 1.85 km around
 * Yeouido) is seeded, then [영상 만들기] → 세로 · 짧게 → [만들기] → the progress card → the preview of a real 30 s video,
 * back, [이미지 만들기] → [만들기] → the image's preview, back → "만든 영상·이미지" with both. Real map tiles, encoder and
 * gallery. Screenshots of each step go to Download/bikeRecoderTest (pulled by hand). The gallery files and the trip are
 * deleted afterwards, unless the instrumentation argument `keepMedia=true` (to pull the MP4, then delete it by hand).
 */
class VideoFlowDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as BikeApp
    private val container: AppContainer get() = app.container
    private val keepMedia = InstrumentationRegistry.getArguments().getString("keepMedia") == "true"
    private var tripId = 0L
    private val made = mutableListOf<Uri>()

    @Before
    fun seedTrip() = runBlocking {
        val track = VideoTestTrip.points()
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
        val media = container.tripMedia.observe(tripId).first()
        made += media.map { Uri.parse(it.uri) }
        if (!keepMedia) {
            for (uri in made.distinct()) container.mediaStoreWriter.delete(uri)
        }
        Log.i(TAG, "made ${made.distinct()} (kept: $keepMedia)")
        container.db.tripDao().deleteTrip(tripId)
        container.videoJob.acknowledge()
    }

    /** The trip detail and the preview, as [com.cowork.bikerecoder.ui.BikeNavHost] has them. */
    private fun showApp() {
        val detail = TripDetailViewModel(
            container.rideStore, tripId, container.postStore, container.postProcessor, androidWarnLog(TAG),
            tripStore = container.tripStore,
            tripMedia = container.tripMedia,
            mediaExists = { uri -> withContext(Dispatchers.IO) { container.mediaStoreWriter.exists(Uri.parse(uri)) } },
        )
        composeRule.setContent {
            MaterialTheme {
                val video: VideoViewModel = viewModel(factory = VideoViewModel.factory(container))
                val outcome by video.outcome.collectAsStateWithLifecycle()
                var preview by remember { mutableStateOf<VideoOutcome.Preview?>(null) }
                LaunchedEffect(outcome) {
                    val o = outcome ?: return@LaunchedEffect
                    if (o is VideoOutcome.Preview) preview = o
                    video.outcomeShown()
                }
                val shown = preview
                if (shown == null) {
                    TripDetailScreen(
                        viewModel = detail,
                        tileSource = container.tileSource,
                        thumbnails = container.photoThumbnails,
                        onOpenSegment = {},
                        onBack = {},
                        mediaEntry = { MediaEntry(video, tripId) },
                        mediaThumbnail = galleryThumbnail(app.contentResolver),
                        onOpenMedia = {},
                    )
                } else {
                    val name = remember(shown.uri) { container.mediaStoreWriter.displayName(Uri.parse(shown.uri)) }
                    MediaPreviewScreen(shown.uri, shown.kind, shown.downscaled, name, onBack = { preview = null }, onShare = {}, onOpenGallery = {})
                }
                VideoHost(video)
            }
        }
    }

    @Test
    fun aShortPortraitVideoAndAnImageAreMadeThroughTheScreens() {
        showApp()
        waitFor(30_000) { exists(hasTestTag(VideoTags.MAKE_VIDEO)) }

        // [영상 만들기] → the sheet, with its estimate.
        composeRule.onNodeWithTag(VideoTags.MAKE_VIDEO).performClick()
        waitFor(30_000) { exists(hasTestTag(VideoTags.ESTIMATE) and hasText("걸립니다", substring = true)) }
        composeRule.onNodeWithText("세로 9:16").performClick()
        composeRule.onNodeWithText("짧게(약 30초)").performClick()
        composeRule.waitForIdle()
        waitFor(10_000) { exists(hasTestTag(VideoTags.ESTIMATE) and hasText("걸립니다", substring = true)) }
        SystemClock.sleep(800)
        screenshot("ui-options-sheet")

        composeRule.onNodeWithTag(VideoTags.MAKE).performClick()
        answerLowBattery()
        waitFor(60_000) { exists(hasTestTag(VideoTags.PROGRESS)) }
        val started = SystemClock.elapsedRealtime()
        // Mid-render: a percentage of a third or more.
        waitFor(10 * 60_000L) { exists(hasText("영상 만드는 중 ", substring = true) and hasText("남음", substring = true)) && percent() >= 30 }
        screenshot("ui-progress")

        waitFor(15 * 60_000L) { exists(hasText("영상을 만들었습니다")) }
        Log.i(TAG, "video made in ${(SystemClock.elapsedRealtime() - started) / 1000.0} s")
        composeRule.onNodeWithTag(VideoTags.SHARE).assertIsDisplayed()
        composeRule.onNodeWithTag(VideoTags.GALLERY).assertIsDisplayed()
        SystemClock.sleep(4_000)
        screenshot("video-preview")
        val videoMedia = runBlocking { container.tripMedia.observe(tripId).first() }.single { it.kind == MediaKind.VIDEO }
        val videoUri = Uri.parse(videoMedia.uri).also { made += it }
        assertVideo(videoUri)

        // Back: "만든 영상·이미지" has the video; then the image.
        composeRule.onNodeWithText("←").performClick()
        waitFor(30_000) { exists(hasTestTag(VideoTags.media(videoMedia.id))) }
        composeRule.onNodeWithTag(VideoTags.MAKE_IMAGE).performClick()
        waitFor(10_000) { exists(hasTestTag(VideoTags.SHEET)) && exists(hasTestTag(VideoTags.MAKE)) }
        composeRule.onNodeWithText("세로 9:16").performClick()
        composeRule.onNodeWithTag(VideoTags.MAKE).performClick()
        answerLowBattery()
        waitFor(5 * 60_000L) { exists(hasText("이미지를 만들었습니다")) }
        SystemClock.sleep(2_000)
        screenshot("image-preview")
        val imageMedia = runBlocking { container.tripMedia.observe(tripId).first() }.single { it.kind == MediaKind.IMAGE }
        made += Uri.parse(imageMedia.uri)

        composeRule.onNodeWithText("←").performClick()
        waitFor(30_000) { exists(hasTestTag(VideoTags.media(imageMedia.id))) && exists(hasTestTag(VideoTags.media(videoMedia.id))) }
        composeRule.onNodeWithText("만든 영상·이미지").performScrollTo().assertIsDisplayed()
        // The map tiles and the thumbnails.
        SystemClock.sleep(4_000)
        screenshot("ui-trip-detail")
    }

    /** A completed trip's summary (the record screen's) with the two buttons under it. */
    @Test
    fun theSummaryOfACompletedTripOffersTheButtons() {
        val track = VideoTestTrip.points().map { it.point }
        val segment = runBlocking { container.rideStore.segments(tripId).single() }
        val mediaTrip = runBlocking { mediaTripOf(segment.id, container.rideStore, container.tripStore) }
        assertEquals(tripId, mediaTrip)
        composeRule.setContent {
            MaterialTheme {
                val video: VideoViewModel = viewModel(factory = VideoViewModel.factory(container))
                SegmentSummaryContent(
                    segment = segment,
                    map = { modifier -> SummaryMap(track, container.tileSource, modifier) },
                    onConfirm = {},
                    footer = {
                        SummaryExtrasContent(
                            state = SummaryExtrasState(mediaTripId = mediaTrip),
                            photoPrompt = {},
                            mediaEntry = { MediaEntry(video, it) },
                        )
                    },
                )
                VideoHost(video)
            }
        }
        composeRule.onNodeWithText("영상 만들기").assertIsDisplayed()
        composeRule.onNodeWithText("이미지 만들기").assertIsDisplayed()
        composeRule.onNodeWithText("확인").assertIsDisplayed()
        // The map's tiles.
        SystemClock.sleep(8_000)
        screenshot("ui-summary")
    }

    private fun answerLowBattery() {
        composeRule.waitForIdle()
        if (exists(hasText("계속할까요?", substring = true))) composeRule.onNodeWithText("계속").performClick()
    }

    private fun percent(): Int {
        val text = composeRule.onAllNodes(hasText("영상 만드는 중 ", substring = true)).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }
            .map { it.text }.firstOrNull() ?: return -1
        return Regex("(\\d+)%").find(text)?.groupValues?.get(1)?.toInt() ?: -1
    }

    private fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            composeRule.waitForIdle()
            if (condition()) return
            check(SystemClock.elapsedRealtime() < deadline) { "timed out" }
            SystemClock.sleep(250)
        }
    }

    /** 30 s of 1080×1920 (or the 720p fallback) H.264, no music. */
    private fun assertVideo(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, uri, null)
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val size = video.getInteger(MediaFormat.KEY_WIDTH) to video.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue("size $size", size == (1080 to 1920) || size == (720 to 1280))
            val seconds = video.getLong(MediaFormat.KEY_DURATION) / 1e6
            assertTrue("duration $seconds s", abs(seconds - 30.0) <= 1.0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, video.getString(MediaFormat.KEY_MIME))
            Log.i(TAG, "video $uri: $size, $seconds s")
        } finally {
            extractor.release()
        }
    }

    /** The whole screen (the sheet is its own window) into Download/bikeRecoderTest/[name].png. */
    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/bikeRecoderTest/")
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "VideoFlowDeviceTest"
    }
}

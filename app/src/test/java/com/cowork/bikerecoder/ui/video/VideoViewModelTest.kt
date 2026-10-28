package com.cowork.bikerecoder.ui.video

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.RideTimelineBuilder
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.VideoEstimate
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.PendingJob
import com.cowork.bikerecoder.video.StartResult
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripTotals
import com.cowork.bikerecoder.video.VideoInputs
import com.cowork.bikerecoder.video.VideoJobControl
import com.cowork.bikerecoder.video.VideoJobState
import com.cowork.bikerecoder.video.VideoOptionsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Making videos and images on screen (spec §1, §3.2–3.5): the sheet's defaults (the last options, never the music),
 * [심박] only when the trip has detailed heart rate, the estimate, the entry rules (completed · a target segment ·
 * recording), each [StartResult]'s message, the low-battery question, the unfinished job and the made files' pruning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val job = FakeJob()
    private var last = VideoOptions()
    private val saved = mutableListOf<VideoOptions>()
    private var secondsPerFrame: Double? = null
    private var recording = false
    private var battery: BatteryLevel? = BatteryLevel(80, charging = false)
    private var inputs: VideoInputs? = inputs(canColorByHeart = false)
    private var inputsError: Exception? = null
    private val log = RecordingLog()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = VideoViewModel(
        job = job,
        loadInputs = { id -> inputsError?.let { throw it }; inputs?.takeIf { id == TRIP } },
        lastOptions = { last },
        saveOptions = { saved += it },
        secondsPerFrame = { secondsPerFrame },
        recording = { recording },
        battery = { battery },
        musicName = { uri -> if (uri == MUSIC) "여의도 산책.mp3" else null },
        log = log,
        compute = dispatcher,
    )

    // --- the sheet ---------------------------------------------------------------------------------------------------

    @Test
    fun `the video sheet opens with the last options and never with music`() = runTest(dispatcher) {
        last = VideoOptions(Aspect.LANDSCAPE, VideoLength.LONG, photos = false, hud = false, musicUri = MUSIC)
        val vm = vm()

        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()

        val sheet = vm.sheet.value!!
        assertEquals(MediaKind.VIDEO, sheet.kind)
        assertEquals(VideoOptions(Aspect.LANDSCAPE, VideoLength.LONG, photos = false, hud = false, musicUri = null), sheet.options)
        assertNull(sheet.musicName)
    }

    @Test
    fun `nothing remembered opens with the spec's defaults`() = runTest(dispatcher) {
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        // 세로 · 보통 · 사진 켬 · 숫자 켬 · 기본 · 없음 (spec §1.2).
        assertEquals(VideoOptions(Aspect.PORTRAIT, VideoLength.NORMAL, true, true, RouteColoring.DEFAULT, null), vm.sheet.value!!.options)
    }

    @Test
    fun `heart rate is offered only for a trip with detailed heart rate`() = runTest(dispatcher) {
        last = VideoOptions(coloring = RouteColoring.HEART_RATE)
        inputs = inputs(canColorByHeart = true)
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        assertTrue(vm.sheet.value!!.canColorByHeart)
        assertEquals(RouteColoring.HEART_RATE, vm.sheet.value!!.options.coloring)

        inputs = inputs(canColorByHeart = false)
        vm.closeSheet()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        assertFalse(vm.sheet.value!!.canColorByHeart)
        // The last [심박] falls back to [기본], and cannot be chosen.
        assertEquals(RouteColoring.DEFAULT, vm.sheet.value!!.options.coloring)
        vm.setOptions(vm.sheet.value!!.options.copy(coloring = RouteColoring.HEART_RATE))
        assertEquals(RouteColoring.DEFAULT, vm.sheet.value!!.options.coloring)
    }

    @Test
    fun `the estimate follows the length and this phone's seconds per frame`() = runTest(dispatcher) {
        secondsPerFrame = 0.4
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        assertEquals(expectedMinutes(VideoOptions(), 0.4), vm.sheet.value!!.minutes)
        assertTrue(vm.sheet.value!!.canMake)

        vm.setOptions(vm.sheet.value!!.options.copy(length = VideoLength.LONG))
        assertNull(vm.sheet.value!!.minutes, "worked out again")
        assertFalse(vm.sheet.value!!.canMake)
        advanceUntilIdle()
        assertEquals(expectedMinutes(VideoOptions(length = VideoLength.LONG), 0.4), vm.sheet.value!!.minutes)
        assertTrue(expectedMinutes(VideoOptions(length = VideoLength.LONG), 0.4) > expectedMinutes(VideoOptions(), 0.4))
    }

    @Test
    fun `without a measured speed the estimate counts 0_25 s a frame`() = runTest(dispatcher) {
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        assertEquals(expectedMinutes(VideoOptions(), 0.25), vm.sheet.value!!.minutes)
    }

    @Test
    fun `the image sheet asks only for the aspect, the last one`() = runTest(dispatcher) {
        last = VideoOptions(aspect = Aspect.LANDSCAPE, length = VideoLength.LONG)
        val vm = vm()
        vm.open(TRIP, MediaKind.IMAGE)
        advanceUntilIdle()
        val sheet = vm.sheet.value!!
        assertEquals(MediaKind.IMAGE, sheet.kind)
        assertEquals(Aspect.LANDSCAPE, sheet.options.aspect)
        assertTrue(sheet.canMake)
    }

    @Test
    fun `picked music shows its name and none goes back`() = runTest(dispatcher) {
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        vm.pickMusic(MUSIC)
        advanceUntilIdle()
        assertEquals(MUSIC, vm.sheet.value!!.options.musicUri)
        assertEquals("여의도 산책.mp3", vm.sheet.value!!.musicName)

        vm.clearMusic()
        assertNull(vm.sheet.value!!.options.musicUri)
        assertNull(vm.sheet.value!!.musicName)
    }

    // --- entry ------------------------------------------------------------------------------------------------------

    @Test
    fun `a completed trip with a finished ride of 10 m or more offers the buttons`() {
        val completed = trip(TripStatus.COMPLETED)
        assertTrue(canMakeMedia(completed, listOf(segment(endedAt = 10L, distanceM = 10.0))))
        // In progress: never.
        assertFalse(canMakeMedia(trip(TripStatus.ACTIVE), listOf(segment(endedAt = 10L, distanceM = 500.0))))
        // No target: only short or unfinished segments, or none.
        assertFalse(canMakeMedia(completed, listOf(segment(endedAt = 10L, distanceM = 9.9))))
        assertFalse(canMakeMedia(completed, listOf(segment(endedAt = null, distanceM = 500.0))))
        assertFalse(canMakeMedia(completed, emptyList()))
        assertFalse(canMakeMedia(null, listOf(segment(endedAt = 10L, distanceM = 500.0))))
    }

    @Test
    fun `while recording the buttons say so instead of opening the sheet`() = runTest(dispatcher) {
        recording = true
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        assertNull(vm.sheet.value)
        assertEquals(VideoDialog.Message("기록 중에는 만들 수 없습니다"), vm.dialog.value)
    }

    @Test
    fun `while a job runs the buttons say so`() = runTest(dispatcher) {
        job.running = true
        val vm = vm()
        vm.open(TRIP, MediaKind.IMAGE)
        advanceUntilIdle()
        assertNull(vm.sheet.value)
        assertEquals(VideoDialog.Message("이미 영상을 만들고 있습니다"), vm.dialog.value)
    }

    @Test
    fun `a trip that cannot be read closes the sheet with the failure`() = runTest(dispatcher) {
        inputsError = IOException("db")
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        assertNull(vm.sheet.value)
        assertEquals(VideoDialog.Message("영상을 만들지 못했습니다"), vm.dialog.value)
    }

    // --- making -----------------------------------------------------------------------------------------------------

    @Test
    fun `make remembers the options without the music and starts the video`() = runTest(dispatcher) {
        val vm = openVideo()
        val chosen = VideoOptions(Aspect.PORTRAIT, VideoLength.SHORT, photos = false, hud = true, musicUri = MUSIC)
        vm.setOptions(chosen)
        advanceUntilIdle()

        vm.make()
        advanceUntilIdle()

        assertEquals(listOf(chosen.copy(musicUri = null)), saved)
        assertEquals(listOf(TRIP to chosen), job.videos)
        assertNull(vm.sheet.value, "the sheet closes once started")
        assertNull(vm.dialog.value)
    }

    @Test
    fun `make starts the image with its aspect`() = runTest(dispatcher) {
        val vm = vm()
        vm.open(TRIP, MediaKind.IMAGE)
        advanceUntilIdle()
        vm.setOptions(vm.sheet.value!!.options.copy(aspect = Aspect.LANDSCAPE))
        vm.make()
        advanceUntilIdle()
        assertEquals(listOf(TRIP to Aspect.LANDSCAPE), job.images)
        assertTrue(saved.isEmpty(), "the image does not change the remembered video options")
    }

    @Test
    fun `each refusal shows its message and keeps the sheet`() = runTest(dispatcher) {
        val expected = mapOf(
            StartResult.BUSY to "이미 영상을 만들고 있습니다",
            StartResult.RECORDING to "기록 중에는 만들 수 없습니다",
            StartResult.NO_INTERNET to "지도를 받으려면 인터넷이 필요합니다",
            // Short: 8 Mbps × 30 s × 1.2 = 36 MB.
            StartResult.NO_SPACE to "저장 공간이 부족합니다(약 36 MB 필요)",
        )
        for ((result, text) in expected) {
            job.videoResult = result
            val vm = openVideo()
            vm.setOptions(vm.sheet.value!!.options.copy(length = VideoLength.SHORT))
            advanceUntilIdle()
            vm.make()
            advanceUntilIdle()
            assertEquals(VideoDialog.Message(text), vm.dialog.value, "$result")
            assertTrue(vm.sheet.value != null && !vm.sheet.value!!.starting, "$result keeps the sheet")
        }
    }

    @Test
    fun `the image's missing space counts 20 MB`() = runTest(dispatcher) {
        job.imageResult = StartResult.NO_SPACE
        val vm = vm()
        vm.open(TRIP, MediaKind.IMAGE)
        advanceUntilIdle()
        vm.make()
        advanceUntilIdle()
        assertEquals(VideoDialog.Message("저장 공간이 부족합니다(약 20 MB 필요)"), vm.dialog.value)
    }

    @Test
    fun `unreadable music asks, and makes the same video without it`() = runTest(dispatcher) {
        job.videoResult = StartResult.MUSIC_UNREADABLE
        val vm = openVideo()
        vm.pickMusic(MUSIC)
        advanceUntilIdle()
        vm.make()
        advanceUntilIdle()
        assertEquals(VideoDialog.MusicUnreadable, vm.dialog.value)

        job.videoResult = StartResult.STARTED
        vm.makeWithoutMusic()
        advanceUntilIdle()
        assertEquals(listOf(MUSIC, null), job.videos.map { it.second.musicUri })
        assertEquals(job.videos[0].second.copy(musicUri = null), job.videos[1].second)
        assertNull(vm.dialog.value)
        assertNull(vm.sheet.value)
    }

    @Test
    fun `cancel on the unreadable music question keeps the sheet`() = runTest(dispatcher) {
        job.videoResult = StartResult.MUSIC_UNREADABLE
        val vm = openVideo()
        vm.pickMusic(MUSIC)
        vm.make()
        advanceUntilIdle()
        vm.dismissDialog()
        assertNull(vm.dialog.value)
        assertEquals(MUSIC, vm.sheet.value!!.options.musicUri)
        assertEquals(1, job.videos.size)
    }

    @Test
    fun `a start that throws says it could not be made and a retry works`() = runTest(dispatcher) {
        job.videoError = IOException("room")
        val vm = openVideo()
        vm.make()
        advanceUntilIdle()
        assertEquals(VideoDialog.Message("영상을 만들지 못했습니다"), vm.dialog.value)
        assertFalse(vm.sheet.value!!.starting)

        job.videoError = null
        vm.dismissDialog()
        vm.make()
        advanceUntilIdle()
        assertNull(vm.sheet.value)
    }

    @Test
    fun `low battery not charging asks once before starting`() = runTest(dispatcher) {
        battery = BatteryLevel(19, charging = false)
        val vm = openVideo()
        vm.make()
        advanceUntilIdle()
        assertEquals(VideoDialog.LowBattery(19), vm.dialog.value)
        assertTrue(job.videos.isEmpty())

        vm.confirmBattery()
        advanceUntilIdle()
        assertEquals(1, job.videos.size)
        assertNull(vm.dialog.value)
    }

    @Test
    fun `cancel on the battery question starts nothing`() = runTest(dispatcher) {
        battery = BatteryLevel(5, charging = false)
        val vm = openVideo()
        vm.make()
        vm.dismissDialog()
        advanceUntilIdle()
        assertTrue(job.videos.isEmpty())
        assertTrue(vm.sheet.value != null)
    }

    @Test
    fun `charging or 20 percent starts without asking`() = runTest(dispatcher) {
        for (level in listOf(BatteryLevel(5, charging = true), BatteryLevel(20, charging = false), null)) {
            battery = level
            val vm = openVideo()
            vm.make()
            advanceUntilIdle()
            assertNull(vm.dialog.value, "$level")
        }
        assertEquals(3, job.videos.size)
    }

    // --- progress and outcome -----------------------------------------------------------------------------------------

    @Test
    fun `the progress card follows the job`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.progress.value)
        job.state.value = VideoJobState.Preparing(MediaKind.VIDEO)
        advanceUntilIdle()
        assertEquals(VideoProgress(MediaKind.VIDEO, null, "영상 만드는 중 0%"), vm.progress.value)
        job.state.value = VideoJobState.Rendering(42, 2)
        advanceUntilIdle()
        assertEquals(VideoProgress(MediaKind.VIDEO, 42, "영상 만드는 중 42% · 약 2분 남음"), vm.progress.value)
        job.state.value = VideoJobState.Preparing(MediaKind.IMAGE)
        advanceUntilIdle()
        assertEquals(VideoProgress(MediaKind.IMAGE, null, "이미지 만드는 중"), vm.progress.value)

        vm.cancel()
        assertEquals(1, job.cancels)
    }

    @Test
    fun `done is previewed once and failure told once`() = runTest(dispatcher) {
        val vm = vm()
        val media = TripMedia(5, TRIP, MediaKind.VIDEO, "content://media/external/video/media/5", 0L, "{}")
        job.state.value = VideoJobState.Done(media, downscaled = true)
        advanceUntilIdle()
        assertEquals(VideoOutcome.Preview(media.uri, MediaKind.VIDEO, downscaled = true), vm.outcome.value)
        vm.outcomeShown()
        advanceUntilIdle()
        assertNull(vm.outcome.value)

        job.state.value = VideoJobState.Failed("저장 공간이 부족합니다(약 36 MB 필요)")
        advanceUntilIdle()
        assertEquals(VideoOutcome.Message("저장 공간이 부족합니다(약 36 MB 필요)"), vm.outcome.value)
        vm.outcomeShown()
        advanceUntilIdle()
        assertEquals(VideoDialog.Message("저장 공간이 부족합니다(약 36 MB 필요)"), vm.dialog.value)
        assertEquals(VideoJobState.Idle, job.state.value)
        assertNull(vm.outcome.value)
    }

    /**
     * Final review M2: a job that ends as a recording starts (its last seconds of encoding cannot be stopped) is not
     * opened over the record screen; "영상을 만들었습니다" opens it later. Still taken, so the job goes back to idle.
     */
    @Test
    fun `done while recording is not opened on screen`() = runTest(dispatcher) {
        val vm = vm()
        val media = TripMedia(5, TRIP, MediaKind.VIDEO, "content://media/external/video/media/5", 0L, "{}")
        val preview = VideoOutcome.Preview(media.uri, MediaKind.VIDEO, downscaled = false)
        job.state.value = VideoJobState.Done(media, downscaled = false)
        advanceUntilIdle()
        assertEquals(preview, vm.previewToOpen(vm.outcome.value!!))

        recording = true
        assertNull(vm.previewToOpen(vm.outcome.value!!))
        vm.outcomeShown()
        advanceUntilIdle()
        assertNull(vm.dialog.value)
        assertEquals(VideoJobState.Idle, job.state.value)
        assertNull(vm.previewToOpen(VideoOutcome.Message("영상을 만들지 못했습니다")))
    }

    /** Spec §3.2: said by the notification only — no dialog over the record screen of the ride that just started. */
    @Test
    fun `a job stopped by a recording is not told on screen`() = runTest(dispatcher) {
        val vm = vm()
        job.state.value = VideoJobState.Failed("기록이 시작되어 영상 만들기를 멈췄습니다")
        advanceUntilIdle()
        vm.outcomeShown()
        advanceUntilIdle()
        assertNull(vm.dialog.value)
        assertEquals(VideoJobState.Idle, job.state.value)

        // Any failure while a recording runs: the same.
        recording = true
        job.state.value = VideoJobState.Failed("영상을 만들지 못했습니다")
        advanceUntilIdle()
        vm.outcomeShown()
        advanceUntilIdle()
        assertNull(vm.dialog.value)
        assertEquals(VideoJobState.Idle, job.state.value)
    }

    /** I1: the card (and its [취소]) only on the trip being made; other trips keep their buttons. */
    @Test
    fun `the progress card shows only for the trip being made`() {
        val progress = VideoProgress(MediaKind.VIDEO, 42, "영상 만드는 중 42% · 약 2분 남음")
        assertEquals(progress, progressFor(progress, runningTripId = TRIP, tripId = TRIP))
        assertNull(progressFor(progress, runningTripId = TRIP, tripId = 8L))
        assertNull(progressFor(progress, runningTripId = null, tripId = TRIP))
        assertNull(progressFor(null, runningTripId = TRIP, tripId = TRIP))
    }

    @Test
    fun `a cancelled job goes back to idle without a message`() = runTest(dispatcher) {
        val vm = vm()
        job.state.value = VideoJobState.Cancelled
        advanceUntilIdle()
        assertEquals(VideoJobState.Idle, job.state.value)
        assertNull(vm.outcome.value)
    }

    // --- the unfinished job ---------------------------------------------------------------------------------------------

    @Test
    fun `an unfinished video is offered and made again with its options, without music`() = runTest(dispatcher) {
        val options = VideoOptions(Aspect.LANDSCAPE, VideoLength.SHORT, photos = false, hud = false, coloring = RouteColoring.HEART_RATE)
        job.pending = PendingJob(TRIP, MediaKind.VIDEO, VideoOptionsJson.encode(options), 1L)
        val vm = vm()
        vm.checkUnfinished()
        advanceUntilIdle()
        assertEquals(VideoDialog.Unfinished(job.pending!!), vm.dialog.value)

        vm.retryUnfinished()
        advanceUntilIdle()
        assertEquals(listOf(TRIP to options.copy(musicUri = null)), job.videos)
        assertNull(vm.dialog.value)
    }

    @Test
    fun `an unfinished image is made again with its aspect`() = runTest(dispatcher) {
        job.pending = PendingJob(TRIP, MediaKind.IMAGE, VideoOptionsJson.encode(VideoOptions(aspect = Aspect.LANDSCAPE)), 1L)
        val vm = vm()
        vm.checkUnfinished()
        advanceUntilIdle()
        vm.retryUnfinished()
        advanceUntilIdle()
        assertEquals(listOf(TRIP to Aspect.LANDSCAPE), job.images)
    }

    @Test
    fun `close forgets the unfinished job`() = runTest(dispatcher) {
        job.pending = PendingJob(TRIP, MediaKind.VIDEO, VideoOptionsJson.encode(VideoOptions()), 1L)
        val vm = vm()
        vm.checkUnfinished()
        advanceUntilIdle()
        vm.dismissUnfinished()
        advanceUntilIdle()
        assertNull(vm.dialog.value)
        assertEquals(1, job.dismissed)
        assertTrue(job.videos.isEmpty())
    }

    @Test
    fun `nothing is offered while a job runs or when nothing was left`() = runTest(dispatcher) {
        val vm = vm()
        vm.checkUnfinished()
        advanceUntilIdle()
        assertNull(vm.dialog.value)

        job.pending = PendingJob(TRIP, MediaKind.VIDEO, VideoOptionsJson.encode(VideoOptions()), 1L)
        job.running = true
        vm.checkUnfinished()
        advanceUntilIdle()
        assertNull(vm.dialog.value)
    }

    @Test
    fun `an unfinished job of a trip with nothing left to make is forgotten`() = runTest(dispatcher) {
        job.pending = PendingJob(TRIP, MediaKind.VIDEO, VideoOptionsJson.encode(VideoOptions()), 1L)
        job.videoResult = StartResult.NOTHING_TO_MAKE
        val vm = vm()
        vm.checkUnfinished()
        advanceUntilIdle()
        vm.retryUnfinished()
        advanceUntilIdle()
        assertNull(vm.dialog.value)
        assertEquals(1, job.dismissed)
    }

    // --- the made files ---------------------------------------------------------------------------------------------

    @Test
    fun `only files the gallery says are gone are pruned, never on a failed check`() = runTest(dispatcher) {
        val media = listOf(
            TripMedia(1, TRIP, MediaKind.VIDEO, "content://gone", 0L, "{}"),
            TripMedia(2, TRIP, MediaKind.IMAGE, "content://there", 0L, "{}"),
            TripMedia(3, TRIP, MediaKind.IMAGE, "content://broken", 0L, "{}"),
        )
        val missing = missingMedia(media, { uri ->
            when (uri) {
                "content://gone" -> false
                "content://there" -> true
                else -> throw IllegalStateException("MediaStore query failed")
            }
        }, log)
        assertEquals(listOf(1L), missing)
        assertEquals(1, log.messages.size)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.openVideo(): VideoViewModel {
        val vm = vm()
        vm.open(TRIP, MediaKind.VIDEO)
        advanceUntilIdle()
        return vm
    }

    private fun expectedMinutes(options: VideoOptions, perFrame: Double): Int {
        val i = inputs ?: inputs(false)
        return VideoEstimate.minutes(VideoTimeline.plan(i.timeline, i.photos, options, i.dayStarts), perFrame)
    }

    private class FakeJob : VideoJobControl {
        override val state = MutableStateFlow<VideoJobState>(VideoJobState.Idle)
        override val runningTripId = MutableStateFlow<Long?>(null)
        var running = false
        override val isRunning: Boolean get() = running
        var videoResult = StartResult.STARTED
        var imageResult = StartResult.STARTED
        var videoError: Exception? = null
        val videos = mutableListOf<Pair<Long, VideoOptions>>()
        val images = mutableListOf<Pair<Long, Aspect>>()
        var cancels = 0
        var pending: PendingJob? = null
        var dismissed = 0

        override suspend fun startVideo(tripId: Long, options: VideoOptions): StartResult {
            videoError?.let { throw it }
            videos += tripId to options
            return videoResult
        }

        override suspend fun startImage(tripId: Long, aspect: Aspect): StartResult {
            images += tripId to aspect
            return imageResult
        }

        override fun cancel() {
            cancels++
        }

        override fun acknowledge() {
            val s = state.value
            if (s is VideoJobState.Done || s is VideoJobState.Failed || s is VideoJobState.Cancelled) state.value = VideoJobState.Idle
        }

        override suspend fun unfinished(): PendingJob? = if (running) null else pending

        override suspend fun dismissUnfinished() {
            dismissed++
            pending = null
        }
    }

    private companion object {
        const val TRIP = 7L
        const val MUSIC = "content://com.android.providers.media.documents/document/audio%3A12"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun trip(status: TripStatus) = Trip(TRIP, TripType.SINGLE_DAY, status, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)

        fun segment(endedAt: Long?, distanceM: Double) = RideSegment(
            1, TRIP, 0L, endedAt, LocalDate.of(2026, 10, 25),
            SegmentTotals(distanceM, 1_000, 1_000, 5.0, 0.0, 0.0, null, null),
        )

        /** 10 minutes at 5 m/s, one point a second. */
        fun inputs(canColorByHeart: Boolean): VideoInputs {
            val start = LocalDate.of(2026, 10, 25).atTime(9, 0).atZone(SEOUL).toInstant().toEpochMilli()
            val track = List(601) { TrackPoint(start + it * 1_000L, GeoPoint(37.52, 126.93 + it * 0.0000565), 20.0, 5f, 5f) }
            val totals = SegmentTotals(3_000.0, 600_000, 600_000, 5.5, 3.0, 3.0, 20.0, 23.0)
            val segment = RideSegment(1, TRIP, start, track.last().timeMillis, LocalDate.of(2026, 10, 25), totals)
            return VideoInputs(
                tripId = TRIP,
                timeline = RideTimelineBuilder.build(listOf(segment to track), SEOUL),
                photos = emptyList(),
                heartRate = emptyList(),
                dayStarts = emptyList(),
                title = "10월 25일 (일)",
                rideTitle = "아침 라이딩",
                totals = TripTotals(3_000.0, 600_000, 5.0, 5.5, 3.0, avgBpm = null, calories = null),
                canColorByHeart = canColorByHeart,
                firstStartedAt = start,
            )
        }
    }
}

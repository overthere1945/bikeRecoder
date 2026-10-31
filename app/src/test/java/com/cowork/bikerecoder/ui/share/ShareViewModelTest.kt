package com.cowork.bikerecoder.ui.share

import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.nav.FakeTripLabelStore
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.SharePick
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.shareStamp
import com.cowork.bikerecoder.ui.feed.FakeFeedMedia
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.PendingJob
import com.cowork.bikerecoder.video.StartResult
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.VideoJobControl
import com.cowork.bikerecoder.video.VideoJobState
import com.cowork.bikerecoder.video.VideoOptionsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * The share sheet (V1.5.0 spec §2, §3): the text it opens with (auto or saved, the stale line), editing, moods and
 * [처음 글로], what each button sends, the portrait image made on the spot and the action that follows it, the
 * refusals, cancel and close, and the text saved per trip on every press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val labels = FakeTripLabelStore()
    private val media = FakeFeedMedia()
    private val job = FakeJob()
    private var hashtags: String? = null
    private val events = mutableListOf<ShareEvent>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        trips.trips[TRIP] = trip(TripStatus.COMPLETED)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ShareViewModel(
        tripStore = trips,
        rideStore = rides,
        tripLabels = labels,
        tripMedia = media,
        defaultHashtags = { hashtags },
        candidates = { list -> list.map(::candidate) },
        job = job,
        compute = dispatcher,
        io = dispatcher,
    )

    /** The standard trip: one ride of 32.14 km on Sunday evening, 1 h 45 min moving, 123 m up; region "서울 강서구". */
    private suspend fun TestScope.opened(region: String? = "서울 강서구"): ShareViewModel {
        val id = rides.openSegment(TRIP, START, DAY)
        rides.closeSegment(id, END, SegmentTotals(32_140.0, 6_300_000, 7_200_000, 9.0, 123.0, 120.0, 10.0, 60.0))
        if (region != null) labels.setRegionName(TRIP, region)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.open(TRIP)
        advanceUntilIdle()
        return vm
    }

    // --- opening and the text ----------------------------------------------------------------------------------------

    @Test
    fun openBuildsAutoText() = runTest(dispatcher) {
        val vm = opened()

        val s = vm.sheet.value!!
        assertEquals(TRIP, s.tripId)
        assertEquals(AUTO, s.autoText)
        assertEquals(AUTO, s.text)
        assertFalse(s.stale)
        assertEquals(emptySet<Mood>(), s.moods)
        assertTrue(s.tripFinished)
        assertEquals(SharePick.MakeImage, s.general)
        assertEquals(listOf("보낼 것: 요약 이미지 · 세로 (누르면 만듭니다)"), s.fileLines)
        assertNull(s.blocked)
        assertFalse(s.making)
    }

    @Test
    fun openUsesSavedText() = runTest(dispatcher) {
        labels.setShareText(TRIP, "한강 바람 #상쾌", shareStamp(1, 32_140.0))
        val vm = opened()

        val s = vm.sheet.value!!
        assertEquals("한강 바람 #상쾌", s.text)
        assertEquals(AUTO, s.autoText)
        assertFalse(s.stale)
        assertEquals(setOf(Mood.SANGKWAE), s.moods)
    }

    @Test
    fun staleWhenStampDiffers() = runTest(dispatcher) {
        labels.setShareText(TRIP, "어제 쓴 글", "1:20000")
        val vm = opened()

        assertTrue(vm.sheet.value!!.stale)
        assertEquals("어제 쓴 글", vm.sheet.value!!.text)
    }

    @Test
    fun pressSavesNullWhenUnchanged() = runTest(dispatcher) {
        labels.setShareText(TRIP, "어제 쓴 글", "1:20000")
        val vm = opened()

        vm.edit(AUTO)
        vm.press(ShareTarget.COPY)
        advanceUntilIdle()

        val label = labels.label(TRIP)
        assertNull(label.shareText)
        assertNull(label.shareStamp)
        assertEquals("서울 강서구", label.regionName)
    }

    @Test
    fun pressSavesTextAndStampWhenEdited() = runTest(dispatcher) {
        val vm = opened()

        vm.edit("오늘은 바람이 좋았다")
        vm.press(ShareTarget.COPY)
        advanceUntilIdle()

        assertEquals(TripLabel(null, "서울 강서구", "오늘은 바람이 좋았다", "1:32140"), labels.label(TRIP))
    }

    @Test
    fun resetGoesBackToAuto() = runTest(dispatcher) {
        labels.setShareText(TRIP, "어제 쓴 글 #비", "1:32140")
        val vm = opened()

        vm.reset()

        assertEquals(AUTO, vm.sheet.value!!.text)
        assertEquals(emptySet<Mood>(), vm.sheet.value!!.moods)
        // Saved on the next press only.
        assertEquals("어제 쓴 글 #비", labels.label(TRIP).shareText)
    }

    @Test
    fun toggleMoodUpdatesTextAndSet() = runTest(dispatcher) {
        val vm = opened()

        vm.toggle(Mood.SANGKWAE)
        assertEquals("$AUTO #상쾌", vm.sheet.value!!.text)
        assertEquals(setOf(Mood.SANGKWAE), vm.sheet.value!!.moods)

        vm.toggle(Mood.CHOEGO)
        assertEquals(setOf(Mood.SANGKWAE, Mood.CHOEGO), vm.sheet.value!!.moods)

        vm.toggle(Mood.SANGKWAE)
        vm.toggle(Mood.CHOEGO)
        assertEquals(AUTO, vm.sheet.value!!.text)
        assertEquals(emptySet<Mood>(), vm.sheet.value!!.moods)
    }

    @Test
    fun editCapsAt2000CodePoints() = runTest(dispatcher) {
        val vm = opened()

        vm.edit("😀".repeat(2_001))

        val text = vm.sheet.value!!.text
        assertEquals(2_000, text.codePointCount(0, text.length))
        assertEquals("😀".repeat(2_000), text)
    }

    // --- the buttons -------------------------------------------------------------------------------------------------

    @Test
    fun copyEmitsCopy() = runTest(dispatcher) {
        val vm = opened()

        vm.press(ShareTarget.COPY)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Copy(AUTO)), events)
        assertTrue(job.images.isEmpty())
    }

    @Test
    fun facebookWithFreshVideoSendsVideo() = runTest(dispatcher) {
        val video = made(1, MediaKind.VIDEO, Aspect.LANDSCAPE, END + 60_000)
        val old = made(2, MediaKind.IMAGE, Aspect.PORTRAIT, END - 60_000)
        media.items.value = listOf(video, old)
        val vm = opened()
        assertEquals(SharePick.File(candidate(video)), vm.sheet.value!!.general)

        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.FACEBOOK, candidate(video), AUTO)), events)
        assertTrue(job.images.isEmpty())
    }

    @Test
    fun storyWithLandscapeOnlyMakesPortraitImage() = runTest(dispatcher) {
        val video = made(1, MediaKind.VIDEO, Aspect.LANDSCAPE, END + 60_000)
        media.items.value = listOf(video)
        val vm = opened()
        assertEquals(SharePick.MakeImage, vm.sheet.value!!.story)

        vm.press(ShareTarget.STORY)
        advanceUntilIdle()

        assertEquals(listOf(TRIP to Aspect.PORTRAIT), job.images)
        assertTrue(vm.sheet.value!!.making)
        assertTrue(events.isEmpty())

        val image = made(3, MediaKind.IMAGE, Aspect.PORTRAIT, END + 120_000)
        media.items.value = listOf(video, image)
        job.state.value = VideoJobState.Done(image, downscaled = false)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.STORY, candidate(image), AUTO)), events)
        assertEquals(1, job.acknowledges)
        val s = vm.sheet.value!!
        assertFalse(s.making)
        assertEquals(SharePick.File(candidate(image)), s.story)
        assertEquals(SharePick.File(candidate(video)), s.general)
    }

    // --- making ------------------------------------------------------------------------------------------------------

    @Test
    fun closingWhileMakingDoesNotSendLater() = runTest(dispatcher) {
        val vm = opened()
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()
        assertTrue(vm.sheet.value!!.making)

        vm.close()
        assertNull(vm.sheet.value)
        val image = made(3, MediaKind.IMAGE, Aspect.PORTRAIT, END + 120_000)
        media.items.value = listOf(image)
        job.state.value = VideoJobState.Done(image, downscaled = false)
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(0, job.acknowledges)
        assertEquals(0, job.cancels)
        assertTrue(job.state.value is VideoJobState.Done)
    }

    @Test
    fun failedMakingReturnsToButtonsWithMessage() = runTest(dispatcher) {
        val vm = opened()
        vm.press(ShareTarget.INSTAGRAM)
        advanceUntilIdle()

        job.state.value = VideoJobState.Failed("이미지를 만들지 못했습니다")
        advanceUntilIdle()

        val s = vm.sheet.value!!
        assertFalse(s.making)
        assertEquals("이미지를 만들지 못했습니다", s.blocked)
        assertTrue(s.canRetry)
        assertEquals(1, job.acknowledges)
        assertTrue(events.isEmpty())
    }

    @Test
    fun busyBlocksWithMessageAndRetry() = runTest(dispatcher) {
        job.imageResult = StartResult.BUSY
        val vm = opened()

        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        val s = vm.sheet.value!!
        assertEquals("영상을 만드는 중이라 지금은 이미지를 만들 수 없습니다", s.blocked)
        assertTrue(s.canRetry)
        assertFalse(s.making)
        assertFalse(s.enabled(ShareTarget.FACEBOOK))
        assertTrue(s.enabled(ShareTarget.EMAIL))

        // Dimmed buttons do nothing; e-mail and more send the text only.
        vm.press(ShareTarget.INSTAGRAM)
        vm.press(ShareTarget.MORE)
        advanceUntilIdle()
        assertEquals(1, job.images.size)
        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.MORE, null, AUTO)), events)

        job.imageResult = StartResult.STARTED
        vm.retry()
        advanceUntilIdle()

        assertNull(vm.sheet.value!!.blocked)
        assertFalse(vm.sheet.value!!.canRetry)
        assertTrue(vm.sheet.value!!.making)
        assertEquals(2, job.images.size)
    }

    @Test
    fun noInternetBlocksWithShareWording() = runTest(dispatcher) {
        job.imageResult = StartResult.NO_INTERNET
        val vm = opened()

        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()

        assertEquals("인터넷이 없어 이미지를 만들 수 없습니다", vm.sheet.value!!.blocked)
    }

    @Test
    fun cancelStopsJob() = runTest(dispatcher) {
        val vm = opened()
        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()

        vm.cancelMaking()

        assertEquals(1, job.cancels)
        assertFalse(vm.sheet.value!!.making)
        job.state.value = VideoJobState.Cancelled
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertNull(vm.sheet.value!!.blocked)
    }

    @Test
    fun cancelWhileStartingCancelsOnceStarted() = runTest(dispatcher) {
        job.startDelayMs = 5_000
        val vm = opened()
        vm.press(ShareTarget.FACEBOOK)
        advanceTimeBy(1_000)
        assertTrue(vm.sheet.value!!.making)

        vm.cancelMaking()
        assertFalse(vm.sheet.value!!.making)
        advanceUntilIdle()

        assertEquals(1, job.cancels)
        assertTrue(events.isEmpty())
    }

    // --- an unfinished trip ------------------------------------------------------------------------------------------

    @Test
    fun unfinishedTripTextOnly() = runTest(dispatcher) {
        trips.trips[TRIP] = trip(TripStatus.ACTIVE)
        val vm = opened()

        val s = vm.sheet.value!!
        assertFalse(s.tripFinished)
        assertEquals("여행을 마치면 그림을 만들 수 있습니다", s.blocked)
        assertFalse(s.canRetry)

        vm.press(ShareTarget.EMAIL)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.EMAIL, null, AUTO)), events)
        assertTrue(job.images.isEmpty())
    }

    // --- [저장] ------------------------------------------------------------------------------------------------------

    @Test
    fun saveWithFileNotices() = runTest(dispatcher) {
        media.items.value = listOf(made(1, MediaKind.IMAGE, Aspect.PORTRAIT, END + 60_000))
        val vm = opened()

        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Notice("갤러리에 저장되어 있습니다")), events)
        assertTrue(job.images.isEmpty())
    }

    @Test
    fun saveMakesThenNotices() = runTest(dispatcher) {
        val vm = opened()

        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()
        assertEquals(listOf(TRIP to Aspect.PORTRAIT), job.images)

        val image = made(3, MediaKind.IMAGE, Aspect.PORTRAIT, END + 120_000)
        media.items.value = listOf(image)
        job.state.value = VideoJobState.Done(image, downscaled = false)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Notice("갤러리에 저장했습니다")), events)
        assertEquals(SharePick.File(candidate(image)), vm.sheet.value!!.general)
    }

    private class FakeJob : VideoJobControl {
        override val state = MutableStateFlow<VideoJobState>(VideoJobState.Idle)
        override val runningTripId = MutableStateFlow<Long?>(null)
        override val isRunning: Boolean get() = false
        var imageResult = StartResult.STARTED
        var startDelayMs = 0L
        val images = mutableListOf<Pair<Long, Aspect>>()
        var cancels = 0
        var acknowledges = 0

        override suspend fun startVideo(tripId: Long, options: VideoOptions): StartResult = error("not used")

        override suspend fun startImage(tripId: Long, aspect: Aspect): StartResult {
            delay(startDelayMs)
            images += tripId to aspect
            if (imageResult == StartResult.STARTED) state.value = VideoJobState.Preparing(MediaKind.IMAGE)
            return imageResult
        }

        override fun cancel() {
            cancels++
        }

        override fun acknowledge() {
            acknowledges++
            val s = state.value
            if (s is VideoJobState.Done || s is VideoJobState.Failed || s is VideoJobState.Cancelled) state.value = VideoJobState.Idle
        }

        override suspend fun unfinished(): PendingJob? = null

        override suspend fun dismissUnfinished() = Unit
    }

    private companion object {
        const val TRIP = 7L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val DAY: LocalDate = LocalDate.of(2026, 10, 25)
        val START: Long = DAY.atTime(18, 30).atZone(SEOUL).toInstant().toEpochMilli()
        val END: Long = START + 7_200_000

        val AUTO = listOf(
            "저녁 라이딩 · 서울 강서구",
            "2026년 10월 25일 (일)",
            "32.1 km · 1시간 45분 · 상승 고도 123 m · 평균 18.4 km/h",
            "#자전거 #라이딩 #서울 #강서구",
        ).joinToString("\n")

        fun trip(status: TripStatus) = Trip(TRIP, TripType.SINGLE_DAY, status, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)

        fun made(id: Long, kind: MediaKind, aspect: Aspect, createdAt: Long) = TripMedia(
            id, TRIP, kind, "content://media/external/$id", createdAt, VideoOptionsJson.encode(VideoOptions(aspect = aspect)),
        )

        fun candidate(m: TripMedia) = ShareCandidate(
            m.uri, m.kind, VideoOptionsJson.decode(m.optionsJson).aspect, m.createdAt,
            if (m.kind == MediaKind.VIDEO) 12L * 1024 * 1024 else 2_000_000L,
        )
    }
}

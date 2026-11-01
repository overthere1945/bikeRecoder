package com.cowork.bikerecoder.ui.share

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.CardSize
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.nav.FakeTripLabelStore
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.NoSpaceException
import com.cowork.bikerecoder.share.ShareCache
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.ShareFile
import com.cowork.bikerecoder.share.ShareGallery
import com.cowork.bikerecoder.share.ShareItem
import com.cowork.bikerecoder.share.ShareMode
import com.cowork.bikerecoder.share.ShareSelection
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.cards.CardKind
import com.cowork.bikerecoder.share.cards.PhotoUnreadableException
import com.cowork.bikerecoder.share.cards.ShareCardMaker
import com.cowork.bikerecoder.share.shareStamp
import com.cowork.bikerecoder.ui.feed.FakeFeedMedia
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.VideoOptionsJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The share sheet (share cards spec §1, §3.1, §5, §6; text as in V1.5.0 spec §3): the text it opens with (auto with the
 * place tags, or saved; the stale line), editing, moods, place chips and [처음 글로]; the strip, its cards drawn on opening
 * and per mode, a press that waits for its cards, slides and the video, [저장] and [스티커], failures, cancel and close, and
 * the text saved per trip on every press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val labels = FakeTripLabelStore()
    private val media = FakeFeedMedia()
    private val cards = FakeCards()
    private val gallery = FakeGallery()
    private var hashtags: String? = null
    private val hashtagRequests = mutableListOf<ActivityType>()
    private var photos: List<Long> = emptyList()
    private var places: List<String> = emptyList()
    private var cache: ShareCache? = null

    /** While set, the first share-text write waits this long (a slow earlier save). */
    private var firstSaveDelayMs = 0L
    private var saves = 0
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
        tripLabels = object : TripLabelStore by labels {
            override suspend fun setShareText(tripId: Long, text: String?, stamp: String?) {
                if (saves++ == 0) delay(firstSaveDelayMs)
                labels.setShareText(tripId, text, stamp)
            }
        },
        tripMedia = media,
        defaultHashtags = { type ->
            hashtagRequests += type
            hashtags
        },
        candidates = { list -> list.map(::candidate) },
        photoIds = { photos },
        placeTags = { places },
        cards = cards,
        gallery = gallery,
        cache = cache,
        compute = dispatcher,
        io = dispatcher,
        clock = { NOW },
    )

    /** The standard trip: one ride of 32.14 km on Sunday evening, 1 h 45 min moving, 123 m up; region "서울 강서구". */
    private suspend fun TestScope.opened(region: String? = "서울 강서구"): ShareViewModel {
        ride(TRIP)
        if (region != null) labels.setRegionName(TRIP, region)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vmRef = vm
        vm.open(TRIP)
        advanceUntilIdle()
        return vm
    }

    private suspend fun ride(tripId: Long) {
        val id = rides.openSegment(tripId, START, DAY)
        rides.closeSegment(id, END, SegmentTotals(32_140.0, 6_300_000, 7_200_000, 9.0, 123.0, 120.0, 10.0, 60.0))
    }

    /** The sheet of the view model [opened] made. */
    private val sheet get() = vmRef!!.sheet.value!!
    private var vmRef: ShareViewModel? = null

    // --- opening and the text ----------------------------------------------------------------------------------------

    @Test
    fun openBuildsAutoText() = runTest(dispatcher) {
        opened()

        val s = sheet
        assertEquals(TRIP, s.tripId)
        assertEquals(AUTO, s.autoText)
        assertEquals(AUTO, s.text)
        assertFalse(s.stale)
        assertEquals(emptySet<Mood>(), s.moods)
        assertTrue(s.tripFinished)
        assertEquals(ShareMode.FEED, s.mode)
        assertFalse(s.waiting)
        assertNull(s.error)
        assertEquals(1, cards.forgets)
    }

    /** V1.9.0 spec §5.2: the numbers line and the default hashtags follow the trip's dominant activity. */
    @Test
    fun aRunTripTakesTheRunLineAndTags() = runTest(dispatcher) {
        val id = rides.openSegment(TRIP, START, DAY, ActivityType.RUN)
        rides.closeSegment(id, END, SegmentTotals(5_000.0, 1_660_000, 1_700_000, 4.5, 40.0, 30.0, 10.0, 60.0))
        labels.setRegionName(TRIP, "서울 강서구")
        val vm = vm()
        vm.open(TRIP)
        advanceUntilIdle()

        val lines = vm.sheet.value!!.autoText.lines()
        assertEquals("5.0 km · 27분 · 평균 5'32\"/km", lines[2])
        assertEquals("#러닝 #달리기 #서울 #강서구", lines[3])
        assertEquals(listOf(ActivityType.RUN), hashtagRequests)
    }

    /** A mixed trip: the pace is the dominant run's (5 km in 30 min), not the trip's 15 km in 50 min. */
    @Test
    fun aMixedTripsPaceIsTheDominantActivitysOnly() = runTest(dispatcher) {
        val run = rides.openSegment(TRIP, START, DAY, ActivityType.RUN)
        rides.closeSegment(run, START + 1_800_000, SegmentTotals(5_000.0, 1_800_000, 1_800_000, 4.5, 0.0, 0.0, null, null))
        val bike = rides.openSegment(TRIP, START + 1_800_000, DAY, ActivityType.BIKE)
        rides.closeSegment(bike, END, SegmentTotals(10_000.0, 1_200_000, 1_200_000, 9.0, 0.0, 0.0, null, null))
        val vm = vm()
        vm.open(TRIP)
        advanceUntilIdle()

        assertEquals("15.0 km · 50분 · 평균 6'00\"/km", vm.sheet.value!!.autoText.lines()[2])
    }

    @Test
    fun aHikeTripTakesTheHikeLineAndASavedHikeTag() = runTest(dispatcher) {
        val id = rides.openSegment(TRIP, START, DAY, ActivityType.HIKE)
        rides.closeSegment(id, END, SegmentTotals(6_400.0, 9_000_000, 12_000_000, 1.4, 520.0, 500.0, 300.0, 836.0))
        hashtags = "#산행"
        val vm = vm()
        vm.open(TRIP)
        advanceUntilIdle()

        val lines = vm.sheet.value!!.autoText.lines()
        assertEquals("산행 3시간 20분 · 상승 520 m · 최고 836 m", lines[2])
        assertEquals("#산행", lines[3])
        assertEquals(listOf(ActivityType.HIKE), hashtagRequests)
    }

    @Test
    fun openUsesSavedText() = runTest(dispatcher) {
        labels.setShareText(TRIP, "한강 바람 #상쾌", shareStamp(1, 32_140.0))
        opened()

        val s = sheet
        assertEquals("한강 바람 #상쾌", s.text)
        assertEquals(AUTO, s.autoText)
        assertFalse(s.stale)
        assertEquals(setOf(Mood.SANGKWAE), s.moods)
    }

    @Test
    fun staleWhenStampDiffers() = runTest(dispatcher) {
        labels.setShareText(TRIP, "어제 쓴 글", "1:20000")
        opened()

        assertTrue(sheet.stale)
        assertEquals("어제 쓴 글", sheet.text)
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

        assertEquals(AUTO, sheet.text)
        assertEquals(emptySet<Mood>(), sheet.moods)
        // Saved on the next press only.
        assertEquals("어제 쓴 글 #비", labels.label(TRIP).shareText)
    }

    @Test
    fun toggleMoodUpdatesTextAndSet() = runTest(dispatcher) {
        val vm = opened()

        vm.toggle(Mood.SANGKWAE)
        assertEquals("$AUTO #상쾌", sheet.text)
        assertEquals(setOf(Mood.SANGKWAE), sheet.moods)

        vm.toggle(Mood.CHOEGO)
        assertEquals(setOf(Mood.SANGKWAE, Mood.CHOEGO), sheet.moods)

        vm.toggle(Mood.SANGKWAE)
        vm.toggle(Mood.CHOEGO)
        assertEquals(AUTO, sheet.text)
        assertEquals(emptySet<Mood>(), sheet.moods)
    }

    @Test
    fun editCapsAt2000CodePoints() = runTest(dispatcher) {
        val vm = opened()

        vm.edit("😀".repeat(2_001))

        val text = sheet.text
        assertEquals(2_000, text.codePointCount(0, text.length))
        assertEquals("😀".repeat(2_000), text)
    }

    @Test
    fun staleHiddenAfterPressSaves() = runTest(dispatcher) {
        labels.setShareText(TRIP, "어제 쓴 글", "1:20000")
        val vm = opened()
        assertTrue(sheet.stale)

        vm.press(ShareTarget.COPY)
        advanceUntilIdle()

        assertFalse(sheet.stale)
        assertEquals("1:32140", labels.label(TRIP).shareStamp)
    }

    @Test
    fun staleHiddenAfterReset() = runTest(dispatcher) {
        labels.setShareText(TRIP, "어제 쓴 글", "1:20000")
        val vm = opened()

        vm.reset()
        assertFalse(sheet.stale)

        // Only while the box holds the saved text.
        vm.edit("어제 쓴 글")
        assertTrue(sheet.stale)
        vm.edit("어제 쓴 글!")
        assertFalse(sheet.stale)
    }

    @Test
    fun savesInPressOrder() = runTest(dispatcher) {
        firstSaveDelayMs = 5_000
        val vm = opened()

        vm.edit("먼저 쓴 글")
        vm.press(ShareTarget.COPY)
        vm.edit("나중에 쓴 글")
        vm.press(ShareTarget.COPY)
        advanceUntilIdle()

        assertEquals("나중에 쓴 글", labels.label(TRIP).shareText)
    }

    @Test
    fun facebookSavesText() = runTest(dispatcher) {
        cards.auto = true
        val vm = opened()

        vm.edit("내 글")
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        assertEquals("내 글", labels.label(TRIP).shareText)
        assertEquals("1:32140", labels.label(TRIP).shareStamp)
    }

    @Test
    fun copyEmitsCopy() = runTest(dispatcher) {
        val vm = opened()

        vm.press(ShareTarget.COPY)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Copy(AUTO)), events)
    }

    // --- place tags (spec §4.5) --------------------------------------------------------------------------------------

    @Test
    fun autoTextHasPlaceTags() = runTest(dispatcher) {
        places = listOf("#한강종주자전거길", "#국토종주")
        opened()

        assertEquals("#자전거 #라이딩 #서울 #강서구 #한강종주자전거길 #국토종주", sheet.autoText.lines()[3])
        assertEquals(sheet.autoText, sheet.text)
        assertEquals(places, sheet.placeTags)
        assertTrue(sheet.placeOn("#한강종주자전거길"))
    }

    @Test
    fun savedTextOpensUnchanged() = runTest(dispatcher) {
        places = listOf("#한강종주자전거길")
        labels.setShareText(TRIP, "한강 바람\n#자전거", shareStamp(1, 32_140.0))
        val vm = opened()

        assertEquals("한강 바람\n#자전거", sheet.text)
        assertFalse(sheet.placeOn("#한강종주자전거길"))

        vm.togglePlace("#한강종주자전거길")
        assertEquals("한강 바람\n#자전거 #한강종주자전거길", sheet.text)
        assertTrue(sheet.placeOn("#한강종주자전거길"))

        vm.togglePlace("#한강종주자전거길")
        assertEquals("한강 바람\n#자전거", sheet.text)
    }

    // --- the strip and its cards (spec §1.2, §3.1) -------------------------------------------------------------------

    @Test
    fun openRendersSatelliteAndPhotoCard() = runTest(dispatcher) {
        photos = listOf(21, 22, 23)
        opened()

        val s = sheet
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)
        val photoCard = CardKey(CardKind.PHOTO, ShareMode.FEED, 21)
        assertEquals(listOf(satellite, photoCard), cards.requests)
        assertEquals(mapOf(satellite to CardStatus.Rendering, photoCard to CardStatus.Rendering), s.cards)
        assertEquals(21L, s.photoCardMediaId)
        assertEquals(
            listOf(
                ShareItem.SatelliteCard, ShareItem.PhotoCard,
                ShareItem.Photo(21, 1), ShareItem.Photo(22, 2), ShareItem.Photo(23, 3),
            ),
            s.items,
        )
        assertEquals(ShareSelection.initial(ShareMode.FEED, s.items, cardsAllowed = true), s.selection)

        cards.complete(satellite)
        advanceUntilIdle()
        assertEquals(CardStatus.Ready(fileOf(satellite)), sheet.cards[satellite])
        assertEquals(CardStatus.Rendering, sheet.cards[photoCard])
    }

    @Test
    fun openWithNoReadablePhotos() = runTest(dispatcher) {
        opened()

        assertEquals(listOf<ShareItem>(ShareItem.SatelliteCard), sheet.items)
        assertEquals(listOf<ShareItem>(ShareItem.SatelliteCard), sheet.selection.chosen)
        assertNull(sheet.photoCardMediaId)
        assertEquals(listOf(CardKey(CardKind.SATELLITE, ShareMode.FEED, null)), cards.requests)
    }

    @Test
    fun openSweepsTheCache(@TempDir root: File) = runTest(dispatcher) {
        val old = File(root, "3/feed-satellite.jpg").apply { parentFile!!.mkdirs(); writeText("x") }
        old.setLastModified(NOW - ShareCache.MAX_AGE_MS - 1)
        cache = ShareCache(root, { it.path }, { NOW })
        opened()

        assertFalse(old.exists())
    }

    @Test
    fun setModeRendersMissingCardsOnly() = runTest(dispatcher) {
        photos = listOf(21)
        cards.auto = true
        val vm = opened()

        vm.setMode(ShareMode.STORY)
        advanceUntilIdle()
        vm.setMode(ShareMode.FEED)
        advanceUntilIdle()

        assertEquals(
            listOf(
                CardKey(CardKind.SATELLITE, ShareMode.FEED, null), CardKey(CardKind.PHOTO, ShareMode.FEED, 21),
                CardKey(CardKind.SATELLITE, ShareMode.STORY, null), CardKey(CardKind.PHOTO, ShareMode.STORY, 21),
            ),
            cards.requests,
        )
    }

    @Test
    fun setPhotoCardRendersOnlyThePhotoCard() = runTest(dispatcher) {
        photos = listOf(21, 22)
        cards.auto = true
        val vm = opened()

        vm.setPhotoCard(22)
        advanceUntilIdle()

        assertEquals(22L, sheet.photoCardMediaId)
        assertEquals(CardKey(CardKind.PHOTO, ShareMode.FEED, 22), cards.requests.last())
        assertEquals(3, cards.requests.size)
    }

    @Test
    fun tapNumbersAndTellsTheLimit() = runTest(dispatcher) {
        photos = (1L..12L).toList()
        val vm = opened()
        // Satellite + four spread photos.
        assertEquals(5, sheet.selection.chosen.size)

        sheet.items.filterIsInstance<ShareItem.Photo>().forEach { if (it !in sheet.selection.chosen) vm.tap(it) }
        advanceUntilIdle()

        assertEquals(ShareSelection.MAX_ITEMS, sheet.selection.chosen.size)
        assertEquals(ShareEvent.Notice(ShareSelection.MSG_MAX), events.last())
    }

    // --- sending (spec §3.1, §5) -------------------------------------------------------------------------------------

    @Test
    fun pressWaitsForCardThenSends() = runTest(dispatcher) {
        photos = listOf(21, 22)
        val vm = opened()
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)
        // Satellite card, photo 1, photo 2.
        assertEquals(listOf(ShareItem.SatelliteCard, ShareItem.Photo(21, 1), ShareItem.Photo(22, 2)), sheet.selection.chosen)

        vm.press(ShareTarget.INSTAGRAM)
        advanceUntilIdle()
        assertTrue(sheet.waiting)
        assertTrue(events.isEmpty())
        assertTrue(cards.slides.isEmpty())

        cards.complete(satellite)
        advanceUntilIdle()

        assertFalse(sheet.waiting)
        assertEquals(listOf(21L to CardSize.FEED, 22L to CardSize.FEED), cards.slides)
        assertEquals(
            listOf<ShareEvent>(ShareEvent.Send(ShareTarget.INSTAGRAM, listOf(fileOf(satellite), slide(21), slide(22)), AUTO)),
            events,
        )
    }

    @Test
    fun filesFollowTapOrder() = runTest(dispatcher) {
        photos = listOf(21, 22)
        cards.auto = true
        val vm = opened()

        vm.tap(ShareItem.SatelliteCard)
        vm.tap(ShareItem.PhotoCard)
        vm.tap(ShareItem.SatelliteCard)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        val files = (events.single() as ShareEvent.Send).files
        assertEquals(
            listOf(slide(21), slide(22), fileOf(CardKey(CardKind.PHOTO, ShareMode.FEED, 21)), fileOf(CardKey(CardKind.SATELLITE, ShareMode.FEED, null))),
            files,
        )
    }

    @Test
    fun closeWhileWaitingSendsNothing() = runTest(dispatcher) {
        val vm = opened()
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()
        assertTrue(sheet.waiting)

        vm.close()
        cards.complete(CardKey(CardKind.SATELLITE, ShareMode.FEED, null))
        advanceUntilIdle()

        assertNull(vm.sheet.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancelWhileWaitingKeepsDrawing() = runTest(dispatcher) {
        val vm = opened()
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        vm.cancelMaking()
        assertFalse(sheet.waiting)
        cards.complete(satellite)
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(CardStatus.Ready(fileOf(satellite)), sheet.cards[satellite])
    }

    @Test
    fun reopenDropsTheOldSheetsCards() = runTest(dispatcher) {
        val vm = opened()
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)

        vm.open(TRIP)
        advanceUntilIdle()
        // The first sheet's card ends after the second sheet opened: not taken.
        cards.complete(satellite, call = 0)
        advanceUntilIdle()

        assertEquals(2, cards.forgets)
        assertEquals(CardStatus.Rendering, sheet.cards[satellite])
    }

    @Test
    fun cardFailureShowsErrorAndRetry() = runTest(dispatcher) {
        val vm = opened()
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        cards.fail(satellite, IllegalStateException("map"))
        advanceUntilIdle()

        assertEquals(CardStatus.Failed(noSpace = false), sheet.cards[satellite])
        assertEquals(ShareViewModel.MSG_CARD_FAILED, sheet.error)
        assertFalse(sheet.waiting)
        assertTrue(events.isEmpty())

        vm.retryCards()
        advanceUntilIdle()
        assertNull(sheet.error)
        // A card failure is drawn again; the button is not pressed again.
        assertFalse(sheet.waiting)
        assertEquals(CardStatus.Rendering, sheet.cards[satellite])
        assertEquals(listOf(satellite, satellite), cards.requests)

        cards.complete(satellite)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()
        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.FACEBOOK, listOf(fileOf(satellite)), AUTO)), events)
    }

    /** Main.immediate runs a send that never suspends inside its launch: the sheet still sends on the next press. */
    @Test
    fun pressAgainAfterAnImmediateSend() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        cards.auto = true
        val vm = opened()

        vm.press(ShareTarget.FACEBOOK)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        val send = ShareEvent.Send(ShareTarget.FACEBOOK, listOf(fileOf(CardKey(CardKind.SATELLITE, ShareMode.FEED, null))), AUTO)
        assertEquals(listOf<ShareEvent>(send, send), events)
        assertFalse(sheet.waiting)
    }

    @Test
    fun setModeDropsTheWaitingPress() = runTest(dispatcher) {
        val vm = opened()
        vm.press(ShareTarget.INSTAGRAM)
        advanceUntilIdle()
        assertTrue(sheet.waiting)

        vm.setMode(ShareMode.STORY)
        assertFalse(sheet.waiting)
        advanceUntilIdle()
        cards.complete(CardKey(CardKind.SATELLITE, ShareMode.FEED, null))
        cards.complete(CardKey(CardKind.SATELLITE, ShareMode.STORY, null))
        advanceUntilIdle()

        assertFalse(sheet.waiting)
        assertTrue(events.isEmpty())
    }

    @Test
    fun tapDropsTheWaitingPress() = runTest(dispatcher) {
        photos = listOf(21)
        val vm = opened()
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()
        assertTrue(sheet.waiting)

        vm.tap(ShareItem.SatelliteCard)
        cards.complete(CardKey(CardKind.SATELLITE, ShareMode.FEED, null))
        advanceUntilIdle()

        assertFalse(sheet.waiting)
        assertEquals(listOf<ShareItem>(ShareItem.Photo(21, 1)), sheet.selection.chosen)
        assertTrue(events.isEmpty())
        assertTrue(cards.slides.isEmpty())
    }

    /** A slide that fails (not unreadable) is the press's own failure: [다시 시도] presses the same button again. */
    @Test
    fun retryAfterASlideFailurePressesAgain() = runTest(dispatcher) {
        photos = listOf(21)
        cards.auto = true
        cards.slideFailure = IllegalStateException("decode")
        val vm = opened()

        vm.press(ShareTarget.INSTAGRAM)
        advanceUntilIdle()
        assertEquals(ShareViewModel.MSG_CARD_FAILED, sheet.error)
        assertFalse(sheet.waiting)
        assertTrue(events.isEmpty())

        cards.slideFailure = null
        vm.retryCards()
        advanceUntilIdle()

        assertNull(sheet.error)
        val satellite = fileOf(CardKey(CardKind.SATELLITE, ShareMode.FEED, null))
        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.INSTAGRAM, listOf(satellite, slide(21)), AUTO)), events)
    }

    @Test
    fun retryAfterAStickerFailurePressesAgain() = runTest(dispatcher) {
        cards.stickerFailure = NoSpaceException(IOException("ENOSPC"))
        val vm = opened()

        vm.press(ShareTarget.STICKER)
        advanceUntilIdle()
        assertEquals(ShareViewModel.MSG_NO_SPACE, sheet.error)
        assertTrue(events.isEmpty())

        cards.stickerFailure = null
        vm.retryCards()
        advanceUntilIdle()

        assertNull(sheet.error)
        assertEquals(listOf(STICKER to "bikeRecoder_sticker_20261025_213000.png"), gallery.pngs)
        assertEquals(listOf<ShareEvent>(ShareEvent.Notice(ShareViewModel.MSG_STICKER_SAVED, long = true)), events)
    }

    @Test
    fun retryAfterASaveFailurePressesAgain() = runTest(dispatcher) {
        cards.auto = true
        gallery.failure = IOException("provider")
        val vm = opened()

        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()
        assertEquals(ShareViewModel.MSG_CARD_FAILED, sheet.error)

        gallery.failure = null
        vm.retryCards()
        advanceUntilIdle()

        assertNull(sheet.error)
        assertEquals(listOf<ShareEvent>(ShareEvent.Notice("카드 1장을 갤러리에 저장했습니다")), events)
    }

    @Test
    fun noSpaceMessage() = runTest(dispatcher) {
        val vm = opened()
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)
        cards.fail(satellite, NoSpaceException(IOException("ENOSPC")))
        advanceUntilIdle()
        assertEquals(CardStatus.Failed(noSpace = true), sheet.cards[satellite])
        // Shown when a press needs the card.
        assertNull(sheet.error)

        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        assertEquals("저장 공간이 부족해 카드를 만들지 못했습니다", sheet.error)
        assertFalse(sheet.waiting)
        assertTrue(events.isEmpty())
    }

    @Test
    fun storyModeSendsOneAndResets() = runTest(dispatcher) {
        photos = listOf(21, 22)
        cards.auto = true
        val vm = opened()
        vm.tap(ShareItem.Photo(21, 1))

        vm.setMode(ShareMode.STORY)
        advanceUntilIdle()
        val story = CardKey(CardKind.SATELLITE, ShareMode.STORY, null)
        assertEquals(listOf<ShareItem>(ShareItem.SatelliteCard), sheet.selection.chosen)
        assertEquals(CardStatus.Ready(fileOf(story)), sheet.cards[story])

        vm.press(ShareTarget.STORY)
        advanceUntilIdle()
        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.STORY, listOf(fileOf(story)), AUTO)), events)

        // A photo in story mode is a story-sized slide.
        vm.tap(ShareItem.Photo(22, 2))
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()
        assertEquals(ShareEvent.Send(ShareTarget.FACEBOOK, listOf(slide(22, "story")), AUTO), events.last())

        vm.setMode(ShareMode.FEED)
        assertEquals(ShareSelection.initial(ShareMode.FEED, sheet.items, cardsAllowed = true), sheet.selection)
    }

    @Test
    fun videoSendsAlone() = runTest(dispatcher) {
        photos = listOf(21)
        cards.auto = true
        val video = made(1, MediaKind.VIDEO, Aspect.LANDSCAPE, END + 60_000)
        val older = made(2, MediaKind.VIDEO, Aspect.PORTRAIT, END + 30_000)
        val image = made(3, MediaKind.IMAGE, Aspect.PORTRAIT, END + 90_000)
        val beforeRide = made(4, MediaKind.VIDEO, Aspect.PORTRAIT, END - 60_000)
        media.items.value = listOf(video, older, image, beforeRide)
        val vm = opened()
        val item = ShareItem.Video(candidate(video))
        assertEquals(item, sheet.items.last())
        assertEquals(1, sheet.items.count { it is ShareItem.Video })

        vm.tap(item)
        assertEquals(listOf<ShareItem>(item), sheet.selection.chosen)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        val sent = ShareFile(video.uri, "video/mp4", 12L * 1024 * 1024)
        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.FACEBOOK, listOf(sent), AUTO)), events)
        assertTrue(cards.slides.isEmpty())
    }

    @Test
    fun nothingChosenEmailSendsText() = runTest(dispatcher) {
        val vm = opened()
        vm.tap(ShareItem.SatelliteCard)
        assertTrue(sheet.selection.chosen.isEmpty())
        for (t in listOf(ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY, ShareTarget.SAVE)) {
            assertFalse(sheet.enabled(t), t.name)
        }
        assertTrue(sheet.enabled(ShareTarget.EMAIL))

        vm.press(ShareTarget.FACEBOOK)
        vm.press(ShareTarget.EMAIL)
        vm.press(ShareTarget.MORE)
        advanceUntilIdle()

        assertEquals(
            listOf<ShareEvent>(
                ShareEvent.Send(ShareTarget.EMAIL, emptyList(), AUTO),
                ShareEvent.Send(ShareTarget.MORE, emptyList(), AUTO),
            ),
            events,
        )
    }

    /** Spec §6: a photo that cannot be read leaves the strip (the others are numbered again); the send goes on without it. */
    @Test
    fun unreadableSlideIsLeftOut() = runTest(dispatcher) {
        photos = listOf(21, 22, 23)
        cards.auto = true
        cards.unreadable += 22L
        val vm = opened()
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)

        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        assertEquals(
            listOf<ShareEvent>(ShareEvent.Send(ShareTarget.FACEBOOK, listOf(fileOf(satellite), slide(21), slide(23)), AUTO)),
            events,
        )
        assertEquals(
            listOf(ShareItem.SatelliteCard, ShareItem.PhotoCard, ShareItem.Photo(21, 1), ShareItem.Photo(23, 2)),
            sheet.items,
        )
        assertEquals(listOf(ShareItem.SatelliteCard, ShareItem.Photo(21, 1), ShareItem.Photo(23, 2)), sheet.selection.chosen)
        assertEquals(21L, sheet.photoCardMediaId)
    }

    /** Spec §6: the photo card's background cannot be read: the next photo takes its place. */
    @Test
    fun unreadablePhotoCardBackgroundMovesOn() = runTest(dispatcher) {
        photos = listOf(21, 22)
        cards.auto = true
        cards.unreadable += 21L
        opened()

        assertEquals(listOf(ShareItem.SatelliteCard, ShareItem.PhotoCard, ShareItem.Photo(22, 1)), sheet.items)
        assertEquals(listOf(ShareItem.SatelliteCard, ShareItem.Photo(22, 1)), sheet.selection.chosen)
        assertEquals(22L, sheet.photoCardMediaId)
        assertEquals(CardStatus.Ready(fileOf(CardKey(CardKind.PHOTO, ShareMode.FEED, 22))), sheet.cardOf(ShareItem.PhotoCard))
    }

    @Test
    fun lastUnreadablePhotoTakesThePhotoCard() = runTest(dispatcher) {
        photos = listOf(21)
        cards.auto = true
        cards.unreadable += 21L
        opened()

        assertEquals(listOf<ShareItem>(ShareItem.SatelliteCard), sheet.items)
        assertEquals(listOf<ShareItem>(ShareItem.SatelliteCard), sheet.selection.chosen)
        assertNull(sheet.photoCardMediaId)
    }

    // --- [저장] and [스티커] (spec §5.5, §5.6) -------------------------------------------------------------------------

    @Test
    fun saveCardsOnlyAndOnce() = runTest(dispatcher) {
        photos = listOf(21)
        cards.auto = true
        val vm = opened()
        val satellite = CardKey(CardKind.SATELLITE, ShareMode.FEED, null)

        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()
        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()

        assertEquals(listOf(fileOf(satellite) to "bikeRecoder_card_20261025_213000.jpg"), gallery.jpegs)
        assertEquals(
            listOf<ShareEvent>(ShareEvent.Notice("카드 1장을 갤러리에 저장했습니다"), ShareEvent.Notice("이미 저장했습니다")),
            events,
        )

        // Another card (the photo card) is saved on its own.
        vm.tap(ShareItem.PhotoCard)
        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()
        assertEquals(2, gallery.jpegs.size)
        assertEquals(fileOf(CardKey(CardKind.PHOTO, ShareMode.FEED, 21)), gallery.jpegs.last().first)
        assertEquals(ShareEvent.Notice("카드 1장을 갤러리에 저장했습니다"), events.last())
    }

    @Test
    fun saveWithOnlyPhotos() = runTest(dispatcher) {
        photos = listOf(21)
        cards.auto = true
        val vm = opened()

        vm.tap(ShareItem.SatelliteCard)
        vm.press(ShareTarget.SAVE)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Notice(ShareViewModel.MSG_ALREADY_IN_GALLERY)), events)
        assertTrue(gallery.jpegs.isEmpty())
    }

    @Test
    fun stickerSavesOnce() = runTest(dispatcher) {
        val vm = opened()

        vm.press(ShareTarget.STICKER)
        advanceUntilIdle()
        vm.press(ShareTarget.STICKER)
        advanceUntilIdle()

        assertEquals(1, cards.stickers)
        assertEquals(listOf(STICKER to "bikeRecoder_sticker_20261025_213000.png"), gallery.pngs)
        assertEquals(
            listOf<ShareEvent>(
                ShareEvent.Notice("스티커를 저장했습니다. 인스타 스토리에서 사진 스티커로 붙이세요", long = true),
                ShareEvent.Notice("이미 저장했습니다"),
            ),
            events,
        )
    }

    // --- an unfinished trip ------------------------------------------------------------------------------------------

    @Test
    fun unfinishedTripHasNoCards() = runTest(dispatcher) {
        trips.trips[TRIP] = trip(TripStatus.ACTIVE)
        photos = listOf(21, 22)
        val vm = opened()

        val s = sheet
        assertFalse(s.tripFinished)
        assertTrue(cards.requests.isEmpty())
        assertEquals(listOf(ShareItem.Photo(21, 1), ShareItem.Photo(22, 2)), s.selection.chosen)
        assertFalse(s.enabled(ShareTarget.SAVE))
        assertFalse(s.enabled(ShareTarget.STICKER))
        assertTrue(s.enabled(ShareTarget.FACEBOOK))
        assertEquals("여행을 마치면 카드를 만들 수 있습니다", ShareViewModel.MSG_UNFINISHED)

        // A card cannot be picked; photos are sent as slides.
        vm.tap(ShareItem.SatelliteCard)
        vm.press(ShareTarget.SAVE)
        vm.press(ShareTarget.STICKER)
        vm.press(ShareTarget.FACEBOOK)
        advanceUntilIdle()

        assertEquals(listOf(ShareItem.Photo(21, 1), ShareItem.Photo(22, 2)), sheet.selection.chosen)
        assertTrue(cards.requests.isEmpty())
        assertEquals(0, cards.stickers)
        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.FACEBOOK, listOf(slide(21), slide(22)), AUTO)), events)
    }

    @Test
    fun unfinishedTripWithoutPhotosSendsTextOnly() = runTest(dispatcher) {
        trips.trips[TRIP] = trip(TripStatus.ACTIVE)
        val vm = opened()
        assertTrue(sheet.selection.chosen.isEmpty())

        vm.press(ShareTarget.INSTAGRAM)
        vm.press(ShareTarget.EMAIL)
        advanceUntilIdle()

        assertEquals(listOf<ShareEvent>(ShareEvent.Send(ShareTarget.EMAIL, emptyList(), AUTO)), events)
    }

    /** Cards drawn on demand: each call gets a deferred to complete or fail, unless [auto]. */
    private class FakeCards : ShareCardMaker {
        var auto = false
        val requests = mutableListOf<CardKey>()
        private val calls = mutableListOf<Pair<CardKey, CompletableDeferred<ShareFile>>>()
        val slides = mutableListOf<Pair<Long, CardSize>>()
        val unreadable = mutableSetOf<Long>()

        /** Thrown by the next slide or sticker while set. */
        var slideFailure: Exception? = null
        var stickerFailure: Exception? = null
        var stickers = 0
        var forgets = 0

        override suspend fun card(tripId: Long, kind: CardKind, size: CardSize, photoMediaId: Long?): ShareFile {
            val key = CardKey(kind, if (size == CardSize.STORY) ShareMode.STORY else ShareMode.FEED, photoMediaId)
            requests += key
            if (photoMediaId != null && photoMediaId in unreadable) throw PhotoUnreadableException(photoMediaId)
            if (auto) return fileOf(key)
            val deferred = CompletableDeferred<ShareFile>()
            calls += key to deferred
            return deferred.await()
        }

        override suspend fun slide(tripId: Long, mediaId: Long, size: CardSize): ShareFile {
            if (mediaId in unreadable) throw PhotoUnreadableException(mediaId)
            slideFailure?.let { throw it }
            slides += mediaId to size
            return slide(mediaId, if (size == CardSize.STORY) "story" else "feed")
        }

        override suspend fun sticker(tripId: Long): ShareFile {
            stickerFailure?.let { throw it }
            stickers++
            return STICKER
        }

        override fun forget() {
            forgets++
        }

        /** Completes the [call]th (default: last) waiting draw of [key]. */
        fun complete(key: CardKey, call: Int = -1) = callOf(key, call).complete(fileOf(key))

        fun fail(key: CardKey, e: Throwable) = callOf(key).completeExceptionally(e)

        private fun callOf(key: CardKey, call: Int = -1): CompletableDeferred<ShareFile> {
            val of = calls.filter { it.first == key }.map { it.second }
            return if (call < 0) of.last() else of[call]
        }
    }

    private class FakeGallery : ShareGallery {
        val jpegs = mutableListOf<Pair<ShareFile, String>>()
        val pngs = mutableListOf<Pair<ShareFile, String>>()
        var failure: Exception? = null

        override suspend fun saveJpeg(file: ShareFile, displayName: String) {
            failure?.let { throw it }
            jpegs += file to displayName
        }

        override suspend fun savePng(file: ShareFile, displayName: String) {
            pngs += file to displayName
        }
    }

    private companion object {
        const val TRIP = 7L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val DAY: LocalDate = LocalDate.of(2026, 10, 25)
        val START: Long = DAY.atTime(18, 30).atZone(SEOUL).toInstant().toEpochMilli()
        val END: Long = START + 7_200_000
        val NOW: Long = DAY.atTime(21, 30).atZone(SEOUL).toInstant().toEpochMilli()
        val STICKER = ShareFile("content://share/7/sticker.png", "image/png", 300_000)

        val AUTO = listOf(
            "저녁 라이딩 · 서울 강서구",
            "2026년 10월 25일 (일)",
            "32.1 km · 1시간 45분 · 상승 고도 123 m · 평균 18.4 km/h",
            "#자전거 #라이딩 #서울 #강서구",
        ).joinToString("\n")

        fun trip(status: TripStatus) = Trip(TRIP, TripType.SINGLE_DAY, status, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)

        fun fileOf(key: CardKey) = ShareFile(
            "content://share/7/${key.mode.name.lowercase()}-${key.kind.name.lowercase()}-${key.photoMediaId}.jpg",
            "image/jpeg",
            500_000,
        )

        fun slide(mediaId: Long, mode: String = "feed") =
            ShareFile("content://share/7/$mode-photo-$mediaId.jpg", "image/jpeg", 400_000)

        fun made(id: Long, kind: MediaKind, aspect: Aspect, createdAt: Long) = TripMedia(
            id, TRIP, kind, "content://media/external/$id", createdAt, VideoOptionsJson.encode(VideoOptions(aspect = aspect)),
        )

        fun candidate(m: TripMedia) = ShareCandidate(
            m.uri, m.kind, VideoOptionsJson.decode(m.optionsJson).aspect, m.createdAt,
            if (m.kind == MediaKind.VIDEO) 12L * 1024 * 1024 else 2_000_000L,
        )
    }
}

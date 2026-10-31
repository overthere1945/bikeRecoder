package com.cowork.bikerecoder.ui.feed

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.stats.Streak
import com.cowork.bikerecoder.ui.history.CONFIRM_DELETE_TRIP
import com.cowork.bikerecoder.ui.history.HistoryDialog
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The stateless summary feed on fake data (V1.4.0 spec §2): strip, month headers, cards, empty state, delete and [정리]. */
class FeedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val video = TripMedia(5, 7, MediaKind.VIDEO, "content://media/external/video/5", 1_000L, "{}")

    private val routeA = listOf(
        GeoPoint(37.50, 127.00), GeoPoint(37.51, 127.02), GeoPoint(37.53, 127.03), GeoPoint(37.54, 127.07),
        GeoPoint(37.52, 127.10),
    )

    /** October's trip: a satellite picture can be drawn, photos (three and two more), a video, a record. */
    private fun cardA(thumbFile: File? = null) = FeedCardState(
        tripId = 7,
        title = "저녁 라이딩 · 서울 강서구",
        dateLine = "10월 25일 (일) 오후 6:12",
        inProgress = false,
        distanceM = 32_100.0,
        movingTimeMs = 6_300_000,
        ascentM = 123.4,
        avgSpeedMps = 32_100.0 / 6_300.0,
        avgBpm = 132,
        photoMediaIds = listOf(1, 2, 3),
        morePhotos = 2,
        video = video,
        trophy = true,
        thumbKey = "0123456789ab",
        thumbFile = thumbFile,
        routes = listOf(routeA to "#E53935"),
    )

    /** September's trip: several days, still in progress, nothing else yet. */
    private val cardB = FeedCardState(
        tripId = 3,
        title = "2일 여행",
        dateLine = "9월 27일~28일",
        inProgress = true,
        distanceM = 1_580.0,
        movingTimeMs = 720_000,
        ascentM = 0.0,
        avgSpeedMps = 1_580.0 / 720.0,
        avgBpm = null,
        photoMediaIds = emptyList(),
        morePhotos = 0,
        video = null,
        trophy = false,
        thumbKey = null,
        thumbFile = null,
        routes = emptyList(),
    )

    private val strip = WeekStripState(64_200.0, 4, 12_300.0, 100, Streak(4, includesThisWeek = true))
    private val stripWithoutGoal = strip.copy(weeklyGoalKm = null)

    private val months = listOf(FeedMonth("2026년 10월 · 32.1 km · 1회", listOf(cardA())), FeedMonth("2026년 9월 · 1.6 km · 1회", listOf(cardB)))

    private val opened = mutableListOf<Long>()
    private val shared = mutableListOf<Long>()
    private val videos = mutableListOf<TripMedia>()
    private val requested = mutableListOf<Long>()
    private val shown = mutableListOf<Long>()
    private val hidden = mutableListOf<Long>()
    private var statsOpened = 0
    private var goalsOpened = 0
    private var recordings = 0
    private var confirmed = 0

    private val photo: suspend (Long, Int) -> ImageBitmap? = { id, size ->
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(PHOTO_COLORS[(id % PHOTO_COLORS.size).toInt()]) }
            .asImageBitmap()
    }

    private fun show(
        months: List<FeedMonth> = this.months,
        strip: WeekStripState? = this.strip,
        ready: Boolean = true,
        hiddenShortCount: Int = 0,
        dialog: HistoryDialog? = null,
        onConfirmCleanup: () -> Unit = {},
        onRequestCleanup: () -> Unit = {},
        onRequestDelete: (Long) -> Unit = { requested += it },
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                FeedContent(
                    weekStrip = strip,
                    months = months,
                    ready = ready,
                    hiddenShortCount = hiddenShortCount,
                    dialog = dialog,
                    photo = photo,
                    onOpenTrip = { opened += it },
                    onOpenVideo = { videos += it },
                    onOpenStatsWeek = { statsOpened++ },
                    onStartRecording = { recordings++ },
                    onSetGoals = { goalsOpened++ },
                    onRequestDelete = onRequestDelete,
                    onConfirmDelete = { confirmed++ },
                    onDismiss = onDismiss,
                    onRequestCleanup = onRequestCleanup,
                    onConfirmCleanup = onConfirmCleanup,
                    onCardShown = { shown += it },
                    onCardHidden = { hidden += it },
                    onShare = { shared += it },
                )
            }
        }
    }

    /** Spec §2.3: title, date line, the three numbers, the average line, "+N" on the third photo, ▶ 영상 and 🏆. */
    @Test
    fun cardShowsTitleDateNumbersPhotosVideoAndTrophy() {
        show()

        for (text in listOf(
            "2026년 10월 · 32.1 km · 1회", "저녁 라이딩 · 서울 강서구", "10월 25일 (일) 오후 6:12",
            "32.1 km", "1시간 45분", "123 m", "평균 18.3 km/h · ♥ 132", "+2",
        )) {
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(FeedTags.video(7), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("▶ 영상", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(FeedTags.trophy(7), useUnmergedTree = true).assertIsDisplayed()
        for (index in 0..2) composeRule.onNodeWithTag(FeedTags.photo(7, index), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun cardWithoutPhotosVideoOrRecordHasNone() {
        show(months = listOf(FeedMonth("2026년 9월 · 1.6 km · 1회", listOf(cardB))))

        composeRule.onNodeWithText("2일 여행").assertIsDisplayed()
        composeRule.onNodeWithText("9월 27일~28일 · 진행 중").assertIsDisplayed()
        composeRule.onNodeWithText("평균 7.9 km/h").assertIsDisplayed()
        composeRule.onNodeWithTag(FeedTags.video(3), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(FeedTags.trophy(3), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(FeedTags.photo(3, 0), useUnmergedTree = true).assertDoesNotExist()
    }

    /** V1.6.0 spec §4.2: an imported trip's date line ends in " · 가져옴"; a trip recorded here has none. */
    @Test
    fun importedMarkOnCard() {
        show(months = listOf(FeedMonth("2026년 10월 · 32.1 km · 1회", listOf(cardA().copy(imported = true)))))

        composeRule.onNodeWithText("10월 25일 (일) 오후 6:12 · 가져옴").assertIsDisplayed()
    }

    /** V1.5.0 spec §1.1: the paper plane at the end of the numbers opens the share sheet of the trip, not the trip. */
    @Test
    fun shareIconOnCardWithRide() {
        show(months = listOf(FeedMonth("2026년 10월 · 32.1 km · 1회", listOf(cardA().copy(canShare = true)))))

        composeRule.onNodeWithContentDescription("공유").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(listOf(7L), shared)
        assertEquals(emptyList<Long>(), opened)
    }

    /** A trip without a finished ride of 10 m or more has nothing to share. */
    @Test
    fun noShareIconWithoutRide() {
        show(months = listOf(FeedMonth("2026년 9월 · 0 m · 1회", listOf(cardB.copy(distanceM = 0.0, canShare = false)))))

        composeRule.onNodeWithText("2일 여행").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("공유").assertDoesNotExist()
    }

    @Test
    fun tapOnCardOpensTheTrip() {
        show()

        composeRule.onNodeWithTag(FeedTags.card(7)).performClick()

        assertEquals(listOf(7L), opened)
        assertEquals(emptyList<TripMedia>(), videos)
    }

    @Test
    fun tapOnPhotoOpensTheTripToo() {
        show()

        composeRule.onNodeWithTag(FeedTags.photo(7, 1), useUnmergedTree = true).performClick()

        assertEquals(listOf(7L), opened)
    }

    @Test
    fun tapOnVideoOpensTheVideoNotTheTrip() {
        show()

        composeRule.onNodeWithTag(FeedTags.video(7), useUnmergedTree = true).performClick()

        assertEquals(listOf(video), videos)
        assertEquals(emptyList<Long>(), opened)
    }

    /** The old history's rule: a long press asks before deleting. */
    @Test
    fun longPressAsksBeforeDeleting() {
        var dialog by mutableStateOf<HistoryDialog?>(null)
        composeRule.setContent {
            MaterialTheme {
                FeedContent(
                    weekStrip = strip, months = months, ready = true, hiddenShortCount = 0, dialog = dialog, photo = photo,
                    onOpenTrip = { opened += it }, onOpenVideo = {}, onOpenStatsWeek = {}, onStartRecording = {}, onSetGoals = {},
                    onRequestDelete = {
                        requested += it
                        dialog = HistoryDialog.ConfirmDelete(it)
                    },
                    onConfirmDelete = {
                        confirmed++
                        dialog = null
                    },
                    onDismiss = { dialog = null }, onRequestCleanup = {}, onConfirmCleanup = {},
                )
            }
        }

        composeRule.onNodeWithTag(FeedTags.card(7)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(listOf(7L), requested)
        assertEquals(emptyList<Long>(), opened)

        composeRule.onNodeWithText(CONFIRM_DELETE_TRIP).assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertIsDisplayed()
        composeRule.onNodeWithText("삭제").performClick()
        composeRule.waitForIdle()
        assertEquals(1, confirmed)
        composeRule.onNodeWithText(CONFIRM_DELETE_TRIP).assertDoesNotExist()
    }

    /** Spec §2.2: no trip at all. */
    @Test
    fun emptyFeedOffersToStartRecording() {
        show(months = emptyList())

        composeRule.onNodeWithText("첫 주행을 기록해 보세요").assertIsDisplayed()
        composeRule.onNodeWithText("기록 시작").performClick()
        assertEquals(1, recordings)
    }

    @Test
    fun noEmptyTextBeforeTheFeedIsRead() {
        show(months = emptyList(), ready = false)

        composeRule.onNodeWithText("첫 주행을 기록해 보세요").assertDoesNotExist()
        composeRule.onNodeWithTag(FeedTags.STRIP).assertIsDisplayed()
    }

    /** Review M2: before the rides and the goal are read there is no strip ("0.0 km", [목표 정하기]), only the list. */
    @Test
    fun noStripBeforeItIsRead() {
        show(strip = null)

        composeRule.onNodeWithTag(FeedTags.STRIP).assertDoesNotExist()
        composeRule.onNodeWithText("목표 정하기").assertDoesNotExist()
        composeRule.onNodeWithText("저녁 라이딩 · 서울 강서구").assertIsDisplayed()
    }

    /** Spec §2.1: "이번 주 64.2 km · 4회", "지난주 이맘때보다 +12.3 km", "4주 연속"; a tap opens the week's stats. */
    @Test
    fun stripShowsTheWeekAndOpensTheStats() {
        show()

        composeRule.onNodeWithText("이번 주 64.2 km · 4회").assertIsDisplayed()
        composeRule.onNodeWithText("지난주 이맘때보다 +12.3 km").assertIsDisplayed()
        composeRule.onNodeWithText("4주 연속").assertIsDisplayed()
        composeRule.onNodeWithText("목표 정하기").assertDoesNotExist()
        composeRule.onNodeWithTag(FeedTags.RING, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("64 / 100 km", useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(FeedTags.STRIP).performClick()

        assertEquals(1, statsOpened)
        assertEquals(0, goalsOpened)
    }

    @Test
    fun stripShowsAMinusAndNoComparisonWithoutOne() {
        show(strip = strip.copy(diffM = -4_000.0, streak = Streak(0, includesThisWeek = false)))
        composeRule.onNodeWithText("지난주 이맘때보다 −4.0 km").assertIsDisplayed()
        composeRule.onNodeWithText("주 연속", substring = true).assertDoesNotExist()
    }

    @Test
    fun stripWithoutComparisonHasNoComparisonLine() {
        show(strip = strip.copy(diffM = null))
        composeRule.onNodeWithText("지난주 이맘때보다", substring = true).assertDoesNotExist()
    }

    /** Spec §2.1: without a weekly goal the ring's place has [목표 정하기]; it opens the goal dialog, not the stats. */
    @Test
    fun noGoalShowsTheSetGoalsButton() {
        show(strip = stripWithoutGoal)

        composeRule.onNodeWithTag(FeedTags.RING, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("목표 정하기").assertIsDisplayed().performClick()

        assertEquals(1, goalsOpened)
        assertEquals(0, statsOpened)
    }

    @Test
    fun reachedGoalSaysAchieved() {
        show(strip = strip.copy(distanceM = 100_500.0))

        composeRule.onNodeWithContentDescription("100 / 100 km 달성", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * Review M3: the ring's number and "/ 10,000 km" stay inside the ring with a large goal at twice the font size (the text
     * is part of the drawing: sized in dp and shrunk to fit).
     */
    @Test
    fun ringTextStaysInsideTheRingWithALargeGoalAndFont() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    WeekStrip(strip.copy(distanceM = 10_234_900.0, weeklyGoalKm = 10_000), onOpenStatsWeek = {}, onSetGoals = {})
                }
            }
        }

        composeRule.onNodeWithContentDescription("10,234 / 10,000 km 달성", useUnmergedTree = true).assertIsDisplayed()
        val ring = composeRule.onNodeWithTag(FeedTags.RING, useUnmergedTree = true).getBoundsInRoot()
        val text = composeRule.onNodeWithTag(FeedTags.RING_TEXT, useUnmergedTree = true).getBoundsInRoot()
        // Inside the stroke (8 dp wide): within the inner 68 dp, around the middle.
        assertTrue("text $text in ring $ring", text.width <= 60.dp && text.height <= 60.dp)
        assertTrue("text $text in ring $ring", text.left >= ring.left + 8.dp && text.right <= ring.right - 8.dp)
        assertTrue("text $text in ring $ring", text.top >= ring.top + 8.dp && text.bottom <= ring.bottom - 8.dp)
    }

    /** Spec §6.10: "짧은 기록 N개 숨김" [정리] under the list, then "짧은 기록 N개를 삭제할까요?" [삭제] [취소]. */
    @Test
    fun hiddenShortTripsOfferCleanup() {
        var dialog by mutableStateOf<HistoryDialog?>(null)
        var hiddenCount by mutableStateOf(2)
        var cleanups = 0
        composeRule.setContent {
            MaterialTheme {
                FeedContent(
                    weekStrip = strip, months = months, ready = true, hiddenShortCount = hiddenCount, dialog = dialog, photo = photo,
                    onOpenTrip = {}, onOpenVideo = {}, onOpenStatsWeek = {}, onStartRecording = {}, onSetGoals = {},
                    onRequestDelete = {}, onConfirmDelete = { confirmed++ }, onDismiss = { dialog = null },
                    onRequestCleanup = { dialog = HistoryDialog.ConfirmCleanup(hiddenCount) },
                    onConfirmCleanup = {
                        cleanups++
                        dialog = null
                        hiddenCount = 0
                    },
                )
            }
        }

        composeRule.onNodeWithTag(FeedTags.LIST).performScrollToNode(hasText("짧은 기록 2개 숨김"))
        composeRule.onNodeWithText("짧은 기록 2개 숨김").assertIsDisplayed()
        composeRule.onNodeWithText("정리").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("짧은 기록 2개를 삭제할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("취소").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("짧은 기록 2개를 삭제할까요?").assertDoesNotExist()
        assertEquals(0, cleanups)

        composeRule.onNodeWithText("정리").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("삭제").performClick()
        composeRule.waitForIdle()
        assertEquals(1, cleanups)
        assertEquals("not the single-trip delete", 0, confirmed)
        composeRule.onNodeWithText("짧은 기록", substring = true).assertDoesNotExist()
    }

    /** Only short trips: the feed says there is no trip yet and still offers [정리]. */
    @Test
    fun onlyShortTripsStillOfferCleanup() {
        show(months = emptyList(), hiddenShortCount = 3)

        composeRule.onNodeWithText("첫 주행을 기록해 보세요").assertIsDisplayed()
        composeRule.onNodeWithText("짧은 기록 3개 숨김").assertIsDisplayed()
        composeRule.onNodeWithText("정리").assertIsDisplayed()
    }

    /** Spec §2.4: the cards on view are reported, and a card that scrolls away is reported as hidden. */
    @Test
    fun cardsComingAndGoingAreReported() {
        val many = (1L..8L).map { cardB.copy(tripId = it, title = "라이딩 $it") }
        show(months = listOf(FeedMonth("2026년 10월 · 12.6 km · 8회", many)))
        composeRule.waitForIdle()

        assertTrue("the first card is on view: $shown", 1L in shown)
        assertTrue("the last is not: $shown", 8L !in shown)
        assertEquals(emptyList<Long>(), hidden)

        composeRule.onNodeWithTag(FeedTags.LIST).performScrollToNode(hasTestTag(FeedTags.card(8)))
        composeRule.waitForIdle()

        assertTrue("the last card came into view: $shown", 8L in shown)
        assertTrue("the first card left: $hidden", 1L in hidden)
    }

    /**
     * Review I3: a picture this process has decoded before is drawn on the first frame (no line drawing first). The file is
     * not on disk, so only the cache can give it; another missing file that was never decoded shows the line drawing.
     */
    @Test
    fun aDecodedPictureShowsOnTheFirstFrame() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val cached = File(dir, "7-cached-only.webp").also { it.delete() }
        val missing = File(dir, "3-never-made.webp").also { it.delete() }
        FeedPictureCache[cached] = satellitePicture().asImageBitmap()
        composeRule.mainClock.autoAdvance = false

        show(months = listOf(FeedMonth("2026년 10월 · 33.7 km · 2회", listOf(cardA(cached), cardB.copy(thumbKey = "x", thumbFile = missing)))))

        composeRule.onNodeWithTag(FeedTags.picture(7), useUnmergedTree = true).assertExists()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FeedTags.picture(3), useUnmergedTree = true).assertDoesNotExist()
    }

    /** One picture of the feed for the record: Download/bikeRecoderTest/v140-feed.png (pulled by hand). */
    @Test
    fun screenshot() {
        val picture = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "feed-test-picture.png")
        picture.outputStream().use { satellitePicture().compress(Bitmap.CompressFormat.PNG, 100, it) }
        show(months = listOf(FeedMonth("2026년 10월 · 32.1 km · 1회", listOf(cardA(picture)))) + months.drop(1))
        composeRule.waitForIdle()
        Thread.sleep(500) // the picture and the thumbnails are read off the main thread
        composeRule.waitForIdle()

        screenshot("v140-feed")
    }

    /** A stand-in for a satellite picture: dark green ground, a track in orange, the start green and the end red. */
    private fun satellitePicture(): Bitmap {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(46, 74, 52))
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(255, 143, 0)
            strokeWidth = 12f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val xs = floatArrayOf(160f, 380f, 560f, 820f, 1100f)
        val ys = floatArrayOf(560f, 380f, 430f, 230f, 300f)
        for (i in 0 until xs.size - 1) canvas.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], track)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = AndroidColor.rgb(67, 160, 71)
        canvas.drawCircle(xs.first(), ys.first(), 18f, dot)
        dot.color = AndroidColor.rgb(229, 57, 53)
        canvas.drawCircle(xs.last(), ys.last(), 18f, dot)
        return bitmap
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
        const val TAG = "FeedScreenTest"
        val PHOTO_COLORS = intArrayOf(
            AndroidColor.rgb(198, 120, 80), AndroidColor.rgb(90, 140, 190), AndroidColor.rgb(120, 170, 100),
            AndroidColor.rgb(180, 150, 200),
        )
    }
}

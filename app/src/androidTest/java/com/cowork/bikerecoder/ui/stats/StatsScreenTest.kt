package com.cowork.bikerecoder.ui.stats

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The stats tab on fake rides (V1.4.0 spec §3), with today fixed on Wednesday 2026-10-28. */
class StatsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRides(rides: List<RideStat>) : RideStatsSource {
        val flow = MutableStateFlow(rides)
        override fun observeRides(): Flow<List<RideStat>> = flow
    }

    private class FakeGoals(weekly: Int?, yearly: Int?) : GoalSettings {
        val flow = MutableStateFlow(GoalKm(weekly, yearly))
        val saved = mutableListOf<GoalKm>()
        override val goals: Flow<GoalKm> = flow
        override suspend fun setGoals(weeklyKm: Int?, yearlyKm: Int?) {
            saved += GoalKm(weeklyKm, yearlyKm)
            flow.value = GoalKm(weeklyKm, yearlyKm)
        }
    }

    private fun ride(id: Long, date: String, km: Double, minutes: Long, ascent: Double) = RideStat(
        segmentId = id,
        tripId = id,
        startedAt = LocalDate.parse(date).atTime(8, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),
        localDate = LocalDate.parse(date),
        distanceM = km * 1000.0,
        movingTimeMs = minutes * 60_000,
        ascentM = ascent,
        endedAt = 1L,
    )

    /** 14 rides from August to this week: the week of 10/26 has 64.3 km, and each of the five weeks from 9/28 has a ride. */
    private val rides = listOf(
        ride(1, "2026-08-02", 38.4, 130, 210.0),
        ride(2, "2026-08-15", 21.0, 70, 80.0),
        ride(3, "2026-08-23", 45.2, 160, 320.0),
        ride(4, "2026-08-30", 28.8, 95, 140.0),
        ride(5, "2026-09-06", 18.0, 62, 60.0),
        ride(6, "2026-09-14", 42.1, 150, 280.0),
        ride(7, "2026-09-20", 25.5, 85, 110.0),
        ride(8, "2026-09-29", 31.6, 100, 190.0),
        ride(9, "2026-10-06", 24.1, 80, 95.0),
        ride(10, "2026-10-13", 36.0, 120, 205.0),
        ride(11, "2026-10-20", 28.2, 92, 150.0),
        ride(12, "2026-10-25", 33.6, 105, 175.0),
        ride(13, "2026-10-26", 42.3, 140, 230.0),
        ride(14, "2026-10-27", 22.0, 70, 90.0),
    )

    private val goals = FakeGoals(weekly = 100, yearly = 1000)
    private val opened = mutableListOf<Long>()
    private val cumulative = mutableListOf<PeriodFilter>()
    private lateinit var viewModel: StatsViewModel

    private fun show(rides: List<RideStat> = this.rides, goals: FakeGoals = this.goals, scrollToTop: Int = 0) {
        viewModel = StatsViewModel(FakeRides(rides), goals, { LocalDate.of(2026, 10, 28) }, WarnLog { _, _ -> })
        composeRule.setContent {
            MaterialTheme {
                // The tab sits in the app's scaffold; here the system bars are kept off it by hand.
                Surface(modifier = Modifier.systemBarsPadding()) {
                    StatsScreen(viewModel, onOpenSegment = { opened += it }, onOpenCumulative = { cumulative += it }, scrollToTop = scrollToTop)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Nodes inside the goal card, which is one clickable (merged) node. */
    private fun unmerged(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(StatsTags.LIST).performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    @Test
    fun startsOnThisMonthAndTheEndsAreDimmed() {
        show()

        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.MONTH)).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.WEEK)).assertIsNotSelected()
        composeRule.onNodeWithTag(StatsTags.metric(StatMetric.DISTANCE)).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("2026년 10월")
        composeRule.onNodeWithTag(StatsTags.DISTANCE).assertTextEquals("186.2 km")
        composeRule.onNodeWithTag(StatsTags.RIDES).assertTextEquals("6회")
        // Not past this month, but back to August (the first ride's month).
        composeRule.onNodeWithTag(StatsTags.NEXT).assertIsNotEnabled()
        composeRule.onNodeWithTag(StatsTags.PREVIOUS).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("2026년 9월")
        composeRule.onNodeWithTag(StatsTags.PREVIOUS).performClick()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("2026년 8월")
        composeRule.onNodeWithTag(StatsTags.PREVIOUS).assertIsNotEnabled()
        composeRule.onNodeWithTag(StatsTags.NEXT).assertIsEnabled()
    }

    @Test
    fun kindChipsSwitchThePeriodAndAllHasNoArrows() {
        show()

        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.WEEK)).performClick()
        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.WEEK)).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.MONTH)).assertIsNotSelected()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("10월 5주 (10/26~11/1)")
        composeRule.onNodeWithTag(StatsTags.DISTANCE).assertTextEquals("64.3 km")

        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.YEAR)).performClick()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("2026년")

        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.ALL)).performClick()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("전체")
        composeRule.onNodeWithTag(StatsTags.PREVIOUS).assertDoesNotExist()
        composeRule.onNodeWithTag(StatsTags.NEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(StatsTags.COMPARISON).assertDoesNotExist()
    }

    @Test
    fun comparisonSentenceIsShown() {
        show()

        // October up to the 28th: 186.2 km; September up to the 28th: 18.0 + 42.1 + 25.5 = 85.6 km.
        composeRule.onNodeWithTag(StatsTags.COMPARISON).assertTextEquals("지난달 이맘때보다 +100.6 km (+118%)")
    }

    @Test
    fun tappingABarShowsItsValueAndAgainHidesIt() {
        show()

        composeRule.onNodeWithTag(StatsTags.BAR_VALUE).assertTextEquals(" ")
        composeRule.onNodeWithTag(StatsTags.bar(3)).performClick() // 4주: 10/19~10/25
        composeRule.onNodeWithTag(StatsTags.BAR_VALUE).assertTextEquals("10월 4주 · 61.8 km")

        composeRule.onNodeWithTag(StatsTags.bar(3)).performClick()
        composeRule.onNodeWithTag(StatsTags.BAR_VALUE).assertTextEquals(" ")

        composeRule.onNodeWithTag(StatsTags.bar(4)).performClick()
        composeRule.onNodeWithTag(StatsTags.BAR_VALUE).assertTextEquals("10월 5주 · 64.3 km")
        composeRule.onNodeWithTag(StatsTags.metric(StatMetric.TIME)).performClick()
        composeRule.onNodeWithTag(StatsTags.BAR_VALUE).assertTextEquals(" ")
        composeRule.onNodeWithTag(StatsTags.bar(4)).performClick()
        composeRule.onNodeWithTag(StatsTags.BAR_VALUE).assertTextEquals("10월 5주 · 3시간 30분")
    }

    @Test
    fun barsOfDaysThatHaveNotComeCannotBeTapped() {
        show()
        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.WEEK)).performClick()

        // Thursday 10/29 to Sunday 11/1 are still to come.
        composeRule.onNodeWithTag(StatsTags.bar(3)).assertIsNotEnabled()
        composeRule.onNodeWithTag(StatsTags.bar(2)).assertIsEnabled().performClick() // Wed 10/28
        composeRule.onNodeWithTag(StatsTags.BAR_VALUE).assertTextEquals("10월 28일 (수) · 0.0 km")
    }

    @Test
    fun recordRowOpensItsSegment() {
        show()

        scrollTo(StatsTags.record(0))
        composeRule.onNodeWithText("가장 긴 주행").assertIsDisplayed()
        composeRule.onNodeWithText("45.2 km · 2026년 8월 23일").assertIsDisplayed()
        composeRule.onNodeWithTag(StatsTags.record(0)).performClick()

        assertEquals(listOf(3L), opened)
    }

    @Test
    fun weekAndMonthRecordsShowThatPeriodHere() {
        show()

        scrollTo(StatsTags.record(4))
        // 9/14~9/20 had 42.1 + 25.5 km, more than this week.
        composeRule.onNodeWithText("67.6 km · 9월 3주").assertIsDisplayed()
        composeRule.onNodeWithTag(StatsTags.record(4)).performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Long>(), opened)
        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.WEEK)).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("9월 3주 (9/14~9/20)")
        composeRule.onNodeWithTag(StatsTags.DISTANCE).assertTextEquals("67.6 km")
    }

    @Test
    fun monthRecordShowsThatMonth() {
        show()
        composeRule.onNodeWithTag(StatsTags.PREVIOUS).performClick()
        composeRule.onNodeWithTag(StatsTags.PREVIOUS).performClick()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("2026년 8월")

        scrollTo(StatsTags.record(5))
        composeRule.onNodeWithText("186.2 km · 2026년 10월").assertIsDisplayed()
        composeRule.onNodeWithTag(StatsTags.record(5)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.MONTH)).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.TITLE).assertTextEquals("2026년 10월")
    }

    @Test
    fun streakRowHasNoTarget() {
        show()

        scrollTo(StatsTags.record(6))
        composeRule.onNodeWithText("가장 긴 연속 주행").assertIsDisplayed()
        composeRule.onNodeWithTag(StatsTags.record(6)).performClick()

        assertEquals(emptyList<Long>(), opened)
        assertEquals(emptyList<PeriodFilter>(), cumulative)
    }

    @Test
    fun recordsAreNotShownUnderTwoRides() {
        show(rides = rides.take(1))

        composeRule.onNodeWithTag(StatsTags.RECORDS).assertDoesNotExist()
        composeRule.onNodeWithText("개인 기록").assertDoesNotExist()
    }

    @Test
    fun cumulativeEntryOpensWithThePeriodsFilter() {
        show()

        scrollTo(StatsTags.CUMULATIVE)
        composeRule.onNodeWithText("누적 지도 · 이 기간 6회 ›").assertIsDisplayed().performClick()
        assertEquals(listOf(PeriodFilter(year = 2026, month = 10)), cumulative)

        composeRule.onNodeWithTag(StatsTags.LIST).performScrollToNode(hasTestTag(StatsTags.kind(PeriodKind.ALL)))
        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.ALL)).performClick()
        scrollTo(StatsTags.CUMULATIVE)
        composeRule.onNodeWithText("누적 지도 · 이 기간 14회 ›").performClick()
        assertEquals(PeriodFilter(), cumulative.last())
    }

    @Test
    fun goalCardShowsTheProgressAndOpensTheGoalWindow() {
        show()

        composeRule.onNodeWithText("64 / 100 km").assertIsDisplayed()
        unmerged(StatsTags.YEARLY_TEXT).assertTextEquals("436 / 1,000 km")
        unmerged(StatsTags.PROJECTION).assertTextEquals("지금 속도면 연말에 약 530 km")
        unmerged(StatsTags.YEARLY_REACHED).assertDoesNotExist()
        composeRule.onNodeWithText("5주 연속 주행 중").assertIsDisplayed()

        composeRule.onNodeWithTag(StatsTags.GOAL_CARD).performClick()
        composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).assertIsDisplayed()
        composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).performTextReplacement("120")
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(GoalKm(120, 1000)), goals.saved)
        composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).assertDoesNotExist()
        composeRule.onNodeWithText("64 / 120 km").assertIsDisplayed()
    }

    @Test
    fun reachedGoalsSayDone() {
        show(goals = FakeGoals(weekly = 50, yearly = 400))

        composeRule.onNodeWithText("달성").assertIsDisplayed() // the ring
        unmerged(StatsTags.YEARLY_REACHED).assertIsDisplayed()
    }

    @Test
    fun withoutGoalsTheCardAsksAndTheButtonOpensTheWindow() {
        val noGoals = FakeGoals(null, null)
        show(goals = noGoals)

        composeRule.onNodeWithText("목표를 정하면 진행 상황을 보여 드립니다").assertIsDisplayed()
        unmerged(StatsTags.WEEKLY_RING).assertDoesNotExist()
        composeRule.onNodeWithTag(StatsTags.GOAL_SET).performClick()
        composeRule.onNodeWithTag(GoalDialogTags.weeklyChip(100)).performClick()
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(GoalKm(100, null)), noGoals.saved)
        composeRule.onNodeWithText("64 / 100 km").assertIsDisplayed()
        unmerged(StatsTags.YEARLY_TEXT).assertDoesNotExist()
    }

    @Test
    fun scrollToTopBringsTheListBackUp() {
        var tick = 0
        viewModel = StatsViewModel(FakeRides(rides), goals, { LocalDate.of(2026, 10, 28) }, WarnLog { _, _ -> })
        val counter = androidx.compose.runtime.mutableIntStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                StatsScreen(viewModel, onOpenSegment = {}, onOpenCumulative = {}, scrollToTop = counter.intValue)
            }
        }
        scrollTo(StatsTags.CUMULATIVE)
        composeRule.onNodeWithTag(StatsTags.GOAL_CARD).assertDoesNotExist()

        composeRule.runOnUiThread { counter.intValue = ++tick }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(StatsTags.GOAL_CARD).assertIsDisplayed()
    }

    /** The two pictures for the record (Download/bikeRecoderTest, pulled by hand): with goals, and without. */
    @Test
    fun screenshotWithGoals() {
        show()
        Thread.sleep(300)
        screenshot("v140-stats-goals")
    }

    @Test
    fun screenshotWithoutGoals() {
        show(goals = FakeGoals(null, null))
        Thread.sleep(300)
        screenshot("v140-stats-nogoals")
    }

    /** The whole screen into Download/bikeRecoderTest/[name].png (an older file of the name is deleted first). */
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val resolver = instrumentation.targetContext.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/bikeRecoderTest/"
        runCatching {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("$name.png", relative),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "StatsScreenTest"
    }
}

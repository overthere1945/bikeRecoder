package com.cowork.bikerecoder.ui.cumulative

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.CumulativeSummary
import com.cowork.bikerecoder.core.post.PeriodFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The cumulative map screen on fake state, its map replaced (spec §4, §6.4–§6.6, §6.8): the summary, the period menus
 * and quick periods, the ride list that opens records, the map's highlight and info card, the empty texts.
 */
class CumulativeMapScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val all = listOf(
        entry(1, LocalDate.of(2025, 12, 30), 10_000.0, 1_800_000L),
        entry(2, LocalDate.of(2026, 1, 5), 20_000.0, 3_600_000L),
        entry(3, LocalDate.of(2026, 3, 1), 5_000.0, 900_000L),
    )

    private fun entry(id: Long, date: LocalDate, distanceM: Double, movingTimeMs: Long) =
        CumulativeEntry(id, date, distanceM, movingTimeMs, listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.01)))

    /** Rides of October 2026 (and one of 30 September); "today" is Tuesday 2026-10-27. */
    private val october = listOf(
        entry(11, LocalDate.of(2026, 10, 24), 1_590.0, 900_000L),
        entry(12, LocalDate.of(2026, 10, 20), 5_000.0, 1_200_000L),
        entry(13, LocalDate.of(2026, 10, 27), 3_000.0, 600_000L),
        entry(14, LocalDate.of(2026, 9, 30), 2_000.0, 300_000L),
    )

    /** What the view model would show of [rides] (the same rules). */
    private fun stateFor(
        period: PeriodFilter,
        rides: List<CumulativeEntry> = all,
        quick: QuickPeriod? = null,
        selected: Long? = null,
        settled: Boolean = true,
    ) = cumulativeStateOf(CumulativeSummary.rides(rides), settled, period, quick, selected)

    /**
     * Shows [initial]; the controls act like the view model's on [rides]. A tap on the fake map acts like a tap on
     * [mapTapHits]'s line (the view model's select: the same ride again lets go of it).
     */
    private fun show(
        initial: CumulativeMapState,
        rides: List<CumulativeEntry> = all,
        onOpenRide: (Long) -> Unit = {},
        mapTapHits: Long? = null,
        onBack: () -> Unit = {},
    ) {
        var state by mutableStateOf(initial)
        fun choose(period: PeriodFilter, quick: QuickPeriod? = null) {
            state = stateFor(period, rides, quick)
        }
        val actions = CumulativeMapActions(
            showAll = { choose(PeriodFilter()) },
            selectYear = { choose(PeriodFilter(it)) },
            selectMonth = { month -> choose(PeriodFilter(state.period.year, month)) },
            selectWeek = { start -> choose(PeriodFilter(state.period.year, state.period.month, start)) },
            thisWeek = { choose(PeriodFilter(2026, 10, LocalDate.of(2026, 10, 26)), QuickPeriod.THIS_WEEK) },
            thisMonth = { choose(PeriodFilter(2026, 10), QuickPeriod.THIS_MONTH) },
            openRide = onOpenRide,
        )
        fun mapTap() {
            val id = mapTapHits
            state = stateFor(state.period, rides, state.quick, selected = if (id == null || state.selected == id) null else id)
        }
        composeRule.setContent {
            MaterialTheme {
                CumulativeMapContent(
                    state = state,
                    actions = actions,
                    onBack = onBack,
                    map = { modifier -> Box(modifier.testTag(MAP).clickable { mapTap() }) },
                )
            }
        }
    }

    @Test
    fun summaryOfAllRides() {
        var back = 0
        show(stateFor(PeriodFilter())) { back++ }

        composeRule.onNodeWithText("누적 지도").assertIsDisplayed()
        composeRule.onNodeWithText("35.00km · 주행 3회 · 1시간 45분").assertIsDisplayed()
        composeRule.onNodeWithTag(MAP).assertIsDisplayed()
        composeRule.onNodeWithTag(CumulativeMapTags.ALL).assertIsSelected()
        composeRule.onNodeWithContentDescription("뒤로").performClick()
        assertEquals(1, back)
    }

    @Test
    fun periodMenusFilterTheSummary() {
        show(stateFor(PeriodFilter()))

        // No year yet: [월 ▾] cannot be opened.
        composeRule.onNodeWithTag(CumulativeMapTags.MONTH).assertIsNotEnabled()

        // [연도 ▾] lists the years with a ride, newest first.
        composeRule.onNodeWithText("연도 ▾").performClick()
        val y2026 = composeRule.onNodeWithText("2026년").fetchSemanticsNode().boundsInRoot.top
        val y2025 = composeRule.onNodeWithText("2025년").fetchSemanticsNode().boundsInRoot.top
        assertTrue("2026년 above 2025년", y2026 < y2025)
        composeRule.onNodeWithText("2026년").performClick()

        composeRule.onNodeWithText("25.00km · 주행 2회 · 1시간 15분").assertIsDisplayed()
        composeRule.onNodeWithText("2026년 ▾").assertIsDisplayed()
        composeRule.onNodeWithTag(CumulativeMapTags.ALL).assertIsNotSelected()
        composeRule.onNodeWithTag(CumulativeMapTags.MONTH).assertIsEnabled()

        // [월 ▾]: the whole year, then the months of 2026 with a ride.
        composeRule.onNodeWithText("월 ▾").performClick()
        composeRule.onNodeWithText("1월").assertIsDisplayed()
        composeRule.onAllNodesWithText("2월").assertCountEquals(0)
        composeRule.onNodeWithText("3월").performClick()
        composeRule.onNodeWithText("5.00km · 주행 1회 · 15분").assertIsDisplayed()
        composeRule.onNodeWithText("3월 ▾").assertIsDisplayed()

        // [월 ▾] → 전체: the whole year again.
        composeRule.onNodeWithText("3월 ▾").performClick()
        composeRule.onNode(hasText("전체") and hasAnyAncestor(isPopup())).performClick()
        composeRule.onNodeWithText("25.00km · 주행 2회 · 1시간 15분").assertIsDisplayed()

        // [전체]: every ride.
        composeRule.onNodeWithTag(CumulativeMapTags.ALL).performClick()
        composeRule.onNodeWithText("35.00km · 주행 3회 · 1시간 45분").assertIsDisplayed()
        composeRule.onNodeWithText("연도 ▾").assertIsDisplayed()
        composeRule.onNodeWithTag(CumulativeMapTags.MONTH).assertIsNotEnabled()
    }

    @Test
    fun rideRowsNewestFirst() {
        show(stateFor(PeriodFilter(), october), october)

        composeRule.onNodeWithText("이 기간 주행 4회").assertIsDisplayed()
        composeRule.onNodeWithText("누르면 기록을 봅니다").assertIsDisplayed()
        // Newest first (the list scrolls; the screen may show only a few rows at a time).
        val rows = listOf(
            Triple(13L, "10월 27일 (화)", "3.00km · 10분"),
            Triple(11L, "10월 24일 (토)", "1.59km · 15분"),
            Triple(12L, "10월 20일 (화)", "5.00km · 20분"),
            Triple(14L, "9월 30일 (수)", "2.00km · 5분"),
        )
        val list = composeRule.onNodeWithTag(CumulativeMapTags.RIDES)
        rows.forEachIndexed { index, (id, date, detail) ->
            list.performScrollToIndex(index)
            val row = hasTestTag(CumulativeMapTags.ride(id))
            val dateTop = composeRule.onNode(hasText(date) and hasAnyAncestor(row), useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            val detailTop = composeRule.onNode(hasText(detail) and hasAnyAncestor(row), useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            assertTrue("$detail under $date", dateTop < detailTop)
            // Every row says it opens: "›", a button at least 48 dp high.
            composeRule.onNode(hasText("›") and hasAnyAncestor(row), useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNode(row)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertHeightIsAtLeast(48.dp)
            // The next (older) ride comes right under it.
            val next = rows.getOrNull(index + 1) ?: return@forEachIndexed
            val below = composeRule.onNodeWithTag(CumulativeMapTags.ride(next.first))
            if (below.isDisplayed()) {
                val top = composeRule.onNode(row).fetchSemanticsNode().boundsInRoot.top
                assertTrue("${next.second} under $date", top < below.fetchSemanticsNode().boundsInRoot.top)
            }
        }
        list.performScrollToIndex(0)
        val first = composeRule.onNodeWithTag(CumulativeMapTags.ride(13)).fetchSemanticsNode().boundsInRoot.top
        val second = composeRule.onNodeWithTag(CumulativeMapTags.ride(11)).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("newest first", first < second)
        composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).assertDoesNotExist()
        composeRule.onNodeWithText("기록 보기 ›").assertDoesNotExist()
    }

    @Test
    fun rowTapOpensRecordWithoutSelecting() {
        val opened = mutableListOf<Long>()
        show(stateFor(PeriodFilter(), october), october, onOpenRide = { opened += it })

        composeRule.onNodeWithTag(CumulativeMapTags.ride(11)).performClick()
        assertEquals(listOf(11L), opened)
        // Nothing is highlighted: no info card.
        composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).assertDoesNotExist()
        composeRule.onNodeWithText("기록 보기 ›").assertDoesNotExist()

        composeRule.onNodeWithTag(CumulativeMapTags.RIDES).performScrollToNode(hasTestTag(CumulativeMapTags.ride(12)))
        composeRule.onNodeWithTag(CumulativeMapTags.ride(12)).performClick()
        assertEquals(listOf(11L, 12L), opened)
        composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).assertDoesNotExist()
    }

    @Test
    fun aHighlightedRideHasAnInfoCardThatOpensIt() {
        val opened = mutableListOf<Long>()
        show(stateFor(PeriodFilter(), october, selected = 11), october, onOpenRide = { opened += it })

        composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("10월 24일 (토) · 1.59km · 15분").assertIsDisplayed()
        // The card is between the map and the list.
        val mapBottom = composeRule.onNodeWithTag(MAP).fetchSemanticsNode().boundsInRoot.bottom
        val card = composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).fetchSemanticsNode().boundsInRoot
        val listTop = composeRule.onNodeWithText("이 기간 주행 4회").fetchSemanticsNode().boundsInRoot.top
        assertTrue("card under the map", card.top >= mapBottom)
        assertTrue("card above the list", card.bottom <= listTop)
        // Its row stays in the list, in view.
        composeRule.onNodeWithTag(CumulativeMapTags.ride(11)).assertIsDisplayed()

        composeRule.onNodeWithText("기록 보기 ›").performClick()
        assertEquals(listOf(11L), opened)
    }

    @Test
    fun mapTapShowsTheCardAndTappingAgainHidesIt() {
        show(stateFor(PeriodFilter(), october), october, mapTapHits = 12)

        composeRule.onNodeWithTag(MAP).performClick()
        composeRule.onNodeWithText("10월 20일 (화) · 5.00km · 20분").assertIsDisplayed()
        composeRule.onAllNodesWithText("기록 보기 ›").assertCountEquals(1)

        // The same line again: no card.
        composeRule.onNodeWithTag(MAP).performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).assertDoesNotExist()

        // A period change lets go of it too.
        composeRule.onNodeWithTag(MAP).performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("이번 달").performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.RIDE_CARD).assertDoesNotExist()
    }

    @Test
    fun weekMenuListsTheWeeksOfTheMonth() {
        show(stateFor(PeriodFilter(), october), october)
        composeRule.onNodeWithTag(CumulativeMapTags.WEEK).assertIsNotEnabled()

        composeRule.onNodeWithText("연도 ▾").performClick()
        composeRule.onNodeWithText("2026년").performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.WEEK).assertIsNotEnabled()
        composeRule.onNodeWithText("월 ▾").performClick()
        composeRule.onNodeWithText("10월").performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.WEEK).assertIsEnabled()

        composeRule.onNodeWithText("주 ▾").performClick()
        composeRule.onNode(hasText("전체") and hasAnyAncestor(isPopup())).assertIsDisplayed()
        composeRule.onNodeWithText("10월 1주 (9/28~10/4)").assertIsDisplayed()
        composeRule.onNodeWithText("10월 4주 (10/19~10/25)").assertIsDisplayed()
        composeRule.onNodeWithText("10월 5주 (10/26~11/1)").performClick()

        composeRule.onNodeWithText("5주 ▾").assertIsDisplayed()
        composeRule.onNodeWithText("3.00km · 주행 1회 · 10분").assertIsDisplayed()
        composeRule.onNodeWithText("10월 27일 (화)").assertIsDisplayed()
        composeRule.onNodeWithText("10월 24일 (토)").assertDoesNotExist()

        // The first week of October has days of September.
        composeRule.onNodeWithText("5주 ▾").performClick()
        composeRule.onNodeWithText("10월 1주 (9/28~10/4)").performClick()
        composeRule.onNodeWithText("9월 30일 (수)").assertIsDisplayed()
    }

    @Test
    fun thisWeekAndThisMonth() {
        show(stateFor(PeriodFilter(), october), october)
        composeRule.onNodeWithTag(CumulativeMapTags.THIS_WEEK).assertIsNotSelected()
        composeRule.onNodeWithTag(CumulativeMapTags.THIS_MONTH).assertIsNotSelected()

        composeRule.onNodeWithText("이번 주").performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.THIS_WEEK).assertIsSelected()
        composeRule.onNodeWithTag(CumulativeMapTags.ALL).assertIsNotSelected()
        composeRule.onNodeWithText("3.00km · 주행 1회 · 10분").assertIsDisplayed()
        composeRule.onNodeWithText("5주 ▾").assertIsDisplayed()

        composeRule.onNodeWithText("이번 달").performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.THIS_MONTH).assertIsSelected()
        composeRule.onNodeWithTag(CumulativeMapTags.THIS_WEEK).assertIsNotSelected()
        composeRule.onNodeWithText("9.59km · 주행 3회 · 45분").assertIsDisplayed()
        composeRule.onNodeWithText("10월 ▾").assertIsDisplayed()
        composeRule.onNodeWithText("9월 30일 (수)").assertDoesNotExist()
    }

    @Test
    fun thisWeekWithoutRidesStillShowsItsNumber() {
        // Nothing ridden since Monday 10/26.
        val beforeThisWeek = october.filter { it.localDate < LocalDate.of(2026, 10, 26) }
        show(stateFor(PeriodFilter(), beforeThisWeek), beforeThisWeek)

        composeRule.onNodeWithText("이번 주").performClick()
        composeRule.onNodeWithTag(CumulativeMapTags.THIS_WEEK).assertIsSelected()
        composeRule.onNodeWithText("5주 ▾").assertIsDisplayed()
        composeRule.onNodeWithText(CUMULATIVE_PERIOD_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("이 기간에 기록이 없습니다").assertIsDisplayed()
        composeRule.onNodeWithText("주 ▾").assertDoesNotExist()
    }

    @Test
    fun periodWithoutRidesSaysSo() {
        show(stateFor(PeriodFilter(2026, 12), october), october)

        composeRule.onNodeWithText(CUMULATIVE_PERIOD_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("이 기간에 기록이 없습니다").assertIsDisplayed()
        composeRule.onNodeWithText(CUMULATIVE_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithText("0.00km · 주행 0회 · 0분").assertIsDisplayed()
        composeRule.onNodeWithTag(MAP).assertIsDisplayed()

        // [전체]: the rides again.
        composeRule.onNodeWithTag(CumulativeMapTags.ALL).performClick()
        composeRule.onNodeWithText(CUMULATIVE_PERIOD_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithText("10월 27일 (화)").assertIsDisplayed()
    }

    @Test
    fun noPeriodEmptyTextWhileAPassMayStillAddRoutes() {
        show(stateFor(PeriodFilter(2026, 12), october, settled = false), october)

        composeRule.onNodeWithText(CUMULATIVE_PERIOD_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(MAP).assertIsDisplayed()
    }

    @Test
    fun emptyText() {
        show(CumulativeMapState(loaded = true, settled = true))

        composeRule.onNodeWithText(CUMULATIVE_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다").assertIsDisplayed()
        composeRule.onNodeWithTag(MAP).assertDoesNotExist()
        composeRule.onNodeWithText("연도 ▾").assertDoesNotExist()
    }

    @Test
    fun noEmptyTextWhileAPassMayStillAddRoutes() {
        // Read, nothing yet, but the first pass after the upgrade is still simplifying the earlier rides.
        show(CumulativeMapState(loaded = true, settled = false))

        composeRule.onNodeWithText(CUMULATIVE_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithText("0.00km · 주행 0회 · 0분").assertDoesNotExist()
        composeRule.onNodeWithTag(MAP).assertIsDisplayed()
        composeRule.onNodeWithText("연도 ▾").assertIsDisplayed()
    }

    @Test
    fun nothingButTheMapWhileReading() {
        show(CumulativeMapState())

        composeRule.onNodeWithText(CUMULATIVE_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithText("0.00km · 주행 0회 · 0분").assertDoesNotExist()
        composeRule.onNodeWithTag(MAP).assertIsDisplayed()
    }

    private companion object {
        const val MAP = "test-map"
    }
}

package com.cowork.bikerecoder.ui.cumulative

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.CumulativeSummary
import com.cowork.bikerecoder.core.post.PeriodFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The cumulative map screen on fake state, its map replaced (spec §4): the summary, the period menus, the empty text. */
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

    /** What the view model would show for [period] (the same core rules). */
    private fun stateFor(period: PeriodFilter): CumulativeMapState {
        val entries = CumulativeSummary.filter(all, period)
        return CumulativeMapState(
            loaded = true,
            period = period,
            entries = entries,
            lines = entries.map { it.points },
            totals = CumulativeSummary.totals(entries),
            years = CumulativeSummary.years(all),
            months = period.year?.let { CumulativeSummary.months(all, it) }.orEmpty(),
        )
    }

    private fun show(initial: CumulativeMapState, onBack: () -> Unit = {}) {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            MaterialTheme {
                CumulativeMapContent(
                    state = state,
                    onShowAll = { state = stateFor(PeriodFilter()) },
                    onSelectYear = { state = stateFor(PeriodFilter(it)) },
                    onSelectMonth = { month -> state = stateFor(PeriodFilter(state.period.year, month)) },
                    onBack = onBack,
                    map = { modifier -> Box(modifier.testTag(MAP)) },
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
    fun emptyText() {
        show(CumulativeMapState(loaded = true))

        composeRule.onNodeWithText(CUMULATIVE_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다").assertIsDisplayed()
        composeRule.onNodeWithTag(MAP).assertDoesNotExist()
        composeRule.onNodeWithText("연도 ▾").assertDoesNotExist()
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

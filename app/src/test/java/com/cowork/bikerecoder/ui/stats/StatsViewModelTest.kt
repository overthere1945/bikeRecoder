package com.cowork.bikerecoder.ui.stats

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.stats.GoalInput
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeRides(initial: List<RideStat> = emptyList()) : RideStatsSource {
        val flow = MutableStateFlow(initial)
        override fun observeRides(): Flow<List<RideStat>> = flow
    }

    private class FakeGoals(weekly: Int? = null, yearly: Int? = null) : GoalSettings {
        val flow = MutableStateFlow(GoalKm(weekly, yearly))
        val saved = mutableListOf<GoalKm>()
        override val goals: Flow<GoalKm> = flow
        override suspend fun setGoals(weeklyKm: Int?, yearlyKm: Int?) {
            saved += GoalKm(weeklyKm, yearlyKm)
            flow.value = GoalKm(weeklyKm, yearlyKm)
        }
    }

    private var today = LocalDate.of(2026, 10, 31) // a Saturday

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun ride(id: Long, date: LocalDate, km: Double, minutes: Long = 60, ascent: Double = 0.0) = RideStat(
        segmentId = id,
        tripId = id,
        startedAt = date.atTime(8, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() + id,
        localDate = date,
        distanceM = km * 1000.0,
        movingTimeMs = minutes * 60_000,
        ascentM = ascent,
    )

    private val sample = listOf(
        ride(1, LocalDate.of(2026, 9, 14), 30.0, 120, 100.0),
        ride(2, LocalDate.of(2026, 10, 3), 10.0, 30),
        ride(3, LocalDate.of(2026, 10, 25), 20.0, 60, 50.0),
    )

    private fun TestScope.viewModel(
        rides: FakeRides = FakeRides(sample),
        goals: FakeGoals = FakeGoals(),
    ): StatsViewModel {
        val vm = StatsViewModel(rides, goals, { today }, WarnLog { _, _ -> })
        advanceUntilIdle()
        return vm
    }

    @Test
    fun startsOnThisMonthDistance() = runTest(dispatcher) {
        val state = viewModel().state.value

        assertEquals(PeriodKind.MONTH, state.period.kind)
        assertEquals(LocalDate.of(2026, 10, 1), state.period.start)
        assertEquals("2026년 10월", state.title)
        assertEquals(StatMetric.DISTANCE, state.metric)
        assertNull(state.selectedBar)
        assertEquals(30_000.0, state.totals.distanceM, 1e-9)
        assertEquals(2, state.totals.rides)
        assertEquals(2, state.cumulativeRides)
        // Five weeks touch October 2026: 9/28, 10/5, 10/12, 10/19, 10/26.
        assertEquals(listOf("1주", "2주", "3주", "4주", "5주"), state.bars.map { it.label })
        assertEquals(listOf(10_000.0, 0.0, 0.0, 20_000.0, 0.0), state.bars.map { it.value })
        assertTrue(state.bars.none { it.upcoming })
        assertEquals("10월 4주 · 20.0 km", state.bars[3].valueText)
        assertTrue(state.canPrevious)
        assertFalse(state.canNext)
        // October up to the 31st (30 km) against September up to the 30th (30 km).
        assertEquals("지난달 이맘때보다 +0.0 km (+0%)", state.comparisonText)
    }

    @Test
    fun noRidesShowsThisPeriodOnly() = runTest(dispatcher) {
        val state = viewModel(FakeRides()).state.value

        assertEquals(0.0, state.totals.distanceM, 1e-9)
        assertFalse(state.canPrevious)
        assertFalse(state.canNext)
        assertNull(state.comparisonText)
        assertEquals(emptyList<RecordRow>(), state.records)
        assertEquals("이번 주에 타면 연속 주행이 시작됩니다", state.streakText)
    }

    @Test
    fun previousNextStopAtBounds() = runTest(dispatcher) {
        val vm = viewModel()

        vm.previous() // September: the month of the first ride
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value.period.start)
        assertEquals("2026년 9월", vm.state.value.title)
        assertFalse(vm.state.value.canPrevious)
        assertTrue(vm.state.value.canNext)
        assertEquals("8월보다 +30.0 km", vm.state.value.comparisonText)

        vm.previous() // no further
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value.period.start)

        vm.next()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 1), vm.state.value.period.start)
        assertFalse(vm.state.value.canNext)

        vm.next() // no further than this month
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 1), vm.state.value.period.start)
    }

    @Test
    fun allHasNoArrowsAndNoComparison() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectKind(PeriodKind.ALL)
        advanceUntilIdle()
        val state = vm.state.value

        assertEquals("전체", state.title)
        assertFalse(state.canPrevious)
        assertFalse(state.canNext)
        assertNull(state.comparisonText)
        assertEquals(60_000.0, state.totals.distanceM, 1e-9)
        assertEquals(listOf("2026"), state.bars.map { it.label })
        vm.previous()
        vm.next()
        advanceUntilIdle()
        assertEquals(PeriodKind.ALL, vm.state.value.period.kind)
    }

    @Test
    fun selectKindStartsOnTheCurrentPeriod() = runTest(dispatcher) {
        val vm = viewModel()
        vm.previous()
        advanceUntilIdle()

        vm.selectKind(PeriodKind.WEEK)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 26), vm.state.value.period.start)
        assertEquals("10월 5주 (10/26~11/1)", vm.state.value.title)
        assertEquals(listOf("월", "화", "수", "목", "금", "토", "일"), vm.state.value.bars.map { it.label })
        // Sunday 11/1 has not come yet.
        assertEquals(listOf(false, false, false, false, false, false, true), vm.state.value.bars.map { it.upcoming })

        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 1, 1), vm.state.value.period.start)
        assertEquals(12, vm.state.value.bars.size)
        // November and December have not come yet.
        assertEquals(listOf(false, false, true, true), vm.state.value.bars.drop(8).map { it.upcoming })
    }

    @Test
    fun showJumpsToPeriod() = runTest(dispatcher) {
        val vm = viewModel()

        vm.show(PeriodKind.WEEK, LocalDate.of(2026, 9, 16)) // a Wednesday
        advanceUntilIdle()
        assertEquals(PeriodKind.WEEK, vm.state.value.period.kind)
        assertEquals(LocalDate.of(2026, 9, 14), vm.state.value.period.start)
        assertEquals("9월 3주 (9/14~9/20)", vm.state.value.title)
        assertEquals(30_000.0, vm.state.value.totals.distanceM, 1e-9)
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 9, 14)), vm.state.value.cumulativeFilter)

        vm.show(PeriodKind.MONTH, LocalDate.of(2026, 9, 1))
        advanceUntilIdle()
        assertEquals("2026년 9월", vm.state.value.title)

        // Before the first ride or after today: the nearest period one can page to.
        vm.show(PeriodKind.MONTH, LocalDate.of(2020, 1, 1))
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value.period.start)
        vm.show(PeriodKind.MONTH, LocalDate.of(2030, 1, 1))
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 1), vm.state.value.period.start)
        assertFalse(vm.state.value.canNext)
    }

    @Test
    fun showThisWeekJumpsToTodaysWeek() = runTest(dispatcher) {
        // The summary tab's week strip (V1.4.0 spec §1.1): this Monday-to-Sunday week, by the date read now.
        val vm = viewModel()
        vm.show(PeriodKind.MONTH, LocalDate.of(2026, 9, 1))
        today = LocalDate.of(2026, 11, 2) // the process lived into a Monday of a new month

        vm.showThisWeek()
        advanceUntilIdle()

        assertEquals(PeriodKind.WEEK, vm.state.value.period.kind)
        assertEquals(LocalDate.of(2026, 11, 2), vm.state.value.period.start)
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 11, 2)), vm.state.value.cumulativeFilter)
    }

    @Test
    fun selectBarTogglesAndClearsOnPeriodChange() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectBar(3)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.selectedBar)

        vm.selectBar(1)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selectedBar)

        vm.selectBar(1) // again: gone
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)

        vm.selectBar(3)
        vm.previous()
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)

        vm.selectBar(0)
        vm.next()
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)

        vm.selectBar(0)
        vm.selectMetric(StatMetric.TIME)
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)
        assertEquals(StatMetric.TIME, vm.state.value.metric)

        vm.selectBar(0)
        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)

        vm.selectBar(0)
        vm.show(PeriodKind.MONTH, LocalDate.of(2026, 9, 1))
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)
    }

    @Test
    fun barsFollowTheMetricAndOutOfRangeOrUpcomingBarsAreIgnored() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectMetric(StatMetric.TIME)
        advanceUntilIdle()
        assertEquals(listOf(1_800_000.0, 0.0, 0.0, 3_600_000.0, 0.0), vm.state.value.bars.map { it.value })
        vm.selectBar(3)
        advanceUntilIdle()
        assertEquals("10월 4주 · 1시간 0분", vm.state.value.bars[3].valueText)

        vm.selectMetric(StatMetric.ASCENT)
        advanceUntilIdle()
        assertEquals(50.0, vm.state.value.bars[3].value, 1e-9)
        assertEquals("10월 4주 · 50m", vm.state.value.bars[3].valueText)

        vm.selectBar(99)
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)

        today = LocalDate.of(2026, 10, 12) // October 12th: the later weeks are still to come
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(false, false, false, true, true), vm.state.value.bars.map { it.upcoming })
        vm.selectBar(4)
        advanceUntilIdle()
        assertNull(vm.state.value.selectedBar)
    }

    @Test
    fun recordsHiddenUnderTwoRides() = runTest(dispatcher) {
        val rides = FakeRides(listOf(sample[0]))
        val vm = viewModel(rides)
        assertEquals(emptyList<RecordRow>(), vm.state.value.records)

        rides.flow.value = sample
        advanceUntilIdle()
        assertEquals(7, vm.state.value.records.size)
        assertEquals("가장 긴 주행", vm.state.value.records.first().label)
    }

    @Test
    fun saveGoalsWritesValidAndClearsEmpty() = runTest(dispatcher) {
        val goals = FakeGoals(weekly = 50, yearly = 1000)
        val vm = viewModel(goals = goals)
        assertEquals(50, vm.state.value.weeklyGoal?.goalKm)

        vm.saveGoals(GoalInput.Valid(100), GoalInput.Empty)
        advanceUntilIdle()

        assertEquals(listOf(GoalKm(100, null)), goals.saved)
        assertEquals(100, vm.state.value.weeklyGoal?.goalKm)
        assertNull(vm.state.value.yearlyGoal)

        vm.saveGoals(GoalInput.Empty, GoalInput.Valid(2000))
        advanceUntilIdle()
        assertEquals(GoalKm(null, 2000), goals.saved.last())
        assertNull(vm.state.value.weeklyGoal)
        assertEquals(2000, vm.state.value.yearlyGoal?.goalKm)
    }

    @Test
    fun invalidGoalNotSaved() = runTest(dispatcher) {
        val goals = FakeGoals(weekly = 50)
        val vm = viewModel(goals = goals)

        vm.saveGoals(GoalInput.Invalid, GoalInput.Valid(1000))
        vm.saveGoals(GoalInput.Valid(100), GoalInput.Invalid)
        advanceUntilIdle()

        assertEquals(emptyList<GoalKm>(), goals.saved)
        assertEquals(50, vm.state.value.weeklyGoal?.goalKm)
    }

    @Test
    fun goalsMeasureThisWeekAndThisYear() = runTest(dispatcher) {
        val vm = viewModel(goals = FakeGoals(weekly = 20, yearly = 1000))
        val state = vm.state.value

        // The week of Monday 10/26 has no ride; the 25th (a Sunday) was the week before.
        assertEquals(0.0, state.weeklyGoal!!.distanceM, 1e-9)
        assertFalse(state.weeklyGoal.reached)
        assertEquals(60_000.0, state.yearlyGoal!!.distanceM, 1e-9)
        // 60 km in 304 days of 365: 72 km, to 10 km: 70.
        assertEquals("지금 속도면 연말에 약 70 km", state.projectionText)

        today = LocalDate.of(2026, 10, 25)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(20_000.0, vm.state.value.weeklyGoal!!.distanceM, 1e-9)
        assertTrue(vm.state.value.weeklyGoal!!.reached)
        assertEquals(1.0f, vm.state.value.weeklyGoal!!.fraction, 0f)
    }

    @Test
    fun noYearlyGoalNoProjection() = runTest(dispatcher) {
        assertNull(viewModel(goals = FakeGoals(weekly = 20)).state.value.projectionText)
    }

    @Test
    fun streakTextFollowsTheRides() = runTest(dispatcher) {
        val vm = viewModel()
        // The week of 10/19 had a ride; this week (10/26) none yet: 1 week so far.
        assertEquals("1주 연속 · 이번 주에 타면 2주", vm.state.value.streakText)

        today = LocalDate.of(2026, 10, 25)
        vm.refresh()
        advanceUntilIdle()
        assertEquals("1주 연속 주행 중", vm.state.value.streakText)
    }

    @Test
    fun cumulativeFilterMatchesPeriod() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectKind(PeriodKind.WEEK)
        advanceUntilIdle()
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 10, 26)), vm.state.value.cumulativeFilter)

        vm.selectKind(PeriodKind.MONTH)
        advanceUntilIdle()
        assertEquals(PeriodFilter(year = 2026, month = 10), vm.state.value.cumulativeFilter)
        assertEquals(2, vm.state.value.cumulativeRides)

        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        assertEquals(PeriodFilter(year = 2026), vm.state.value.cumulativeFilter)
        assertEquals(3, vm.state.value.cumulativeRides)

        vm.selectKind(PeriodKind.ALL)
        advanceUntilIdle()
        assertEquals(PeriodFilter(), vm.state.value.cumulativeFilter)
        assertEquals(3, vm.state.value.cumulativeRides)
    }

    @Test
    fun rideUpdatesAreFollowed() = runTest(dispatcher) {
        val rides = FakeRides(sample)
        val vm = viewModel(rides)

        rides.flow.value = sample + ride(4, LocalDate.of(2026, 10, 28), 5.0)
        advanceUntilIdle()

        assertEquals(35_000.0, vm.state.value.totals.distanceM, 1e-9)
        assertEquals(3, vm.state.value.totals.rides)
    }

    @Test
    fun sitsOnThePeriodOfTodayAfterTheDayChanges() = runTest(dispatcher) {
        val vm = viewModel()

        today = LocalDate.of(2026, 11, 2)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 11, 1), vm.state.value.period.start)
        assertEquals("2026년 11월", vm.state.value.title)
    }
}

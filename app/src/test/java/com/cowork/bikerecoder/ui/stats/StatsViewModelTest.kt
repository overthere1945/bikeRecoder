package com.cowork.bikerecoder.ui.stats

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.BestEffortDistance
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.stats.GoalInput
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import com.cowork.bikerecoder.core.stats.StoredBestEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeRides(initial: List<RideStat> = emptyList()) : RideStatsSource {
        val flow = MutableStateFlow(initial)
        val efforts = MutableStateFlow<List<StoredBestEffort>>(emptyList())
        override fun observeRides(): Flow<List<RideStat>> = flow
        override fun observeBestEfforts(): Flow<List<StoredBestEffort>> = efforts
    }

    private class FakeGoals(weekly: Int? = null, yearly: Int? = null) : GoalSettings {
        val flow = MutableStateFlow<Map<ActivityType, GoalKm>>(
            if (weekly == null && yearly == null) emptyMap() else mapOf(ActivityType.BIKE to GoalKm(weekly, yearly)),
        )
        val saved = mutableListOf<GoalKm>()
        val savedFor = mutableListOf<ActivityType>()
        override val goals: Flow<Map<ActivityType, GoalKm>> = flow
        override suspend fun setGoals(activity: ActivityType, weeklyKm: Int?, yearlyKm: Int?) {
            saved += GoalKm(weeklyKm, yearlyKm)
            savedFor += activity
            val goal = GoalKm(weeklyKm, yearlyKm)
            flow.value = if (weeklyKm == null && yearlyKm == null) flow.value - activity else flow.value + (activity to goal)
        }
    }

    private class FakeChip(initial: ActivityType? = null) : StatsChipSettings {
        val flow = MutableStateFlow(initial)
        override val activity: Flow<ActivityType?> = flow
        override suspend fun setActivity(type: ActivityType?) {
            flow.value = type
        }
    }

    private var today = LocalDate.of(2026, 10, 31) // a Saturday

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun ride(
        id: Long,
        date: LocalDate,
        km: Double,
        minutes: Long = 60,
        ascent: Double = 0.0,
        activity: ActivityType = ActivityType.BIKE,
        elapsedMinutes: Long = 0,
        maxAltitudeM: Double? = null,
    ) = RideStat(
        segmentId = id,
        tripId = id,
        startedAt = date.atTime(8, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() + id,
        localDate = date,
        distanceM = km * 1000.0,
        movingTimeMs = minutes * 60_000,
        ascentM = ascent,
        activity = activity,
        elapsedMs = elapsedMinutes * 60_000,
        maxAltitudeM = maxAltitudeM,
    )

    private val sample = listOf(
        ride(1, LocalDate.of(2026, 9, 14), 30.0, 120, 100.0),
        ride(2, LocalDate.of(2026, 10, 3), 10.0, 30),
        ride(3, LocalDate.of(2026, 10, 25), 20.0, 60, 50.0),
    )

    private fun TestScope.viewModel(
        rides: FakeRides = FakeRides(sample),
        goals: FakeGoals = FakeGoals(),
        // The tests of V1.4.0 are about bike rides: the bike chip shows what they always showed ([전체] is `null`).
        chip: FakeChip = FakeChip(ActivityType.BIKE),
    ): StatsViewModel {
        val vm = StatsViewModel(rides, goals, { today }, WarnLog { _, _ -> }, dispatcher, chip)
        advanceUntilIdle()
        return vm
    }

    /** Review M2: nothing (no zeros, no "no goal") until both the rides and the goals have been read. */
    @Test
    fun noStateUntilRidesAndGoalsAreRead() = runTest(dispatcher) {
        val rides = MutableSharedFlow<List<RideStat>>()
        val goals = MutableSharedFlow<Map<ActivityType, GoalKm>>()
        val vm = StatsViewModel(
            object : RideStatsSource {
                override fun observeRides(): Flow<List<RideStat>> = rides
                override fun observeBestEfforts(): Flow<List<StoredBestEffort>> = flowOf(emptyList())
            },
            object : GoalSettings {
                override val goals: Flow<Map<ActivityType, GoalKm>> = goals
                override suspend fun setGoals(activity: ActivityType, weeklyKm: Int?, yearlyKm: Int?) = Unit
            },
            { today },
            WarnLog { _, _ -> },
            dispatcher,
            FakeChip(ActivityType.BIKE),
        )
        advanceUntilIdle()
        assertNull(vm.state.value)

        rides.emit(sample)
        advanceUntilIdle()
        assertNull(vm.state.value, "the goals are not read yet")
        // Nothing to page or tap yet.
        vm.previous()
        vm.selectBar(0)

        goals.emit(mapOf(ActivityType.BIKE to GoalKm(100, null)))
        advanceUntilIdle()
        assertEquals(100, vm.state.value!!.weeklyGoal?.goalKm)
        assertEquals("2026년 10월", vm.state.value!!.title)
        assertNull(vm.state.value!!.selectedBar)
    }

    /** Review M4: the rides are followed and the state built on the compute dispatcher, not on Main. */
    @Test
    fun theStateIsBuiltOnTheComputeDispatcher() = runTest(dispatcher) {
        val compute = StandardTestDispatcher(testScheduler, name = "compute")
        var followedOn: ContinuationInterceptor? = null
        val rides = object : RideStatsSource {
            override fun observeRides(): Flow<List<RideStat>> = flow {
                followedOn = currentCoroutineContext()[ContinuationInterceptor]
                emit(sample)
                awaitCancellation()
            }
            override fun observeBestEfforts(): Flow<List<StoredBestEffort>> = flowOf(emptyList())
        }
        val vm = StatsViewModel(rides, FakeGoals(), { today }, WarnLog { _, _ -> }, compute, FakeChip(ActivityType.BIKE))
        advanceUntilIdle()

        assertEquals(compute, followedOn)
        assertEquals("2026년 10월", vm.state.value!!.title)
    }

    @Test
    fun startsOnThisMonthDistance() = runTest(dispatcher) {
        val state = viewModel().state.value!!

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
        val state = viewModel(FakeRides()).state.value!!

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
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value!!.period.start)
        assertEquals("2026년 9월", vm.state.value!!.title)
        assertFalse(vm.state.value!!.canPrevious)
        assertTrue(vm.state.value!!.canNext)
        assertEquals("8월보다 +30.0 km", vm.state.value!!.comparisonText)

        vm.previous() // no further
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value!!.period.start)

        vm.next()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 1), vm.state.value!!.period.start)
        assertFalse(vm.state.value!!.canNext)

        vm.next() // no further than this month
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 1), vm.state.value!!.period.start)
    }

    @Test
    fun allHasNoArrowsAndNoComparison() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectKind(PeriodKind.ALL)
        advanceUntilIdle()
        val state = vm.state.value!!

        assertEquals("전체", state.title)
        assertFalse(state.canPrevious)
        assertFalse(state.canNext)
        assertNull(state.comparisonText)
        assertEquals(60_000.0, state.totals.distanceM, 1e-9)
        assertEquals(listOf("’26.9", "10월"), state.bars.map { it.label })
        vm.previous()
        vm.next()
        advanceUntilIdle()
        assertEquals(PeriodKind.ALL, vm.state.value!!.period.kind)
    }

    @Test
    fun allWithinAYearHasMonthBars() = runTest(dispatcher) {
        val vm = viewModel(FakeRides(listOf(ride(1, LocalDate.of(2025, 11, 3), 24.1), ride(2, LocalDate.of(2026, 10, 25), 12.0))))

        vm.selectKind(PeriodKind.ALL)
        advanceUntilIdle()
        val bars = vm.state.value!!.bars

        assertEquals(
            listOf("’25.11", "12월", "’26.1", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월"),
            bars.map { it.label },
        )
        assertEquals("2025년 11월 · 24.1 km", bars.first().valueText)
        assertEquals("24.1", bars.first().shortText)
        assertEquals("", bars[1].shortText)
        assertEquals("12.0", bars.last().shortText)
        assertEquals("km", vm.state.value!!.unitText)
    }

    @Test
    fun barShortTextsAndUnitFollowTheMetric() = runTest(dispatcher) {
        val vm = viewModel(FakeRides(listOf(ride(1, LocalDate.of(2026, 10, 25), 3.5, minutes = 19, ascent = 120.0))))

        assertEquals(listOf("", "", "", "3.5", ""), vm.state.value!!.bars.map { it.shortText })
        assertEquals("km", vm.state.value!!.unitText)

        vm.selectMetric(StatMetric.TIME)
        advanceUntilIdle()
        assertEquals("0:19", vm.state.value!!.bars[3].shortText)
        assertEquals("시간", vm.state.value!!.unitText)

        vm.selectMetric(StatMetric.ASCENT)
        advanceUntilIdle()
        assertEquals("120", vm.state.value!!.bars[3].shortText)
        assertEquals("m", vm.state.value!!.unitText)
    }

    @Test
    fun selectKindStartsOnTheCurrentPeriod() = runTest(dispatcher) {
        val vm = viewModel()
        vm.previous()
        advanceUntilIdle()

        vm.selectKind(PeriodKind.WEEK)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 26), vm.state.value!!.period.start)
        assertEquals("10월 5주 (10/26~11/1)", vm.state.value!!.title)
        assertEquals(listOf("월", "화", "수", "목", "금", "토", "일"), vm.state.value!!.bars.map { it.label })
        // Sunday 11/1 has not come yet.
        assertEquals(listOf(false, false, false, false, false, false, true), vm.state.value!!.bars.map { it.upcoming })

        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 1, 1), vm.state.value!!.period.start)
        assertEquals(12, vm.state.value!!.bars.size)
        // November and December have not come yet.
        assertEquals(listOf(false, false, true, true), vm.state.value!!.bars.drop(8).map { it.upcoming })
    }

    @Test
    fun showJumpsToPeriod() = runTest(dispatcher) {
        val vm = viewModel()

        vm.show(PeriodKind.WEEK, LocalDate.of(2026, 9, 16)) // a Wednesday
        advanceUntilIdle()
        assertEquals(PeriodKind.WEEK, vm.state.value!!.period.kind)
        assertEquals(LocalDate.of(2026, 9, 14), vm.state.value!!.period.start)
        assertEquals("9월 3주 (9/14~9/20)", vm.state.value!!.title)
        assertEquals(30_000.0, vm.state.value!!.totals.distanceM, 1e-9)
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 9, 14)), vm.state.value!!.cumulativeFilter)

        vm.show(PeriodKind.MONTH, LocalDate.of(2026, 9, 1))
        advanceUntilIdle()
        assertEquals("2026년 9월", vm.state.value!!.title)

        // Before the first ride or after today: the nearest period one can page to.
        vm.show(PeriodKind.MONTH, LocalDate.of(2020, 1, 1))
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value!!.period.start)
        vm.show(PeriodKind.MONTH, LocalDate.of(2030, 1, 1))
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 1), vm.state.value!!.period.start)
        assertFalse(vm.state.value!!.canNext)
    }

    @Test
    fun showThisWeekJumpsToTodaysWeek() = runTest(dispatcher) {
        // The summary tab's week strip (V1.4.0 spec §1.1): this Monday-to-Sunday week, by the date read now.
        val vm = viewModel()
        vm.show(PeriodKind.MONTH, LocalDate.of(2026, 9, 1))
        today = LocalDate.of(2026, 11, 2) // the process lived into a Monday of a new month

        vm.showThisWeek()
        advanceUntilIdle()

        assertEquals(PeriodKind.WEEK, vm.state.value!!.period.kind)
        assertEquals(LocalDate.of(2026, 11, 2), vm.state.value!!.period.start)
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 11, 2)), vm.state.value!!.cumulativeFilter)
    }

    /** Review M5: "this week" from the strip follows the date: a resume in the next week shows that week. */
    @Test
    fun showThisWeekFollowsTheDate() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showThisWeek()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 26), vm.state.value!!.period.start)

        today = LocalDate.of(2026, 11, 3)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(PeriodKind.WEEK, vm.state.value!!.period.kind)
        assertEquals(LocalDate.of(2026, 11, 2), vm.state.value!!.period.start)
    }

    @Test
    fun selectBarTogglesAndClearsOnPeriodChange() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectBar(3)
        advanceUntilIdle()
        assertEquals(3, vm.state.value!!.selectedBar)

        vm.selectBar(1)
        advanceUntilIdle()
        assertEquals(1, vm.state.value!!.selectedBar)

        vm.selectBar(1) // again: gone
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)

        vm.selectBar(3)
        vm.previous()
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)

        vm.selectBar(0)
        vm.next()
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)

        vm.selectBar(0)
        vm.selectMetric(StatMetric.TIME)
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)
        assertEquals(StatMetric.TIME, vm.state.value!!.metric)

        vm.selectBar(0)
        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)

        vm.selectBar(0)
        vm.show(PeriodKind.MONTH, LocalDate.of(2026, 9, 1))
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)
    }

    @Test
    fun barsFollowTheMetricAndOutOfRangeOrUpcomingBarsAreIgnored() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectMetric(StatMetric.TIME)
        advanceUntilIdle()
        assertEquals(listOf(1_800_000.0, 0.0, 0.0, 3_600_000.0, 0.0), vm.state.value!!.bars.map { it.value })
        vm.selectBar(3)
        advanceUntilIdle()
        assertEquals("10월 4주 · 1시간 0분", vm.state.value!!.bars[3].valueText)

        vm.selectMetric(StatMetric.ASCENT)
        advanceUntilIdle()
        assertEquals(50.0, vm.state.value!!.bars[3].value, 1e-9)
        assertEquals("10월 4주 · 50 m", vm.state.value!!.bars[3].valueText)

        vm.selectBar(99)
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)

        today = LocalDate.of(2026, 10, 12) // October 12th: the later weeks are still to come
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(false, false, false, true, true), vm.state.value!!.bars.map { it.upcoming })
        vm.selectBar(4)
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)
    }

    @Test
    fun recordsHiddenUnderTwoRides() = runTest(dispatcher) {
        val rides = FakeRides(listOf(sample[0]))
        val vm = viewModel(rides)
        assertEquals(emptyList<RecordRow>(), vm.state.value!!.records)

        rides.flow.value = sample
        advanceUntilIdle()
        assertEquals(7, vm.state.value!!.records.size)
        assertEquals("가장 긴 주행", vm.state.value!!.records.first().label)
    }

    @Test
    fun saveGoalsWritesValidAndClearsEmpty() = runTest(dispatcher) {
        val goals = FakeGoals(weekly = 50, yearly = 1000)
        val vm = viewModel(goals = goals)
        assertEquals(50, vm.state.value!!.weeklyGoal?.goalKm)

        vm.saveGoals(GoalInput.Valid(100), GoalInput.Empty)
        advanceUntilIdle()

        assertEquals(listOf(GoalKm(100, null)), goals.saved)
        assertEquals(100, vm.state.value!!.weeklyGoal?.goalKm)
        assertNull(vm.state.value!!.yearlyGoal)

        vm.saveGoals(GoalInput.Empty, GoalInput.Valid(2000))
        advanceUntilIdle()
        assertEquals(GoalKm(null, 2000), goals.saved.last())
        assertNull(vm.state.value!!.weeklyGoal)
        assertEquals(2000, vm.state.value!!.yearlyGoal?.goalKm)
    }

    @Test
    fun invalidGoalNotSaved() = runTest(dispatcher) {
        val goals = FakeGoals(weekly = 50)
        val vm = viewModel(goals = goals)

        vm.saveGoals(GoalInput.Invalid, GoalInput.Valid(1000))
        vm.saveGoals(GoalInput.Valid(100), GoalInput.Invalid)
        advanceUntilIdle()

        assertEquals(emptyList<GoalKm>(), goals.saved)
        assertEquals(50, vm.state.value!!.weeklyGoal?.goalKm)
    }

    @Test
    fun goalsMeasureThisWeekAndThisYear() = runTest(dispatcher) {
        val vm = viewModel(goals = FakeGoals(weekly = 20, yearly = 1000))
        val state = vm.state.value!!

        // The week of Monday 10/26 has no ride; the 25th (a Sunday) was the week before.
        assertEquals(0.0, state.weeklyGoal!!.distanceM, 1e-9)
        assertFalse(state.weeklyGoal.reached)
        assertEquals(60_000.0, state.yearlyGoal!!.distanceM, 1e-9)
        // 60 km in 304 days of 365: 72 km, to 10 km: 70.
        assertEquals("지금 속도면 연말에 약 70 km", state.projectionText)

        today = LocalDate.of(2026, 10, 25)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(20_000.0, vm.state.value!!.weeklyGoal!!.distanceM, 1e-9)
        assertTrue(vm.state.value!!.weeklyGoal!!.reached)
        assertEquals(1.0f, vm.state.value!!.weeklyGoal!!.fraction, 0f)
    }

    @Test
    fun noYearlyGoalNoProjection() = runTest(dispatcher) {
        assertNull(viewModel(goals = FakeGoals(weekly = 20)).state.value!!.projectionText)
    }

    @Test
    fun streakTextFollowsTheRides() = runTest(dispatcher) {
        val vm = viewModel()
        // The week of 10/19 had a ride; this week (10/26) none yet: 1 week so far.
        assertEquals("1주 연속 · 이번 주에 타면 2주", vm.state.value!!.streakText)

        today = LocalDate.of(2026, 10, 25)
        vm.refresh()
        advanceUntilIdle()
        assertEquals("1주 연속 주행 중", vm.state.value!!.streakText)
    }

    @Test
    fun cumulativeFilterMatchesPeriod() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectKind(PeriodKind.WEEK)
        advanceUntilIdle()
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 10, 26)), vm.state.value!!.cumulativeFilter)

        vm.selectKind(PeriodKind.MONTH)
        advanceUntilIdle()
        assertEquals(PeriodFilter(year = 2026, month = 10), vm.state.value!!.cumulativeFilter)
        assertEquals(2, vm.state.value!!.cumulativeRides)

        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        assertEquals(PeriodFilter(year = 2026), vm.state.value!!.cumulativeFilter)
        assertEquals(3, vm.state.value!!.cumulativeRides)

        vm.selectKind(PeriodKind.ALL)
        advanceUntilIdle()
        assertEquals(PeriodFilter(), vm.state.value!!.cumulativeFilter)
        assertEquals(3, vm.state.value!!.cumulativeRides)
    }

    @Test
    fun rideUpdatesAreFollowed() = runTest(dispatcher) {
        val rides = FakeRides(sample)
        val vm = viewModel(rides)

        rides.flow.value = sample + ride(4, LocalDate.of(2026, 10, 28), 5.0)
        advanceUntilIdle()

        assertEquals(35_000.0, vm.state.value!!.totals.distanceM, 1e-9)
        assertEquals(3, vm.state.value!!.totals.rides)
    }

    @Test
    fun sitsOnThePeriodOfTodayAfterTheDayChanges() = runTest(dispatcher) {
        val vm = viewModel()

        today = LocalDate.of(2026, 11, 2)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 11, 1), vm.state.value!!.period.start)
        assertEquals("2026년 11월", vm.state.value!!.title)
    }

    @Test
    fun cumulativeOfTheMonthRunsToTodayAgainstTheMonthBefore() = runTest(dispatcher) {
        val state = viewModel().state.value!!
        val cumulative = state.cumulative!!

        assertEquals(31, cumulative.current.size) // October 1~31
        assertEquals(0.0, cumulative.current[1], 1e-9)
        assertEquals(10.0, cumulative.current[2], 1e-9) // 10/3
        assertEquals(30.0, cumulative.current.last(), 1e-9) // 10/25 on top
        assertEquals(30, cumulative.previous.size) // September
        assertEquals(30.0, cumulative.previous.last(), 1e-9)
        assertEquals(31, cumulative.length)
        assertNull(cumulative.goal) // the month has no goal line
        assertEquals("30.0", cumulative.endText)
        assertEquals("누적 이번 달 30.0 km, 지난달 30.0 km", cumulative.description)
        assertEquals(31, cumulative.currentDays)
        assertEquals("이번 달", cumulative.currentName)
        assertEquals("지난달", cumulative.previousName)
    }

    @Test
    fun cumulativeOfAPastMonthIsNamedByMonth() = runTest(dispatcher) {
        val vm = viewModel()

        vm.previous()
        advanceUntilIdle()
        val cumulative = vm.state.value!!.cumulative!!

        assertEquals(30, cumulative.current.size) // all of September
        assertEquals(30.0, cumulative.current.last(), 1e-9)
        assertEquals(31, cumulative.previous.size) // August
        assertEquals("9월", cumulative.currentName)
        assertEquals("8월", cumulative.previousName)
        assertEquals("누적 9월 30.0 km, 8월 0.0 km", cumulative.description)
    }

    @Test
    fun cumulativeOfTheWeekHasTheWeeklyGoalPace() = runTest(dispatcher) {
        val rides = FakeRides(sample + ride(4, LocalDate.of(2026, 10, 26), 5.0) + ride(5, LocalDate.of(2026, 10, 29), 7.5))
        val vm = viewModel(rides, FakeGoals(weekly = 100, yearly = 3000))

        vm.selectKind(PeriodKind.WEEK)
        advanceUntilIdle()
        val cumulative = vm.state.value!!.cumulative!!

        assertEquals(listOf(5.0, 5.0, 5.0, 12.5, 12.5, 12.5), cumulative.current) // Monday to Saturday
        assertEquals(7, cumulative.previous.size)
        assertEquals(20.0, cumulative.previous.last(), 1e-9) // 10/25
        assertEquals(0.0, cumulative.previous[5], 1e-9)
        assertEquals(100.0, cumulative.goal!!, 1e-9)
        assertEquals("12.5", cumulative.endText)
        assertEquals("이번 주", cumulative.currentName)
        assertEquals("지난주", cumulative.previousName)

        vm.previous()
        advanceUntilIdle()
        assertEquals("이 주", vm.state.value!!.cumulative!!.currentName)
        assertEquals("전 주", vm.state.value!!.cumulative!!.previousName)
    }

    @Test
    fun cumulativeOfTheYearHasTheYearlyGoalPace() = runTest(dispatcher) {
        val vm = viewModel(goals = FakeGoals(weekly = 100, yearly = 3000))

        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        val cumulative = vm.state.value!!.cumulative!!

        assertEquals(304, cumulative.current.size) // January 1 to October 31
        assertEquals(60.0, cumulative.current.last(), 1e-9)
        assertEquals(365, cumulative.previous.size)
        assertEquals(365, cumulative.length)
        assertEquals(3000.0, cumulative.goal!!, 1e-9)
        assertEquals("올해", cumulative.currentName)
        assertEquals("작년", cumulative.previousName)

        vm.selectMetric(StatMetric.TIME) // a goal is a distance
        advanceUntilIdle()
        assertNull(vm.state.value!!.cumulative!!.goal)
    }

    @Test
    fun cumulativeFollowsTheMetricAndAllHasNone() = runTest(dispatcher) {
        val vm = viewModel(FakeRides(sample), FakeGoals(weekly = 100))

        vm.selectMetric(StatMetric.TIME)
        advanceUntilIdle()
        var cumulative = vm.state.value!!.cumulative!!
        assertEquals(1.5, cumulative.current.last(), 1e-9) // 30 + 60 minutes, in hours
        assertEquals("1:30", cumulative.endText)
        assertEquals(2.0, cumulative.previous.last(), 1e-9)
        assertEquals("누적 이번 달 1시간 30분, 지난달 2시간 0분", cumulative.description)

        vm.selectMetric(StatMetric.ASCENT)
        advanceUntilIdle()
        cumulative = vm.state.value!!.cumulative!!
        assertEquals(50.0, cumulative.current.last(), 1e-9)
        assertEquals("50", cumulative.endText)
        assertEquals("누적 이번 달 50 m, 지난달 100 m", cumulative.description)

        vm.selectKind(PeriodKind.ALL)
        advanceUntilIdle()
        assertNull(vm.state.value!!.cumulative)
    }

    @Test
    fun cumulativeWithoutRidesIsFlatAtZero() = runTest(dispatcher) {
        val cumulative = viewModel(FakeRides()).state.value!!.cumulative!!

        assertTrue(cumulative.current.all { it == 0.0 } && cumulative.previous.all { it == 0.0 })
        assertEquals("0", cumulative.endText)
        assertEquals("누적 이번 달 0.0 km, 지난달 0.0 km", cumulative.description)
    }

    /** The goal pace ends on the last day of the shown period, not of the longer axis. */
    @Test
    fun cumulativeKnowsTheDaysOfTheShownPeriod() = runTest(dispatcher) {
        today = LocalDate.of(2026, 2, 10)
        val vm = viewModel(FakeRides(listOf(ride(1, LocalDate.of(2026, 1, 20), 5.0))), FakeGoals(weekly = 100, yearly = 3000))

        val february = vm.state.value!!.cumulative!!
        assertEquals(10, february.current.size)
        assertEquals(31, february.length) // January is the longer
        assertEquals(28, february.currentDays)

        vm.selectKind(PeriodKind.WEEK)
        advanceUntilIdle()
        assertEquals(7, vm.state.value!!.cumulative!!.currentDays)

        vm.selectKind(PeriodKind.YEAR)
        advanceUntilIdle()
        assertEquals(365, vm.state.value!!.cumulative!!.currentDays)
        today = LocalDate.of(2028, 3, 1)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(366, vm.state.value!!.cumulative!!.currentDays)
    }

    /** A finished month is compared with all of the month before it: the sentence, the legend and the grey line agree. */
    @Test
    fun cumulativeOfAFinishedMonthReadsTheWholePreviousMonth() = runTest(dispatcher) {
        val vm = viewModel(FakeRides(listOf(ride(1, LocalDate.of(2026, 1, 30), 10.0), ride(2, LocalDate.of(2026, 2, 5), 3.0))))

        vm.show(PeriodKind.MONTH, LocalDate.of(2026, 2, 1))
        advanceUntilIdle()
        val cumulative = vm.state.value!!.cumulative!!

        assertEquals(28, cumulative.current.size)
        assertEquals(31, cumulative.previous.size)
        assertEquals(10.0, cumulative.previous.last(), 1e-9) // January 30 is in the grey line's end
        assertEquals("누적 2월 3.0 km, 1월 10.0 km", cumulative.description)
        assertEquals("1월보다 −7.0 km (−70%)", vm.state.value!!.comparisonText)
    }

    // ---- V1.9.0: activity chips ----

    private val oct = LocalDate.of(2026, 10, 1)

    /** A mixed month: a bike ride, a walk, a run and a hike. */
    private val mixed = listOf(
        ride(1, LocalDate.of(2026, 10, 3), 20.0, 60, 100.0),
        ride(2, LocalDate.of(2026, 10, 3), 3.0, 40, 10.0, activity = ActivityType.WALK),
        ride(3, LocalDate.of(2026, 10, 25), 10.0, 50, 20.0, activity = ActivityType.RUN),
        ride(4, LocalDate.of(2026, 10, 25), 8.0, 180, 600.0, activity = ActivityType.HIKE, elapsedMinutes = 240, maxAltitudeM = 950.0),
    )

    @Test
    fun chipFiltersRides() = runTest(dispatcher) {
        val rides = FakeRides(mixed)
        val chip = FakeChip(null)
        val vm = viewModel(rides, chip = chip)
        assertNull(vm.state.value!!.activity)
        assertEquals(41_000.0, vm.state.value!!.totals.distanceM, 1e-9)
        assertEquals(4, vm.state.value!!.totals.rides)

        vm.selectActivity(ActivityType.RUN)
        advanceUntilIdle()
        val run = vm.state.value!!
        assertEquals(ActivityType.RUN, run.activity)
        assertEquals(10_000.0, run.totals.distanceM, 1e-9)
        assertEquals(1, run.totals.rides)
        assertEquals(1, run.cumulativeRides)
        assertEquals(ActivityType.RUN, run.cumulativeActivity)

        vm.selectActivity(null)
        advanceUntilIdle()
        assertEquals(4, vm.state.value!!.totals.rides)
        assertNull(vm.state.value!!.cumulativeActivity)
    }

    @Test
    fun chipRemembered() = runTest(dispatcher) {
        val chip = FakeChip(null)
        val vm = viewModel(chip = chip)
        vm.selectActivity(ActivityType.HIKE)
        advanceUntilIdle()
        assertEquals(ActivityType.HIKE, chip.flow.value)

        // A new tab (view model) starts on the remembered chip.
        val again = viewModel(chip = chip)
        assertEquals(ActivityType.HIKE, again.state.value!!.activity)
        again.selectActivity(null)
        advanceUntilIdle()
        assertNull(chip.flow.value)
    }

    @Test
    fun chipChangeClearsTheTappedBar() = runTest(dispatcher) {
        val vm = viewModel(FakeRides(mixed), chip = FakeChip(null))
        vm.selectBar(0)
        advanceUntilIdle()
        vm.selectActivity(ActivityType.BIKE)
        advanceUntilIdle()
        assertNull(vm.state.value!!.selectedBar)
    }

    @Test
    fun allChipStacksBarsByActivity() = runTest(dispatcher) {
        val state = viewModel(FakeRides(mixed), chip = FakeChip(null)).state.value!!
        // October's weeks: 9/28, 10/5, 10/12, 10/19, 10/26 → the 3rd of October is in the first, the 25th in the fourth.
        val first = state.bars[0]
        assertEquals(listOf(ActivityType.BIKE, ActivityType.WALK), first.parts.map { it.activity })
        assertEquals(listOf(20_000.0, 3_000.0), first.parts.map { it.value })
        assertEquals(23_000.0, first.value, 1e-9)
        val fourth = state.bars[3]
        assertEquals(listOf(ActivityType.RUN, ActivityType.HIKE), fourth.parts.map { it.activity })
        assertEquals(listOf(10_000.0, 8_000.0), fourth.parts.map { it.value })
        assertTrue(state.bars[1].parts.isEmpty())
        // A single-activity chip draws single-colour bars.
        val bike = viewModel(FakeRides(mixed), chip = FakeChip(ActivityType.BIKE)).state.value!!
        assertTrue(bike.bars.all { it.parts.isEmpty() })
    }

    @Test
    fun allChipStackFollowsTheMetric() = runTest(dispatcher) {
        val vm = viewModel(FakeRides(mixed), chip = FakeChip(null))
        vm.selectMetric(StatMetric.ASCENT)
        advanceUntilIdle()
        val parts = vm.state.value!!.bars[3].parts
        assertEquals(listOf(20.0, 600.0), parts.map { it.value })
    }

    @Test
    fun streakAllCountsAnyActivity() = runTest(dispatcher) {
        // A bike ride in one week, a walk in the next one, a run in this one: three weeks in a row.
        val rides = listOf(
            ride(1, LocalDate.of(2026, 10, 14), 5.0),
            ride(2, LocalDate.of(2026, 10, 21), 3.0, activity = ActivityType.WALK),
            ride(3, LocalDate.of(2026, 10, 28), 6.0, activity = ActivityType.RUN),
        )
        assertEquals("3주 연속 주행 중", viewModel(FakeRides(rides), chip = FakeChip(null)).state.value!!.streakText)
        assertEquals("1주 연속 주행 중", viewModel(FakeRides(rides), chip = FakeChip(ActivityType.RUN)).state.value!!.streakText)
    }

    @Test
    fun streakHikeOnlyCountsHikes() = runTest(dispatcher) {
        val rides = listOf(
            ride(1, LocalDate.of(2026, 10, 14), 5.0, activity = ActivityType.HIKE),
            ride(2, LocalDate.of(2026, 10, 21), 5.0), // a bike week breaks the hikes
            ride(3, LocalDate.of(2026, 10, 28), 5.0, activity = ActivityType.HIKE),
        )
        assertEquals("1주 연속 주행 중", viewModel(FakeRides(rides), chip = FakeChip(ActivityType.HIKE)).state.value!!.streakText)
        assertEquals("3주 연속 주행 중", viewModel(FakeRides(rides), chip = FakeChip(null)).state.value!!.streakText)
    }

    @Test
    fun runCellsShowPace() = runTest(dispatcher) {
        val state = viewModel(FakeRides(mixed), chip = FakeChip(ActivityType.RUN)).state.value!!
        assertEquals(
            listOf("달리기" to "1회", "이동 시간" to "50분", "평균 페이스" to "5'00\"/km"),
            state.cells.map { it.label to it.value },
        )
        assertEquals(listOf(StatCellKind.RIDES, StatCellKind.TIME, StatCellKind.PACE), state.cells.map { it.kind })
        val walk = viewModel(FakeRides(mixed), chip = FakeChip(ActivityType.WALK)).state.value!!
        assertEquals(listOf("걷기" to "1회", "이동 시간" to "40분", "평균 페이스" to "13'20\"/km"), walk.cells.map { it.label to it.value })
    }

    @Test
    fun hikeCellsShowElapsedAndAscent() = runTest(dispatcher) {
        val state = viewModel(FakeRides(mixed), chip = FakeChip(ActivityType.HIKE)).state.value!!
        assertEquals(
            listOf("등산" to "1회", "산행 시간" to "4시간 0분", "누적 상승" to "600 m"),
            state.cells.map { it.label to it.value },
        )
        // The time bars of a hike are the elapsed time too.
        val vm = viewModel(FakeRides(mixed), chip = FakeChip(ActivityType.HIKE))
        vm.selectMetric(StatMetric.TIME)
        advanceUntilIdle()
        assertEquals("4:00", vm.state.value!!.bars[3].shortText)
    }

    @Test
    fun bikeAndAllCells() = runTest(dispatcher) {
        val bike = viewModel(FakeRides(mixed), chip = FakeChip(ActivityType.BIKE)).state.value!!
        assertEquals(listOf("라이딩" to "1회", "이동 시간" to "1시간 0분", "상승 고도" to "100 m"), bike.cells.map { it.label to it.value })
        val all = viewModel(FakeRides(mixed), chip = FakeChip(null)).state.value!!
        assertEquals(listOf("활동" to "4회", "이동 시간" to "5시간 30분"), all.cells.map { it.label to it.value })
    }

    @Test
    fun ridesWordPerChip() = runTest(dispatcher) {
        fun word(chip: ActivityType?): String =
            viewModel(FakeRides(mixed), chip = FakeChip(chip)).state.value!!.cells.first { it.kind == StatCellKind.RIDES }
                .let { "${it.label} ${it.value}" }
        assertEquals("라이딩 1회", word(ActivityType.BIKE))
        assertEquals("걷기 1회", word(ActivityType.WALK))
        assertEquals("달리기 1회", word(ActivityType.RUN))
        assertEquals("등산 1회", word(ActivityType.HIKE))
        assertEquals("활동 4회", word(null))
    }

    @Test
    fun allChipShowsGoalRowPerActivityWithGoal() = runTest(dispatcher) {
        val goals = FakeGoals(weekly = 50, yearly = 1000)
        goals.flow.value = goals.flow.value + (ActivityType.RUN to GoalKm(null, 500))
        val state = viewModel(FakeRides(mixed), goals, FakeChip(null)).state.value!!
        assertNull(state.weeklyGoal)
        assertNull(state.yearlyGoal)
        assertEquals(listOf(ActivityType.BIKE, ActivityType.RUN), state.goalRows.map { it.activity })
        val bike = state.goalRows[0]
        assertEquals(50, bike.weekly!!.goalKm)
        assertEquals(1000, bike.yearly!!.goalKm)
        assertEquals(20_000.0, bike.yearly!!.distanceM, 1e-9) // the bike's year, not everything's
        assertNull(state.goalRows[1].weekly)
        assertEquals(10_000.0, state.goalRows[1].yearly!!.distanceM, 1e-9)
        assertNull(state.projectionText)
    }

    @Test
    fun chipShowsOnlyItsOwnGoal() = runTest(dispatcher) {
        val goals = FakeGoals(weekly = 50, yearly = 1000)
        goals.flow.value = goals.flow.value + (ActivityType.WALK to GoalKm(7, null))
        val walk = viewModel(FakeRides(mixed), goals, FakeChip(ActivityType.WALK)).state.value!!
        assertEquals(7, walk.weeklyGoal!!.goalKm)
        assertNull(walk.yearlyGoal)
        assertTrue(walk.goalRows.isEmpty())
        val hike = viewModel(FakeRides(mixed), goals, FakeChip(ActivityType.HIKE)).state.value!!
        assertNull(hike.weeklyGoal)
        assertNull(hike.yearlyGoal)
        assertEquals(mapOf(ActivityType.BIKE to GoalKm(50, 1000), ActivityType.WALK to GoalKm(7, null)), hike.goals)
    }

    @Test
    fun noGoalsShowsSetGoal() = runTest(dispatcher) {
        val state = viewModel(FakeRides(mixed), FakeGoals(), FakeChip(null)).state.value!!
        assertTrue(state.goalRows.isEmpty())
        assertNull(state.weeklyGoal)
        assertNull(state.yearlyGoal)
    }

    @Test
    fun saveGoalsGoesToTheActivity() = runTest(dispatcher) {
        val goals = FakeGoals()
        val vm = viewModel(FakeRides(mixed), goals, FakeChip(null))
        vm.saveGoals(ActivityType.RUN, GoalInput.Valid(30), GoalInput.Empty)
        advanceUntilIdle()
        assertEquals(listOf(ActivityType.RUN), goals.savedFor)
        assertEquals(GoalKm(30, null), goals.saved.single())
        assertEquals(listOf(ActivityType.RUN), vm.state.value!!.goalRows.map { it.activity })
        // The old two-goal call is the bike's.
        vm.saveGoals(GoalInput.Valid(100), GoalInput.Empty)
        advanceUntilIdle()
        assertEquals(ActivityType.BIKE, goals.savedFor.last())
    }

    @Test
    fun recordsAreGroupedByActivityUnderAll() = runTest(dispatcher) {
        val rides = listOf(
            ride(1, LocalDate.of(2026, 10, 3), 20.0, 60, 100.0),
            ride(2, LocalDate.of(2026, 10, 4), 30.0, 90, 200.0),
            ride(3, LocalDate.of(2026, 10, 10), 5.0, 30, activity = ActivityType.RUN),
            ride(4, LocalDate.of(2026, 10, 11), 6.0, 33, activity = ActivityType.RUN),
        )
        val fake = FakeRides(rides)
        fake.efforts.value = listOf(
            StoredBestEffort(3, BestEffortDistance.FIVE_K, 1_491_000),
            StoredBestEffort(4, BestEffortDistance.FIVE_K, 3_912_000),
        )
        val all = viewModel(fake, chip = FakeChip(null)).state.value!!.records
        val headed = all.filter { it.heading != null }
        assertEquals(listOf("자전거" to "가장 긴 주행", "달리기" to "가장 긴 달리기", "전체" to "가장 많이 탄 주"), headed.map { it.heading to it.label })
        val best = all.first { it.label == "5 km 최고 기록" }
        assertTrue(best.value.startsWith("24'51\""), best.value)
        val longRun = viewModel(fake, chip = FakeChip(ActivityType.RUN)).state.value!!.records
        assertTrue(longRun.all { it.heading == null })
        assertEquals("가장 긴 달리기", longRun.first().label)
        assertTrue(longRun.none { it.label == "가장 긴 주행" })
    }
}

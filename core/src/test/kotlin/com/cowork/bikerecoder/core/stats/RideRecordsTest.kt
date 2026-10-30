package com.cowork.bikerecoder.core.stats

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideRecordsTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private var nextId = 1L

    private fun ride(
        date: LocalDate,
        distanceM: Double = 10_000.0,
        movingMs: Long = 1_000_000L,
        ascentM: Double = 0.0,
        startedAt: Long = nextId * 1_000,
        tripId: Long = 1L,
    ): RideStat {
        val id = nextId++
        return RideStat(id, tripId, startedAt, date, distanceM, movingMs, ascentM)
    }

    // 2026-10-31(토)이 든 주는 10-26(월)~11-01(일)
    private val today = d(2026, 10, 31)

    @Test
    fun streakCountsBackFromThisOrLastWeek() {
        val withThisWeek = listOf(
            ride(d(2026, 10, 27)), ride(d(2026, 10, 20)), ride(d(2026, 10, 13)), ride(d(2026, 10, 6)),
            ride(d(2026, 9, 22)), // 한 주 건너뜀: 이어지지 않는다
        )
        assertEquals(Streak(4, true), RideRecords.streak(withThisWeek, today))

        val fromLastWeek = listOf(ride(d(2026, 10, 21)), ride(d(2026, 10, 14)), ride(d(2026, 10, 8)))
        assertEquals(Streak(3, false), RideRecords.streak(fromLastWeek, today))

        val gap = listOf(ride(d(2026, 10, 13)), ride(d(2026, 10, 6)))
        assertEquals(Streak(0, false), RideRecords.streak(gap, today))
        assertEquals(Streak(0, false), RideRecords.streak(emptyList(), today))
    }

    @Test
    fun streakIgnoresShortRides() {
        val rides = listOf(ride(d(2026, 10, 28), distanceM = 9.9))
        assertEquals(Streak(0, false), RideRecords.streak(rides, today))
    }

    @Test
    fun streakAcrossYearEnd() {
        val rides = listOf(ride(d(2025, 12, 23)), ride(d(2025, 12, 31)), ride(d(2026, 1, 6)))
        assertEquals(Streak(3, true), RideRecords.streak(rides, d(2026, 1, 7)))
        // 2025-12-29(월)~2026-01-04(일)는 한 주: 연말 양쪽 날이 한 주로 묶인다
        val sameWeek = listOf(ride(d(2025, 12, 30)), ride(d(2026, 1, 2)))
        assertEquals(Streak(1, false), RideRecords.streak(sameWeek, d(2026, 1, 7)))
    }

    @Test
    fun longestStreakAndItsWeeks() {
        assertNull(RideRecords.longestStreak(emptyList()))
        val rides = listOf(
            // 2주 (8/3, 8/10)
            ride(d(2026, 8, 5)), ride(d(2026, 8, 12)),
            // 3주 (8/24, 8/31, 9/7)
            ride(d(2026, 8, 24)), ride(d(2026, 8, 31)), ride(d(2026, 9, 11)),
            // 3주 (10/5, 10/12, 10/19): 길이가 같으면 먼저 것이 남는다
            ride(d(2026, 10, 7)), ride(d(2026, 10, 12)), ride(d(2026, 10, 25)),
        )
        assertEquals(StreakRun(3, d(2026, 8, 24), d(2026, 9, 7)), RideRecords.longestStreak(rides))

        val longer = rides + ride(d(2026, 10, 27))
        assertEquals(StreakRun(4, d(2026, 10, 5), d(2026, 10, 26)), RideRecords.longestStreak(longer))
    }

    @Test
    fun recordsNeedTwoRides() {
        assertTrue(RideRecords.rideRecords(emptyList()).isEmpty())
        assertTrue(RideRecords.rideRecords(listOf(ride(d(2026, 10, 1)))).isEmpty())
        // 10 m 미만은 횟수에 세지 않는다
        assertTrue(RideRecords.rideRecords(listOf(ride(d(2026, 10, 1)), ride(d(2026, 10, 2), distanceM = 5.0))).isEmpty())
    }

    @Test
    fun fourRideRecords() {
        val longDist = ride(d(2026, 10, 1), distanceM = 40_000.0, movingMs = 5_000_000L, ascentM = 100.0)
        val longTime = ride(d(2026, 10, 2), distanceM = 30_000.0, movingMs = 9_000_000L, ascentM = 200.0)
        val climb = ride(d(2026, 10, 3), distanceM = 20_000.0, movingMs = 2_000_000L, ascentM = 900.0)
        val fast = ride(d(2026, 10, 4), distanceM = 10_000.0, movingMs = 600_000L, ascentM = 10.0)
        // 4,999 m는 더 빨라도 FASTEST_AVG에서 빠진다. 이동 시간 0도 뺀다.
        val tooShortFaster = ride(d(2026, 10, 5), distanceM = 4_999.0, movingMs = 100_000L)
        val noTime = ride(d(2026, 10, 6), distanceM = 6_000.0, movingMs = 0L)
        val records = RideRecords.rideRecords(listOf(longDist, longTime, climb, fast, tooShortFaster, noTime))
            .associateBy { it.kind }
        assertEquals(4, records.size)
        assertEquals(longDist, records.getValue(RideRecordKind.LONGEST_DISTANCE).ride)
        assertEquals(40_000.0, records.getValue(RideRecordKind.LONGEST_DISTANCE).value)
        assertEquals(longTime, records.getValue(RideRecordKind.LONGEST_TIME).ride)
        assertEquals(9_000_000.0, records.getValue(RideRecordKind.LONGEST_TIME).value)
        assertEquals(climb, records.getValue(RideRecordKind.MOST_ASCENT).ride)
        assertEquals(900.0, records.getValue(RideRecordKind.MOST_ASCENT).value)
        assertEquals(fast, records.getValue(RideRecordKind.FASTEST_AVG).ride)
        assertEquals(10_000.0 / 600.0, records.getValue(RideRecordKind.FASTEST_AVG).value, 1e-9)
    }

    @Test
    fun fastestAvgStartsAtExactlyFiveKm() {
        val a = ride(d(2026, 10, 1), distanceM = 5_000.0, movingMs = 1_000_000L)
        val b = ride(d(2026, 10, 2), distanceM = 4_999.0, movingMs = 10_000L)
        val rec = RideRecords.rideRecords(listOf(a, b)).single { it.kind == RideRecordKind.FASTEST_AVG }
        assertEquals(a, rec.ride)
    }

    @Test
    fun fastestAvgIsAbsentWithoutAnEligibleRide() {
        val kinds = RideRecords.rideRecords(
            listOf(ride(d(2026, 10, 1), distanceM = 4_000.0, ascentM = 1.0), ride(d(2026, 10, 2), distanceM = 3_000.0, ascentM = 2.0)),
        ).map { it.kind }
        assertEquals(
            listOf(RideRecordKind.LONGEST_DISTANCE, RideRecordKind.LONGEST_TIME, RideRecordKind.MOST_ASCENT),
            kinds,
        )
    }

    @Test
    fun recordTiesKeepTheEarlierRide() {
        val later = ride(d(2026, 10, 2), startedAt = 200)
        val earlier = ride(d(2026, 10, 1), startedAt = 100)
        val records = RideRecords.rideRecords(listOf(later, earlier))
        assertEquals(setOf(earlier), records.map { it.ride }.toSet())
        // startedAt이 같으면 작은 segmentId
        val x = ride(d(2026, 10, 1), startedAt = 500)
        val y = ride(d(2026, 10, 1), startedAt = 500)
        assertEquals(x, RideRecords.rideRecords(listOf(y, x)).first().ride)
    }

    @Test
    fun bestWeekAndMonth() {
        assertNull(RideRecords.bestWeek(emptyList()))
        assertNull(RideRecords.bestMonth(emptyList()))
        val rides = listOf(
            // 9/28(월) 주: 30 km (9월 20 + 10월 10 → 월 기준은 날짜로 갈린다)
            ride(d(2026, 9, 29), distanceM = 20_000.0), ride(d(2026, 10, 1), distanceM = 10_000.0),
            // 10/5 주: 30 km (같은 거리 → 먼저 주가 남는다)
            ride(d(2026, 10, 6), distanceM = 15_000.0), ride(d(2026, 10, 8), distanceM = 15_000.0),
            ride(d(2026, 8, 3), distanceM = 25_000.0),
        )
        assertEquals(PeriodRecord(d(2026, 9, 28), 30_000.0), RideRecords.bestWeek(rides))
        // 월: 8월 25 km, 9월 20 km, 10월 40 km
        assertEquals(PeriodRecord(d(2026, 10, 1), 40_000.0), RideRecords.bestMonth(rides))

        val tie = listOf(ride(d(2026, 8, 3), distanceM = 10_000.0), ride(d(2026, 9, 3), distanceM = 10_000.0))
        assertEquals(PeriodRecord(d(2026, 8, 1), 10_000.0), RideRecords.bestMonth(tie))
        assertEquals(PeriodRecord(d(2026, 8, 3), 10_000.0), RideRecords.bestWeek(tie))
    }

    @Test
    fun recordHolderTripsOnlyCurrentHolders() {
        assertTrue(RideRecords.recordHolderTrips(listOf(ride(d(2026, 10, 1), tripId = 7))).isEmpty())
        val a = ride(d(2026, 10, 1), distanceM = 30_000.0, movingMs = 9_000_000L, ascentM = 100.0, tripId = 10)
        val b = ride(d(2026, 10, 2), distanceM = 20_000.0, movingMs = 1_000_000L, ascentM = 50.0, tripId = 20)
        // a: 거리·시간·상승 / b: 속도(20km/1000s)가 a(30km/9000s)보다 빠르다
        assertEquals(setOf(10L, 20L), RideRecords.recordHolderTrips(listOf(a, b)))
        // 세 번째 여행은 아무 기록도 갖지 못한다
        val c = ride(d(2026, 10, 3), distanceM = 11_000.0, movingMs = 5_000_000L, ascentM = 1.0, tripId = 30)
        assertEquals(setOf(10L, 20L), RideRecords.recordHolderTrips(listOf(a, b, c)))
    }

    @Test
    fun newRecordsNeedAnEarlierRideOfThatKind() {
        val first = ride(d(2026, 10, 1), distanceM = 10_000.0, movingMs = 1_000_000L, ascentM = 100.0)
        // 첫 주행은 신기록이 없다
        assertTrue(RideRecords.newRecords(listOf(first), first.segmentId).isEmpty())

        // 앞 최고와 같으면 없다
        val same = ride(d(2026, 10, 2), distanceM = 10_000.0, movingMs = 1_000_000L, ascentM = 100.0)
        assertTrue(RideRecords.newRecords(listOf(first, same), same.segmentId).isEmpty())

        // 크면 그 항목이 나온다
        val bigger = ride(d(2026, 10, 3), distanceM = 12_000.0, movingMs = 1_000_000L, ascentM = 150.0)
        assertEquals(
            listOf(RideRecordKind.LONGEST_DISTANCE, RideRecordKind.MOST_ASCENT, RideRecordKind.FASTEST_AVG),
            RideRecords.newRecords(listOf(first, same, bigger), bigger.segmentId),
        )

        // 뒤의 주행은 보지 않는다
        assertTrue(RideRecords.newRecords(listOf(first, same, bigger), first.segmentId).isEmpty())
    }

    @Test
    fun newFastestAvgNeedsAnEarlierRideOfFiveKm() {
        val short = ride(d(2026, 10, 1), distanceM = 4_000.0, movingMs = 1_000_000L)
        val firstLong = ride(d(2026, 10, 2), distanceM = 6_000.0, movingMs = 100_000L)
        assertTrue(RideRecordKind.FASTEST_AVG !in RideRecords.newRecords(listOf(short, firstLong), firstLong.segmentId))
        // 앞에 5 km 이상이 있으면 더 빠른 경우 신기록
        val faster = ride(d(2026, 10, 3), distanceM = 6_000.0, movingMs = 90_000L)
        assertTrue(RideRecordKind.FASTEST_AVG in RideRecords.newRecords(listOf(short, firstLong, faster), faster.segmentId))
        // 4,999 m 주행은 아무리 빨라도 대상이 아니다
        val shortFast = ride(d(2026, 10, 4), distanceM = 4_999.0, movingMs = 1_000L)
        assertTrue(RideRecordKind.FASTEST_AVG !in RideRecords.newRecords(listOf(short, firstLong, shortFast), shortFast.segmentId))
    }

    @Test
    fun newRecordsOfAShortSegmentIsEmpty() {
        val a = ride(d(2026, 10, 1))
        val shortOne = ride(d(2026, 10, 2), distanceM = 5.0)
        assertTrue(RideRecords.newRecords(listOf(a, shortOne), shortOne.segmentId).isEmpty())
        assertTrue(RideRecords.newRecords(listOf(a), 999L).isEmpty())
    }

    @Test
    fun newRecordsEarlierMeansSmallerStartedAt() {
        // 같은 startedAt이면 segmentId가 작은 쪽이 더 이르다
        val x = ride(d(2026, 10, 1), distanceM = 10_000.0, startedAt = 100)
        val y = ride(d(2026, 10, 1), distanceM = 11_000.0, startedAt = 100)
        assertTrue(RideRecords.newRecords(listOf(x, y), x.segmentId).isEmpty())
        assertNotNull(RideRecords.newRecords(listOf(x, y), y.segmentId).singleOrNull { it == RideRecordKind.LONGEST_DISTANCE })
    }

    @Test
    fun zeroValuedRecordsAreNotRecords() {
        // 모든 주행의 상승이 0 m: 가장 많이 오른 주행 기록 없음, 트로피도 그 때문에는 없음
        val a = ride(d(2026, 10, 1), distanceM = 20_000.0, movingMs = 1_000_000L, ascentM = 0.0, tripId = 10)
        val b = ride(d(2026, 10, 2), distanceM = 10_000.0, movingMs = 2_000_000L, ascentM = 0.0, tripId = 20)
        val kinds = RideRecords.rideRecords(listOf(a, b)).map { it.kind }
        assertTrue(RideRecordKind.MOST_ASCENT !in kinds)
        assertEquals(setOf(10L, 20L), RideRecords.recordHolderTrips(listOf(a, b)))
        // 상승만 같은 여행(30)이 0 m로 트로피를 받지 않는다
        val c = ride(d(2026, 10, 3), distanceM = 1_000.0, movingMs = 1_000L, ascentM = 0.0, tripId = 30)
        assertTrue(30L !in RideRecords.recordHolderTrips(listOf(a, b, c)))

        // 모든 주행의 이동 시간이 0 ms: LONGEST_TIME·FASTEST_AVG 기록 없음
        val x = ride(d(2026, 10, 1), distanceM = 20_000.0, movingMs = 0L, ascentM = 5.0)
        val y = ride(d(2026, 10, 2), distanceM = 10_000.0, movingMs = 0L, ascentM = 9.0)
        val xyKinds = RideRecords.rideRecords(listOf(x, y)).map { it.kind }
        assertEquals(listOf(RideRecordKind.LONGEST_DISTANCE, RideRecordKind.MOST_ASCENT), xyKinds)
    }

    @Test
    fun newRecordsNeverIncludeAZeroValue() {
        val first = ride(d(2026, 10, 1), distanceM = 10_000.0, movingMs = 0L, ascentM = 0.0)
        val second = ride(d(2026, 10, 2), distanceM = 12_000.0, movingMs = 0L, ascentM = 0.0)
        assertEquals(listOf(RideRecordKind.LONGEST_DISTANCE), RideRecords.newRecords(listOf(first, second), second.segmentId))
        // 앞 주행의 값이 0이고 이번이 0보다 크면 신기록 (0은 기록이 아니지만 이번 값은 양수)
        val third = ride(d(2026, 10, 3), distanceM = 5_000.0, movingMs = 1_000L, ascentM = 3.0)
        val result = RideRecords.newRecords(listOf(first, second, third), third.segmentId)
        assertTrue(RideRecordKind.LONGEST_TIME in result && RideRecordKind.MOST_ASCENT in result)
    }
}

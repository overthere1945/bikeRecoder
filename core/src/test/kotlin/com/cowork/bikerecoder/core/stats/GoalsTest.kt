package com.cowork.bikerecoder.core.stats

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private var nextId = 1L

    private fun ride(date: LocalDate, distanceM: Double): RideStat {
        val id = nextId++
        return RideStat(id, 1L, id * 1_000, date, distanceM, 1_000L, 0.0)
    }

    @Test
    fun goalParsing() {
        assertEquals(GoalInput.Empty, Goals.parse(""))
        assertEquals(GoalInput.Empty, Goals.parse("  "))
        assertEquals(GoalInput.Valid(1000), Goals.parse("1,000"))
        assertEquals(GoalInput.Valid(1000), Goals.parse(" 1000 "))
        assertEquals(GoalInput.Valid(1), Goals.parse("1"))
        assertEquals(GoalInput.Valid(100_000), Goals.parse("100000"))
        assertEquals(GoalInput.Valid(100_000), Goals.parse("100,000"))
        for (bad in listOf("0", "100001", "1.5", "-3", "abc", "1,5", ",", "1,,000", "+5", "1 000", "99999999999999999999", "٣")) {
            assertEquals(GoalInput.Invalid, Goals.parse(bad), "'$bad'")
        }
    }

    @Test
    fun crossedOnlyOnTheWayUp() {
        assertTrue(Goals.crossed(100, 90_000.0, 100_000.0))
        assertFalse(Goals.crossed(100, 100_000.0, 120_000.0))
        assertFalse(Goals.crossed(null, 0.0, 1_000_000.0))
        assertFalse(Goals.crossed(100, 50_000.0, 99_900.0))
        assertTrue(Goals.crossed(100, 0.0, 250_000.0))
    }

    @Test
    fun yearProjection() {
        assertNull(Goals.yearProjectionKm(200_000.0, d(2026, 1, 6)))
        assertNull(Goals.yearProjectionKm(0.0, d(2026, 3, 1)))
        assertEquals(1220, Goals.yearProjectionKm(200_000.0, d(2026, 3, 1)))
        // 윤년 2028-03-01은 61일째, 366일: 200/61*366 = 1200
        assertEquals(1200, Goals.yearProjectionKm(200_000.0, d(2028, 3, 1)))
    }

    @Test
    fun yearProjectionFirstShownDay() {
        // 7일째부터 보인다: 70 km ÷ 7 × 365 = 3650
        assertEquals(3650, Goals.yearProjectionKm(70_000.0, d(2026, 1, 7)))
    }

    @Test
    fun weekAndYearBeforeAfter() {
        val target = ride(d(2026, 10, 28), 30_000.0)          // 10/26 주
        val sameWeek = ride(d(2026, 10, 26), 20_000.0)
        val otherWeekSameYear = ride(d(2026, 10, 20), 50_000.0)
        val otherYear = ride(d(2025, 10, 28), 70_000.0)
        val tiny = ride(d(2026, 10, 29), 9.0)                // 10 m 미만: 넣지 않는다
        val all = listOf(sameWeek, otherWeekSameYear, otherYear, tiny, target)
        assertEquals(20_000.0 to 50_000.0, Goals.weekBeforeAfter(all, target.segmentId))
        assertEquals(70_000.0 to 100_000.0, Goals.yearBeforeAfter(all, target.segmentId))
    }

    @Test
    fun weekBeforeAfterAcrossYearEndStaysInOneWeek() {
        val dec = ride(d(2025, 12, 30), 10_000.0)
        val jan = ride(d(2026, 1, 2), 5_000.0)
        assertEquals(10_000.0 to 15_000.0, Goals.weekBeforeAfter(listOf(dec, jan), jan.segmentId))
        // 해 기준은 갈린다
        assertEquals(0.0 to 5_000.0, Goals.yearBeforeAfter(listOf(dec, jan), jan.segmentId))
    }

    @Test
    fun beforeAfterOfAShortOrMissingSegmentNeverCrosses() {
        val a = ride(d(2026, 10, 28), 90_000.0)
        val shortOne = ride(d(2026, 10, 29), 5.0)
        for (id in listOf(shortOne.segmentId, 999L)) {
            val (b, f) = Goals.weekBeforeAfter(listOf(a, shortOne), id)
            assertEquals(b, f)
            assertFalse(Goals.crossed(90, b, f))
            val (yb, yf) = Goals.yearBeforeAfter(listOf(a, shortOne), id)
            assertEquals(yb, yf)
        }
    }
}

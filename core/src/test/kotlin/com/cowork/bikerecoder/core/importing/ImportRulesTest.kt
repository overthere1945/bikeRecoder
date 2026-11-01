package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportRulesTest {

    private fun span(start: Long, end: Long) = TimeSpan(start, end)

    private fun segment(start: Long, end: Long?) =
        RideSegment(1, 1, start, end, LocalDate.of(2026, 10, 6), SegmentTotals.ZERO)

    @Test
    fun missingTypeIsBike() {
        assertEquals(ActivityType.BIKE, ImportRules.activityOf(null))
        assertEquals(ActivityType.BIKE, ImportRules.activityOf(""))
        assertEquals(ActivityType.BIKE, ImportRules.activityOf("   "))
    }

    @Test
    fun bikeStringsStayBike() {
        listOf("cycling", "Ride", "road_biking", "1", "VirtualRide", " mountain_biking ", "e-bike").forEach {
            assertEquals(ActivityType.BIKE, ImportRules.activityOf(it), it)
        }
    }

    @Test
    fun readsTypes() {
        assertEquals(ActivityType.RUN, ImportRules.activityOf("running"))
        assertEquals(ActivityType.RUN, ImportRules.activityOf("Run"))
        assertEquals(ActivityType.RUN, ImportRules.activityOf("trail_running"))
        assertEquals(ActivityType.WALK, ImportRules.activityOf("Walking"))
        assertEquals(ActivityType.WALK, ImportRules.activityOf("walk"))
        assertEquals(ActivityType.HIKE, ImportRules.activityOf("Hiking"))
        assertEquals(ActivityType.HIKE, ImportRules.activityOf(" hike "))
        // 모르는 글자·숫자는 자전거
        assertEquals(ActivityType.BIKE, ImportRules.activityOf("swimming"))
        assertEquals(ActivityType.BIKE, ImportRules.activityOf("9"))
        assertEquals(ActivityType.BIKE, ImportRules.activityOf("11"))
    }

    @Test
    fun touchingSpansDoNotOverlap() {
        assertFalse(ImportRules.overlaps(span(0, 10), span(10, 20)))
        assertFalse(ImportRules.overlaps(span(10, 20), span(0, 10)))
    }

    @Test
    fun overlapCases() {
        val base = span(100, 200)
        assertTrue(ImportRules.overlaps(span(50, 150), base), "앞")
        assertTrue(ImportRules.overlaps(span(150, 250), base), "뒤")
        assertTrue(ImportRules.overlaps(span(120, 180), base), "안")
        assertTrue(ImportRules.overlaps(span(50, 250), base), "밖")
        assertFalse(ImportRules.overlaps(span(0, 50), base))
        assertFalse(ImportRules.overlaps(span(250, 300), base))
    }

    @Test
    fun openSegmentCountsUntilNow() {
        val spans = ImportRules.existingSpans(listOf(segment(100, 200), segment(300, null)), now = 500)
        assertEquals(listOf(span(100, 200), span(300, 500)), spans)
    }

    @Test
    fun duplicateInListKeepsFirst() {
        val result = ImportRules.statuses(
            spans = listOf(span(100, 200), span(150, 250), span(300, 400), span(350, 450)),
            existing = emptyList(),
        )
        assertEquals(
            listOf(RideStatus.New, RideStatus.DuplicateInList, RideStatus.New, RideStatus.DuplicateInList),
            result,
        )
    }

    @Test
    fun existingBeatsDuplicate() {
        val existing = listOf(span(100, 200))
        val result = ImportRules.statuses(
            spans = listOf(span(120, 180), span(130, 190), span(500, 600), span(550, 650)),
            existing = existing,
        )
        assertEquals(RideStatus.OverlapsExisting, result[0])
        // 둘째는 앞선 것과도 겹치지만 기존과 겹침이 우선
        assertEquals(RideStatus.OverlapsExisting, result[1])
        assertEquals(RideStatus.New, result[2])
        assertEquals(RideStatus.DuplicateInList, result[3])
    }

    @Test
    fun noNotCyclingStatus() {
        // 종류는 상태에 관여하지 않는다: 겹치지 않는 읽은 주행은 모두 새 주행이다.
        val result = ImportRules.statuses(
            spans = listOf(span(100, 200), span(300, 400), span(500, 600)),
            existing = emptyList(),
        )
        assertEquals(listOf(RideStatus.New, RideStatus.New, RideStatus.New), result)
    }

    @Test
    fun unreadableIgnoredForDuplicates() {
        val result = ImportRules.statuses(
            spans = listOf(null, span(100, 200), span(150, 250)),
            existing = emptyList(),
        )
        assertTrue(result[0] is RideStatus.Unreadable)
        assertEquals(RideStatus.New, result[1])
        assertEquals(RideStatus.DuplicateInList, result[2])
    }
}

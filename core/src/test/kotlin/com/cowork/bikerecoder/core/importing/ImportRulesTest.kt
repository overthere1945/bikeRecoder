package com.cowork.bikerecoder.core.importing

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
    fun missingTypeIsCycling() {
        assertEquals(Activity.CYCLING, ImportRules.activityOf(null))
        assertEquals(Activity.CYCLING, ImportRules.activityOf(""))
        assertEquals(Activity.CYCLING, ImportRules.activityOf("   "))
    }

    @Test
    fun activityTable() {
        listOf("cycling", "Ride", "road_biking", "1", "VirtualRide", " mountain_biking ").forEach {
            assertEquals(Activity.CYCLING, ImportRules.activityOf(it), it)
        }
        assertEquals(Activity.RUNNING, ImportRules.activityOf("running"))
        assertEquals(Activity.WALKING, ImportRules.activityOf("Walking"))
        assertEquals(Activity.HIKING, ImportRules.activityOf("Hiking"))
        assertEquals(Activity.OTHER, ImportRules.activityOf("swimming"))
        assertEquals(Activity.OTHER, ImportRules.activityOf("9"))
        assertEquals(Activity.OTHER, ImportRules.activityOf("11"))
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
        val cycling = Activity.CYCLING
        val result = ImportRules.statuses(
            spans = listOf(span(100, 200), span(150, 250), span(300, 400), span(350, 450)),
            activities = listOf(cycling, cycling, cycling, cycling),
            existing = emptyList(),
        )
        assertEquals(
            listOf(RideStatus.New, RideStatus.DuplicateInList, RideStatus.New, RideStatus.DuplicateInList),
            result,
        )
    }

    @Test
    fun existingBeatsDuplicateAndNotCycling() {
        val existing = listOf(span(100, 200))
        val result = ImportRules.statuses(
            spans = listOf(span(120, 180), span(130, 190), span(500, 600), span(550, 650)),
            activities = listOf(Activity.CYCLING, Activity.CYCLING, Activity.RUNNING, Activity.RUNNING),
            existing = existing,
        )
        assertEquals(RideStatus.OverlapsExisting, result[0])
        // 둘째는 앞선 것과도 겹치지만 기존과 겹침이 우선
        assertEquals(RideStatus.OverlapsExisting, result[1])
        assertEquals(RideStatus.NotCycling, result[2])
        // 넷째는 자전거도 아니지만 목록에 같은 주행이 우선
        assertEquals(RideStatus.DuplicateInList, result[3])
    }

    @Test
    fun unreadableIgnoredForDuplicates() {
        val result = ImportRules.statuses(
            spans = listOf(null, span(100, 200), span(150, 250)),
            activities = listOf(null, Activity.CYCLING, Activity.CYCLING),
            existing = emptyList(),
        )
        assertTrue(result[0] is RideStatus.Unreadable)
        assertEquals(RideStatus.New, result[1])
        assertEquals(RideStatus.DuplicateInList, result[2])
    }
}

package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ruling R13: within one trip a photo belongs to exactly one segment. */
class PhotoOwnershipTest {

    private fun seg(id: Long, startedAt: Long, endedAt: Long?) =
        RideSegment(id, tripId = 1, startedAt, endedAt, LocalDate.of(2026, 10, 18), SegmentTotals.ZERO)

    // A [1 000, 2 000], gap, B [3 000, 4 000]
    private val a = seg(1, 1_000, 2_000)
    private val b = seg(2, 3_000, 4_000)

    private fun owner(takenAt: Long, vararg segments: RideSegment) = PhotoOwnership.ownerOf(takenAt, segments.toList())

    @Test
    fun theContainingSegmentOwnsIt() {
        assertEquals(a.id, owner(1_500, a, b))
        assertEquals(b.id, owner(3_500, a, b))
        // both ends are inside
        assertEquals(a.id, owner(1_000, a, b))
        assertEquals(a.id, owner(2_000, a, b))
        assertEquals(b.id, owner(4_000, a, b))
    }

    @Test
    fun inAGapTheNearestBoundaryOwnsIt() {
        assertEquals(a.id, owner(2_400, a, b)) // 400 after A's end, 600 before B's start
        assertEquals(b.id, owner(2_600, a, b))
    }

    @Test
    fun aTieGoesToTheEarlierSegment() {
        assertEquals(a.id, owner(2_500, a, b))
        assertEquals(a.id, owner(2_500, b, a)) // whatever the order given
    }

    @Test
    fun backToBackSegmentsShareTheirBoundaryWithTheEarlierOne() {
        // "새 구간으로 나누기" at 2 000: A [1 000, 2 000], C [2 000, 3 000]
        val c = seg(3, 2_000, 3_000)
        assertEquals(a.id, owner(2_000, c, a))
        assertEquals(a.id, owner(1_999, a, c))
        assertEquals(c.id, owner(2_001, a, c))
    }

    @Test
    fun beforeTheFirstOrAfterTheLastSegment() {
        assertEquals(a.id, owner(700, a, b))
        assertEquals(b.id, owner(4_300, a, b))
    }

    @Test
    fun anOpenSegmentContainsEverythingFromItsStart() {
        val open = seg(2, 3_000, null)
        assertEquals(open.id, owner(3_200, a, open))
        assertEquals(open.id, owner(9_999_999, a, open))
        assertEquals(open.id, owner(2_600, a, open)) // nearer its start than A's end
        assertEquals(a.id, owner(2_400, a, open))
    }

    @Test
    fun aSingleSegmentOwnsEveryPhotoAndNoSegmentOwnsNone() {
        assertEquals(a.id, owner(0, a))
        assertEquals(a.id, owner(1_000_000, a))
        assertNull(owner(1_500))
    }

    @Test
    fun overlappingSegmentsGoToTheEarlierOne() {
        // not made by the recorder, but the rule still names one owner
        val late = seg(5, 1_500, 2_500)
        assertEquals(a.id, owner(1_800, late, a))
        assertEquals(late.id, owner(2_200, late, a))
    }

    @Test
    fun equalStartsGoToTheLowerId() {
        val twin = seg(9, 1_000, 2_000)
        assertEquals(a.id, owner(1_500, twin, a))
    }
}

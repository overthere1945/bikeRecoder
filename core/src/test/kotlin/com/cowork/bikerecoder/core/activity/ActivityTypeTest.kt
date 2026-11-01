package com.cowork.bikerecoder.core.activity

import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class ActivityTypeTest {

    private fun seg(id: Long, startedAt: Long, activity: ActivityType, movingMin: Int) = RideSegment(
        id = id,
        tripId = 1,
        startedAt = startedAt,
        endedAt = startedAt + movingMin * 60_000L,
        localDate = LocalDate.of(2026, 11, 1),
        totals = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = movingMin * 60_000L),
        activity = activity,
    )

    @Test
    fun storedRoundTrip() {
        assertEquals(listOf("BIKE", "WALK", "RUN", "HIKE"), ActivityType.entries.map { it.stored })
        for (t in ActivityType.entries) assertEquals(t, ActivityType.fromStored(t.stored))
    }

    @Test
    fun unknownStoredIsBike() {
        assertEquals(ActivityType.BIKE, ActivityType.fromStored("SKI"))
        assertEquals(ActivityType.BIKE, ActivityType.fromStored(null))
        assertEquals(ActivityType.BIKE, ActivityType.fromStored(""))
        assertEquals(ActivityType.BIKE, ActivityType.fromStored("run")) // 대소문자까지 정확히 맞아야 한다
    }

    @Test
    fun gpxTypes() {
        assertEquals("cycling", ActivityType.BIKE.gpxType)
        assertEquals("walking", ActivityType.WALK.gpxType)
        assertEquals("running", ActivityType.RUN.gpxType)
        assertEquals("hiking", ActivityType.HIKE.gpxType)
    }

    @Test
    fun segmentDefaultsToBike() {
        assertEquals(ActivityType.BIKE, RideSegment(1, 1, 0, 1, LocalDate.of(2026, 11, 1), SegmentTotals.ZERO).activity)
    }

    @Test
    fun mixOfNoSegmentsIsBike() {
        assertEquals(ActivityMix(ActivityType.BIKE, listOf(ActivityType.BIKE)), ActivityMix.of(emptyList()))
    }

    @Test
    fun mixDominantIsLongestSingleSegment() {
        val mix = ActivityMix.of(
            listOf(
                seg(1, 1_000, ActivityType.WALK, 20),
                seg(2, 2_000, ActivityType.WALK, 20),
                seg(3, 3_000, ActivityType.BIKE, 30),
            ),
        )
        assertEquals(ActivityType.BIKE, mix.dominant) // 걷기 합계(40분)가 더 길어도 한 구간으로는 자전거(30분)가 가장 길다
        assertEquals(listOf(ActivityType.BIKE, ActivityType.WALK), mix.badges)
    }

    @Test
    fun mixTieGoesToEarlier() {
        val later = seg(1, 5_000, ActivityType.RUN, 30)
        val earlier = seg(2, 1_000, ActivityType.HIKE, 30)
        val mix = ActivityMix.of(listOf(later, earlier)) // 목록 순서가 아니라 startedAt이 앞선 구간
        assertEquals(ActivityType.HIKE, mix.dominant)
        assertEquals(listOf(ActivityType.HIKE, ActivityType.RUN), mix.badges)
    }

    @Test
    fun mixBadgesCapAtThree() {
        val mix = ActivityMix.of(
            listOf(
                seg(1, 1_000, ActivityType.BIKE, 60),
                seg(2, 2_000, ActivityType.HIKE, 10),
                seg(3, 3_000, ActivityType.WALK, 30),
                seg(4, 4_000, ActivityType.RUN, 20),
            ),
        )
        assertEquals(ActivityType.BIKE, mix.dominant)
        assertEquals(listOf(ActivityType.BIKE, ActivityType.WALK, ActivityType.RUN), mix.badges)
    }

    @Test
    fun mixSingleTypeHasOneBadge() {
        val mix = ActivityMix.of(listOf(seg(1, 1_000, ActivityType.RUN, 10), seg(2, 2_000, ActivityType.RUN, 5)))
        assertEquals(ActivityMix(ActivityType.RUN, listOf(ActivityType.RUN)), mix)
    }

    @Test
    fun mixIgnoresEmptySegments() {
        val emptyLong = seg(1, 1_000, ActivityType.HIKE, 90).let { it.copy(totals = it.totals.copy(distanceM = 0.0)) }
        val real = seg(2, 2_000, ActivityType.RUN, 10)
        val mix = ActivityMix.of(listOf(emptyLong, real))
        assertEquals(ActivityMix(ActivityType.RUN, listOf(ActivityType.RUN)), mix) // 빈 구간(거리 0)은 대표·배지에 끼지 않는다
    }

    @Test
    fun mixOfOnlyEmptySegmentsFallsBackToAll() {
        val a = seg(1, 1_000, ActivityType.HIKE, 20).let { it.copy(totals = it.totals.copy(distanceM = 0.0)) }
        val b = seg(2, 2_000, ActivityType.RUN, 10).let { it.copy(totals = it.totals.copy(distanceM = 0.0)) }
        assertEquals(ActivityMix(ActivityType.HIKE, listOf(ActivityType.HIKE, ActivityType.RUN)), ActivityMix.of(listOf(a, b)))
    }

    @Test
    fun mixOfPartsFollowsTheSameRules() {
        assertEquals(ActivityMix(ActivityType.BIKE, listOf(ActivityType.BIKE)), ActivityMix.ofParts(emptyList()))
        val mix = ActivityMix.ofParts(
            listOf(
                ActivityType.WALK to 20 * 60_000L,
                ActivityType.WALK to 20 * 60_000L,
                ActivityType.BIKE to 30 * 60_000L,
            ),
        )
        assertEquals(ActivityMix(ActivityType.BIKE, listOf(ActivityType.BIKE, ActivityType.WALK)), mix)
    }
}

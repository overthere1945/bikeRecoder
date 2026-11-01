package com.cowork.bikerecoder.core.elevation

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class UsualSpeedTest {

    private fun seg(
        startedAt: Long,
        distanceM: Double,
        movingSec: Long,
        ended: Boolean = true,
        activity: ActivityType = ActivityType.BIKE,
    ) = RideSegment(
        id = startedAt,
        tripId = 1,
        startedAt = startedAt,
        endedAt = if (ended) startedAt + movingSec * 1_000 + 60_000 else null,
        localDate = LocalDate.of(2026, 10, 1),
        totals = SegmentTotals.ZERO.copy(distanceM = distanceM, movingTimeMs = movingSec * 1_000),
        activity = activity,
    )

    @Test
    fun noSegmentsIsDefault() {
        assertEquals(15.0 / 3.6, UsualSpeed.of(emptyList()), 1e-12)
        assertEquals(15.0 / 3.6, UsualSpeed.DEFAULT_MPS, 1e-12)
    }

    @Test
    fun shortSegmentsExcluded() {
        val segments = listOf(
            seg(1, 999.0, 10), // 1 km 미만
            seg(2, 5_000.0, 0), // 이동 시간 0
            seg(3, 2_000.0, 400),
        )
        assertEquals(5.0, UsualSpeed.of(segments), 1e-12)
    }

    @Test
    fun onlyTenMostRecent() {
        val recent = (2L..11L).map { seg(it * 1_000, 1_000.0, 200) } // 5 m/s
        val oldest = seg(1_000, 10_000.0, 100) // 100 m/s, 가장 오래됨
        assertEquals(5.0, UsualSpeed.of((recent + oldest).shuffled(java.util.Random(7))), 1e-12)
    }

    @Test
    fun weightedByTime() {
        val segments = listOf(seg(1, 1_000.0, 100), seg(2, 3_000.0, 1_000))
        assertEquals(4_000.0 / 1_100.0, UsualSpeed.of(segments), 1e-12)
    }

    @Test
    fun openSegmentExcluded() {
        val segments = listOf(seg(2, 10_000.0, 100, ended = false), seg(1, 2_000.0, 400))
        assertEquals(5.0, UsualSpeed.of(segments), 1e-12)
    }

    @Test
    fun usualSpeedIgnoresWalking() {
        val segments = listOf(
            seg(1, 9_000.0, 1_800, activity = ActivityType.BIKE), // 18 km/h
            seg(2, 5_000.0, 3_600, activity = ActivityType.WALK), // 5 km/h, 더 최근
            seg(3, 4_000.0, 1_000, activity = ActivityType.RUN),
            seg(4, 3_000.0, 3_600, activity = ActivityType.HIKE),
        )
        assertEquals(18.0 / 3.6, UsualSpeed.of(segments), 1e-12)
        // 자전거 구간이 없으면 기본값
        assertEquals(UsualSpeed.DEFAULT_MPS, UsualSpeed.of(segments.drop(1)), 1e-12)
    }
}

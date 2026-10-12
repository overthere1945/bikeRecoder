package com.cowork.bikerecoder.core.recording

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RideStoreTest {
    @Test
    fun `ride stats convert to segment totals dropping live fields`() {
        val stats = RideStats(
            distanceM = 1234.5,
            movingTimeMs = 60_000L,
            elapsedMs = 90_000L,
            currentSpeedMps = 4.0,
            avgSpeedMps = 5.0,
            maxSpeedMps = 9.0,
            altitudeM = 33.0,
            ascentM = 12.0,
            descentM = 3.0,
            minAltitudeM = 10.0,
            maxAltitudeM = 40.0,
        )

        assertEquals(
            SegmentTotals(1234.5, 60_000L, 90_000L, 9.0, 12.0, 3.0, 10.0, 40.0),
            stats.toTotals(),
        )
    }

    @Test
    fun `empty ride stats convert to the zero totals`() {
        assertEquals(SegmentTotals.ZERO, RideStats.ZERO.toTotals())
    }

    @Test
    fun `segment average speed is distance over moving time`() {
        assertEquals(5.0, SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 200_000).avgSpeedMps)
        assertEquals(0.0, SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 0).avgSpeedMps)
    }
}

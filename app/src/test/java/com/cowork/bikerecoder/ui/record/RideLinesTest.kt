package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.core.recording.RideStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RideLinesTest {

    @Test
    fun `summary lines`() {
        val stats = RideStats.ZERO.copy(
            currentSpeedMps = 5.0,
            distanceM = 3_010.0,
            movingTimeMs = 600_000,
            elapsedMs = 700_000,
            avgSpeedMps = 5.0,
        )

        assertEquals("18.0km/h · 3.01km · 0:10:00 · 평균 18.0", rideMapSummaryLine(stats))
        assertEquals("3.01km · 0:10:00 · 평균 18.0", rideSummaryLine(stats))
    }
}

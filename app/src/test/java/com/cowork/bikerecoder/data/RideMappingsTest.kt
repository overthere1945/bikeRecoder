package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RideMappingsTest {
    @Test
    fun `segment round trip keeps nulls and date`() {
        val segment = RideSegment(
            id = 3,
            tripId = 9,
            startedAt = 1_000L,
            endedAt = null,
            localDate = LocalDate.of(2026, 10, 12),
            totals = SegmentTotals(
                distanceM = 1234.5,
                movingTimeMs = 60_000L,
                elapsedMs = 90_000L,
                maxSpeedMps = 7.5,
                ascentM = 12.0,
                descentM = 3.0,
                minAltitudeM = null,
                maxAltitudeM = null,
            ),
        )

        val entity = segment.toEntity()

        assertEquals("2026-10-12", entity.localDate)
        assertNull(entity.endedAt)
        assertNull(entity.minAltitudeM)
        assertNull(entity.maxAltitudeM)
        assertEquals(segment, entity.toModel())
    }

    @Test
    fun `track point round trip`() {
        val point = TrackPoint(1_000L, GeoPoint(37.5, 127.0), altitudeM = null, speedMps = null, accuracyM = 5f)

        val entity = point.toEntity(segmentId = 7)

        assertEquals(7L, entity.segmentId)
        assertNull(entity.altitudeM)
        assertNull(entity.speedMps)
        assertEquals(point, entity.toModel())
    }
}

package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryFormatTest {

    private fun summary(type: TripType, dayCount: Int) = TripRideSummary(
        trip = Trip(1, type, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, 0L),
        firstDate = LocalDate.of(2026, 10, 12),
        dayCount = dayCount,
        distanceM = 32_100.0,
        movingTimeMs = 6_300_000,
        lastStartedAt = 0L,
    )

    @Test
    fun `card texts`() {
        assertEquals("10월 25일 (일)", cardDateText(LocalDate.of(2026, 10, 25)))
        assertEquals("당일", cardBadgeText(summary(TripType.SINGLE_DAY, 1)))
        assertEquals("여러 날 · 3일", cardBadgeText(summary(TripType.MULTI_DAY, 3)))
        assertEquals("여러 날 · 1일", cardBadgeText(summary(TripType.MULTI_DAY, 1)))
        // 32.1 km in 1 h 45 min = 18.3 km/h.
        assertEquals("32.10km · 1시간 45분 · 18.3km/h", cardLine2Text(summary(TripType.SINGLE_DAY, 1)))
        assertEquals("2026년 10월 · 3.45km · 2회", monthHeaderText(2026, 10, 3_450.0, 2))
    }

    @Test
    fun `segment row shows the Seoul start time`() {
        // 2026-10-12 07:05 UTC = 16:05 in Seoul.
        val startedAt = ZonedDateTime.of(2026, 10, 12, 7, 5, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val segment = RideSegment(
            id = 1,
            tripId = 1,
            startedAt = startedAt,
            endedAt = startedAt + 1,
            localDate = LocalDate.of(2026, 10, 12),
            totals = SegmentTotals.ZERO.copy(distanceM = 12_345.0, movingTimeMs = 3_000_000),
        )

        assertEquals("16:05 · 12.34km · 50분", segmentRowText(segment))
    }

    @Test
    fun `start and end markers`() {
        val a = GeoPoint(37.5, 127.0)
        val b = GeoPoint(37.6, 127.1)
        val c = GeoPoint(37.7, 127.2)

        val stops = startEndStops(listOf(a, b, c))

        assertEquals(listOf(a to false, c to true), stops.map { it.point to it.isDestination })
        assertEquals(emptyList<Any>(), startEndStops(emptyList()))
    }
}

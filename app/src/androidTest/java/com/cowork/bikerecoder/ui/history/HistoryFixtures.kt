package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.DayTotals
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.map.TileSource
import java.time.LocalDate

/** [pointsPerSegment] points per segment, 1 s and 0.001° apart; writes are not expected. */
internal class ReadOnlyRides(private val segments: List<RideSegment>, private val pointsPerSegment: Int = 2) : RideStore {
    override suspend fun segment(id: Long) = segments.firstOrNull { it.id == id }
    override suspend fun segments(tripId: Long) = segments.filter { it.tripId == tripId }
    override suspend fun points(segmentId: Long): List<TrackPoint> {
        val s = segment(segmentId) ?: return emptyList()
        return (0 until pointsPerSegment).map { i ->
            TrackPoint(s.startedAt + i * 1_000L, GeoPoint(37.5 + i * 0.001, 127.0), null, 5f, 5f)
        }
    }
    override suspend fun openSegments() = segments.filter { it.endedAt == null }
    override suspend fun openSegment(tripId: Long, startedAt: Long, localDate: LocalDate): Long = error("read only")
    override suspend fun appendPoints(segmentId: Long, points: List<TrackPoint>, totals: SegmentTotals) = error("read only")
    override suspend fun closeSegment(segmentId: Long, endedAt: Long, totals: SegmentTotals) = error("read only")
    override suspend fun dayTotals(tripId: Long, localDate: LocalDate): DayTotals = error("read only")
    override suspend fun tripSummaries(): List<TripRideSummary> = error("read only")
    override suspend fun deleteSegment(id: Long) = error("read only")
    override suspend fun deleteTrip(tripId: Long) = error("read only")
}

/** For screens whose map slot is replaced. */
internal object NoTiles : TileSource {
    override val styleUrl = "unused"
    override val attribution = ""
    override suspend fun styleJson(): String = error("the map is replaced in this test")
}

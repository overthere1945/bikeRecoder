package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.core.recording.DayTotals
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.location.PressureSource
import com.cowork.bikerecoder.nav.FakeTripStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.io.IOException
import java.time.LocalDate

/**
 * In-memory [RideStore] like RoomRideStore: [tripSummaries] joins [trips] (rows whose trip is gone are dropped) and
 * skips empty segments, [deleteTrip] removes the trip with its segments and points (the CASCADE). [clock] stamps
 * [appendCalls].
 */
class FakeRideStore(private val trips: FakeTripStore, private val clock: () -> Long = { 0L }) : RideStore {
    private val segmentsById = linkedMapOf<Long, RideSegment>()
    private val pointsBySegment = mutableMapOf<Long, MutableList<TrackPoint>>()
    private var nextId = 1L

    /** While true every [appendPoints] throws (nothing is stored) and is counted in [failedAppends]. */
    var failAppend = false
    var failedAppends = 0
        private set

    /** The next this many [appendPoints] calls throw (counted in [failedAppends]), then they work again. */
    var failNextAppends = 0

    /** Successful [appendPoints] calls: (clock time, totals). */
    val appendCalls = mutableListOf<Pair<Long, SegmentTotals>>()
    val deletedTrips = mutableListOf<Long>()
    val deletedSegments = mutableListOf<Long>()

    override suspend fun openSegment(tripId: Long, startedAt: Long, localDate: LocalDate): Long {
        val id = nextId++
        segmentsById[id] = RideSegment(id, tripId, startedAt, endedAt = null, localDate, SegmentTotals.ZERO)
        return id
    }

    override suspend fun appendPoints(segmentId: Long, points: List<TrackPoint>, totals: SegmentTotals) {
        if (failAppend || failNextAppends > 0) {
            if (failNextAppends > 0) failNextAppends--
            failedAppends++
            throw IOException("disk full")
        }
        val segment = segmentsById.getValue(segmentId)
        pointsBySegment.getOrPut(segmentId) { mutableListOf() } += points
        segmentsById[segmentId] = segment.copy(totals = totals)
        appendCalls += clock() to totals
    }

    override suspend fun closeSegment(segmentId: Long, endedAt: Long, totals: SegmentTotals) {
        segmentsById[segmentId] = segmentsById.getValue(segmentId).copy(endedAt = endedAt, totals = totals)
    }

    override suspend fun openSegments(): List<RideSegment> =
        segmentsById.values.filter { it.endedAt == null }.sortedByDescending { it.startedAt }

    override suspend fun segment(id: Long): RideSegment? = segmentsById[id]

    override suspend fun segments(tripId: Long): List<RideSegment> =
        segmentsById.values.filter { it.tripId == tripId }.sortedWith(compareBy({ it.startedAt }, { it.id }))

    override suspend fun points(segmentId: Long): List<TrackPoint> =
        pointsBySegment[segmentId].orEmpty().sortedBy { it.timeMillis }

    override suspend fun dayTotals(tripId: Long, localDate: LocalDate): DayTotals {
        val day = segmentsById.values.filter { it.tripId == tripId && it.localDate == localDate }
        return DayTotals(day.sumOf { it.totals.distanceM }, day.sumOf { it.totals.movingTimeMs })
    }

    override suspend fun tripSummaries(): List<TripRideSummary> =
        segmentsById.values.filterNot { it.isEmpty }.groupBy { it.tripId }
            .mapNotNull { (tripId, segments) ->
                val trip = trips.trips[tripId] ?: return@mapNotNull null
                TripRideSummary(
                    trip = trip,
                    firstDate = segments.minOf { it.localDate },
                    dayCount = segments.map { it.localDate }.distinct().size,
                    distanceM = segments.sumOf { it.totals.distanceM },
                    movingTimeMs = segments.sumOf { it.totals.movingTimeMs },
                    lastStartedAt = segments.maxOf { it.startedAt },
                )
            }
            .sortedWith(compareByDescending<TripRideSummary> { it.lastStartedAt }.thenByDescending { it.trip.id })

    override suspend fun deleteSegment(id: Long) {
        segmentsById.remove(id)
        pointsBySegment.remove(id)
        deletedSegments += id
    }

    override suspend fun deleteTrip(tripId: Long) {
        segmentsById.values.filter { it.tripId == tripId }.forEach {
            segmentsById.remove(it.id)
            pointsBySegment.remove(it.id)
        }
        trips.trips.remove(tripId)
        trips.stopsByTrip.remove(tripId)
        deletedTrips += tripId
    }
}

/** Emits what the test sends to [pressures]; [collecting] tells whether someone is collecting right now. */
class FakePressureSource : PressureSource {
    val values = MutableSharedFlow<Float>()
    var collecting = false
        private set

    override fun pressures(): Flow<Float> =
        values.onStart { collecting = true }.onCompletion { collecting = false }
}

package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.DayTotals
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.TripRideSummary
import java.time.LocalDate

class RoomRideStore(db: AppDatabase) : RideStore {
    private val dao = db.rideDao()
    private val tripDao = db.tripDao()

    override suspend fun openSegment(tripId: Long, startedAt: Long, localDate: LocalDate): Long =
        dao.insertSegment(
            RideSegment(
                id = 0,
                tripId = tripId,
                startedAt = startedAt,
                endedAt = null,
                localDate = localDate,
                totals = SegmentTotals.ZERO,
            ).toEntity(),
        )

    override suspend fun appendPoints(segmentId: Long, points: List<TrackPoint>, totals: SegmentTotals) =
        dao.appendPoints(segmentId, points.map { it.toEntity(segmentId) }, totals)

    override suspend fun closeSegment(segmentId: Long, endedAt: Long, totals: SegmentTotals) =
        dao.closeSegment(segmentId, endedAt, totals)

    override suspend fun openSegments(): List<RideSegment> = dao.openSegments().map { it.toModel() }

    override suspend fun segment(id: Long): RideSegment? = dao.segment(id)?.toModel()

    override suspend fun segments(tripId: Long): List<RideSegment> = dao.segments(tripId).map { it.toModel() }

    override suspend fun points(segmentId: Long): List<TrackPoint> = dao.points(segmentId).map { it.toModel() }

    override suspend fun dayTotals(tripId: Long, localDate: LocalDate): DayTotals {
        val row = dao.dayTotals(tripId, localDate.toString())
        return DayTotals(row.distanceM, row.movingTimeMs)
    }

    override suspend fun tripSummaries(): List<TripRideSummary> =
        dao.tripRideRows().mapNotNull { row ->
            val trip = tripDao.trip(row.tripId)?.toTrip() ?: return@mapNotNull null
            TripRideSummary(
                trip = trip,
                firstDate = LocalDate.parse(row.firstDate),
                dayCount = row.dayCount,
                distanceM = row.distanceM,
                movingTimeMs = row.movingTimeMs,
                lastStartedAt = row.lastStartedAt,
            )
        }

    override suspend fun deleteSegment(id: Long) = dao.deleteSegment(id)

    override suspend fun deleteTrip(tripId: Long) = tripDao.deleteTrip(tripId)
}

internal fun RideSegment.toEntity() = RideSegmentEntity(
    id = id,
    tripId = tripId,
    startedAt = startedAt,
    endedAt = endedAt,
    localDate = localDate.toString(),
    distanceM = totals.distanceM,
    movingTimeMs = totals.movingTimeMs,
    elapsedMs = totals.elapsedMs,
    maxSpeedMps = totals.maxSpeedMps,
    ascentM = totals.ascentM,
    descentM = totals.descentM,
    minAltitudeM = totals.minAltitudeM,
    maxAltitudeM = totals.maxAltitudeM,
)

internal fun RideSegmentEntity.toModel() = RideSegment(
    id = id,
    tripId = tripId,
    startedAt = startedAt,
    endedAt = endedAt,
    localDate = LocalDate.parse(localDate),
    totals = SegmentTotals(
        distanceM = distanceM,
        movingTimeMs = movingTimeMs,
        elapsedMs = elapsedMs,
        maxSpeedMps = maxSpeedMps,
        ascentM = ascentM,
        descentM = descentM,
        minAltitudeM = minAltitudeM,
        maxAltitudeM = maxAltitudeM,
    ),
)

internal fun TrackPoint.toEntity(segmentId: Long) = TrackPointEntity(
    id = 0,
    segmentId = segmentId,
    timeMillis = timeMillis,
    lat = point.lat,
    lon = point.lon,
    altitudeM = altitudeM,
    speedMps = speedMps,
    accuracyM = accuracyM,
)

internal fun TrackPointEntity.toModel() = TrackPoint(
    timeMillis = timeMillis,
    point = GeoPoint(lat, lon),
    altitudeM = altitudeM,
    speedMps = speedMps,
    accuracyM = accuracyM,
)

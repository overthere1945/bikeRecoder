package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType

class RoomTripStore(db: AppDatabase) : TripStore {
    private val dao = db.tripDao()

    override suspend fun activeTrip(): Trip? = dao.active()?.toTrip()

    override suspend fun trip(id: Long): Trip? = dao.trip(id)?.toTrip()

    override suspend fun insertTrip(trip: Trip): Long = dao.insertTrip(trip.toEntity())

    override suspend fun updateTrip(trip: Trip) = dao.updateTrip(trip.toEntity())

    override suspend fun stops(tripId: Long): List<TripStop> = dao.stops(tripId).map { it.toStop() }

    override suspend fun replaceStops(tripId: Long, stops: List<TripStop>) =
        dao.replaceStops(tripId, stops.map { it.toEntity() })

    override suspend fun markVisited(stopId: Long, at: Long) = dao.markVisited(stopId, at)
}

private fun Trip.toEntity() = TripEntity(
    id = id,
    type = type.name,
    status = status.name,
    profile = profile.name,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
    completedAt = completedAt,
)

private fun TripEntity.toTrip() = Trip(
    id = id,
    type = TripType.valueOf(type),
    status = TripStatus.valueOf(status),
    profile = RouteProfile.valueOf(profile),
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
    completedAt = completedAt,
)

private fun TripStop.toEntity() = WaypointEntity(
    id = id,
    tripId = tripId,
    order = order,
    name = name,
    lat = point.lat,
    lon = point.lon,
    isDestination = isDestination,
    visitedAt = visitedAt,
)

private fun WaypointEntity.toStop() = TripStop(
    id = id,
    tripId = tripId,
    order = order,
    name = name,
    point = GeoPoint(lat, lon),
    isDestination = isDestination,
    visitedAt = visitedAt,
)

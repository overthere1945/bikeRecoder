package com.cowork.bikerecoder.data

import androidx.room.withTransaction
import com.cowork.bikerecoder.core.importing.ImportStore
import com.cowork.bikerecoder.core.importing.ImportedRide
import com.cowork.bikerecoder.core.importing.TimeSpan
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room implementation of [ImportStore]: one imported ride = one trip + one segment + its points + a `trip_import` row. */
class RoomImportStore(private val db: AppDatabase) : ImportStore {
    private val importDao = db.importDao()
    private val rideDao = db.rideDao()
    private val tripDao = db.tripDao()

    /** Trip, segment, points and the import row go in one transaction, so a failure leaves no half ride behind. */
    override suspend fun saveImported(ride: ImportedRide): Long = db.withTransaction {
        val replayed = ride.ride
        val tripId = tripDao.insertTrip(
            Trip(
                id = 0,
                type = TripType.SINGLE_DAY,
                status = TripStatus.COMPLETED,
                profile = ride.profile,
                createdAt = replayed.startedAt,
                lastActiveAt = replayed.endedAt,
                completedAt = replayed.endedAt,
            ).toEntity(),
        )
        val segmentId = rideDao.insertSegment(
            RideSegment(
                id = 0,
                tripId = tripId,
                startedAt = replayed.startedAt,
                endedAt = replayed.endedAt,
                localDate = replayed.localDate,
                totals = replayed.totals,
            ).toEntity(),
        )
        rideDao.insertPoints(replayed.points.map { it.toEntity(segmentId) })
        importDao.insertImport(TripImportEntity(tripId, ride.fileName.take(MAX_FILE_NAME), ride.importedAt))
        tripId
    }

    override suspend fun segmentSpans(now: Long): List<TimeSpan> =
        importDao.segmentSpans(now).map { TimeSpan(it.spanStart, it.spanEnd) }

    override fun observeImported(): Flow<Map<Long, String>> =
        importDao.observeImported().map { rows -> rows.associate { it.tripId to it.fileName } }

    /** Deletes through the existing trip delete (CASCADE takes segments, points, photos, labels, media rows, import row). */
    override suspend fun deleteAllImported(): Int = db.withTransaction {
        val ids = importDao.importedTripIds()
        ids.forEach { tripDao.deleteTrip(it) }
        ids.size
    }

    private companion object {
        const val MAX_FILE_NAME = 200
    }
}

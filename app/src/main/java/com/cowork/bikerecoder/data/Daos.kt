package com.cowork.bikerecoder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.cowork.bikerecoder.core.recording.SegmentTotals
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TripDao {
    @Query("SELECT * FROM trip WHERE status = 'ACTIVE' ORDER BY lastActiveAt DESC LIMIT 1")
    abstract suspend fun active(): TripEntity?

    @Query("SELECT * FROM trip WHERE id = :id")
    abstract suspend fun trip(id: Long): TripEntity?

    @Insert
    abstract suspend fun insertTrip(trip: TripEntity): Long

    @Update
    abstract suspend fun updateTrip(trip: TripEntity)

    @Query("DELETE FROM trip WHERE id = :id")
    abstract suspend fun deleteTrip(id: Long)

    @Query("SELECT * FROM waypoint WHERE tripId = :tripId ORDER BY stop_order ASC")
    abstract suspend fun stops(tripId: Long): List<WaypointEntity>

    @Insert
    abstract suspend fun insertStops(stops: List<WaypointEntity>)

    @Query("DELETE FROM waypoint WHERE tripId = :tripId")
    abstract suspend fun deleteStops(tripId: Long)

    @Query("UPDATE waypoint SET visitedAt = :at WHERE id = :stopId")
    abstract suspend fun markVisited(stopId: Long, at: Long)

    /** 기존 경유지를 지우고 새로 넣는다. 들어오는 id는 무시되고 새로 생성된다. */
    @Transaction
    open suspend fun replaceStops(tripId: Long, stops: List<WaypointEntity>) {
        deleteStops(tripId)
        insertStops(stops.map { it.copy(id = 0, tripId = tripId) })
    }
}

@Dao
interface OfflineRegionRefDao {
    @Insert
    suspend fun insert(ref: OfflineRegionRefEntity): Long

    @Query("SELECT * FROM offline_region_ref WHERE tripId = :tripId ORDER BY id ASC")
    suspend fun forTrip(tripId: Long): List<OfflineRegionRefEntity>

    @Query("DELETE FROM offline_region_ref WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: Long)

    @Query("DELETE FROM offline_region_ref")
    suspend fun deleteAll()

    @Query("DELETE FROM offline_region_ref WHERE mapLibreRegionId = :regionId")
    suspend fun deleteByRegionId(regionId: Long)

    @Query("SELECT * FROM offline_region_ref ORDER BY id ASC")
    suspend fun all(): List<OfflineRegionRefEntity>
}

data class DayTotalsRow(val distanceM: Double, val movingTimeMs: Long)

data class TripRideRow(
    val tripId: Long,
    val firstDate: String,
    val dayCount: Int,
    val distanceM: Double,
    val movingTimeMs: Long,
    val lastStartedAt: Long,
)

@Dao
abstract class RideDao {
    @Insert
    abstract suspend fun insertSegment(segment: RideSegmentEntity): Long

    @Insert
    abstract suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query(
        "UPDATE ride_segment SET distanceM = :distanceM, movingTimeMs = :movingTimeMs, elapsedMs = :elapsedMs, " +
            "maxSpeedMps = :maxSpeedMps, ascentM = :ascentM, descentM = :descentM, " +
            "minAltitudeM = :minAltitudeM, maxAltitudeM = :maxAltitudeM WHERE id = :id",
    )
    abstract suspend fun updateTotals(
        id: Long,
        distanceM: Double,
        movingTimeMs: Long,
        elapsedMs: Long,
        maxSpeedMps: Double,
        ascentM: Double,
        descentM: Double,
        minAltitudeM: Double?,
        maxAltitudeM: Double?,
    )

    @Query("UPDATE ride_segment SET endedAt = :endedAt WHERE id = :id")
    abstract suspend fun setEndedAt(id: Long, endedAt: Long)

    /** 점 삽입과 합계 갱신을 한 트랜잭션으로 한다. */
    @Transaction
    open suspend fun appendPoints(segmentId: Long, points: List<TrackPointEntity>, totals: SegmentTotals) {
        insertPoints(points)
        updateTotals(
            segmentId, totals.distanceM, totals.movingTimeMs, totals.elapsedMs, totals.maxSpeedMps,
            totals.ascentM, totals.descentM, totals.minAltitudeM, totals.maxAltitudeM,
        )
    }

    @Transaction
    open suspend fun closeSegment(segmentId: Long, endedAt: Long, totals: SegmentTotals) {
        updateTotals(
            segmentId, totals.distanceM, totals.movingTimeMs, totals.elapsedMs, totals.maxSpeedMps,
            totals.ascentM, totals.descentM, totals.minAltitudeM, totals.maxAltitudeM,
        )
        setEndedAt(segmentId, endedAt)
    }

    @Query("SELECT * FROM ride_segment WHERE endedAt IS NULL ORDER BY startedAt DESC")
    abstract suspend fun openSegments(): List<RideSegmentEntity>

    @Query("SELECT * FROM ride_segment WHERE id = :id")
    abstract suspend fun segment(id: Long): RideSegmentEntity?

    @Query("SELECT * FROM ride_segment WHERE tripId = :tripId ORDER BY startedAt ASC, id ASC")
    abstract suspend fun segments(tripId: Long): List<RideSegmentEntity>

    @Query("SELECT * FROM track_point WHERE segmentId = :segmentId ORDER BY timeMillis ASC, id ASC")
    abstract suspend fun points(segmentId: Long): List<TrackPointEntity>

    @Query(
        "SELECT COALESCE(SUM(distanceM), 0.0) AS distanceM, COALESCE(SUM(movingTimeMs), 0) AS movingTimeMs " +
            "FROM ride_segment WHERE tripId = :tripId AND localDate = :localDate",
    )
    abstract suspend fun dayTotals(tripId: Long, localDate: String): DayTotalsRow

    @Query(
        "SELECT tripId, MIN(localDate) AS firstDate, COUNT(DISTINCT localDate) AS dayCount, " +
            "SUM(distanceM) AS distanceM, SUM(movingTimeMs) AS movingTimeMs, MAX(startedAt) AS lastStartedAt " +
            // Empty segments (no distance, RideSegment.isEmpty) are not part of the history.
            "FROM ride_segment WHERE distanceM > 0 GROUP BY tripId ORDER BY lastStartedAt DESC, tripId DESC",
    )
    abstract suspend fun tripRideRows(): List<TripRideRow>

    @Query("DELETE FROM ride_segment WHERE id = :id")
    abstract suspend fun deleteSegment(id: Long)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_place")
    fun observeAll(): Flow<List<FavoritePlaceEntity>>

    @Query("SELECT * FROM favorite_place")
    suspend fun all(): List<FavoritePlaceEntity>

    @Insert
    suspend fun insert(entity: FavoritePlaceEntity): Long

    @Update
    suspend fun update(entity: FavoritePlaceEntity)

    @Query("DELETE FROM favorite_place WHERE id = :id")
    suspend fun delete(id: Long)
}

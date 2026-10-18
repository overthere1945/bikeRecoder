package com.cowork.bikerecoder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

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

package com.cowork.bikerecoder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** enum 필드(type/status/profile)는 enum의 name 문자열로 저장한다. */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val type: String,
    val status: String,
    val profile: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "waypoint",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tripId")],
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val tripId: Long,
    // "order"는 SQL 예약어이므로 컬럼명을 따로 둔다.
    @ColumnInfo(name = "stop_order") val order: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isDestination: Boolean,
    val visitedAt: Long?,
)

/** kind는 "CORRIDOR" 또는 "OVERVIEW". */
@Entity(tableName = "offline_region_ref", indices = [Index("tripId")])
data class OfflineRegionRefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val tripId: Long,
    val mapLibreRegionId: Long,
    val kind: String,
    val createdAt: Long,
)

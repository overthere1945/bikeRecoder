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

/** localDate는 ISO(yyyy-MM-dd) 문자열. endedAt이 null이면 열린 구간이다. */
@Entity(
    tableName = "ride_segment",
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
data class RideSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val tripId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val localDate: String,
    val distanceM: Double,
    val movingTimeMs: Long,
    val elapsedMs: Long,
    val maxSpeedMps: Double,
    val ascentM: Double,
    val descentM: Double,
    val minAltitudeM: Double?,
    val maxAltitudeM: Double?,
)

@Entity(
    tableName = "track_point",
    foreignKeys = [
        ForeignKey(
            entity = RideSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("segmentId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val segmentId: Long,
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val accuracyM: Float,
)

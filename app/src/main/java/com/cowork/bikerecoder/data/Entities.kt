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

/** kind는 FavoriteKind의 name. */
@Entity(tableName = "favorite_place")
data class FavoritePlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val kind: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String?,
    val createdAt: Long,
)

/** positionSource는 PhotoPositionSource의 name. (segmentId, mediaId)는 유일하다. */
@Entity(
    tableName = "ride_photo",
    foreignKeys = [
        ForeignKey(
            entity = RideSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["segmentId", "mediaId"], unique = true)],
)
data class RidePhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val segmentId: Long,
    val mediaId: Long,
    val takenAt: Long,
    val lat: Double,
    val lon: Double,
    val positionSource: String,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean,
)

/** hrStatus는 HrStatus의 name. */
@Entity(
    tableName = "segment_extras",
    foreignKeys = [
        ForeignKey(
            entity = RideSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SegmentExtrasEntity(
    @PrimaryKey val segmentId: Long,
    val photosScannedAt: Long?,
    val photoScanDone: Boolean,
    val hrStatus: String,
    val hrFirstTriedAt: Long?,
    val hrLastTriedAt: Long?,
    val hrDetailed: Boolean,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val calories: Double?,
    val simplifiedDone: Boolean,
)

@Entity(
    tableName = "heart_rate_sample",
    foreignKeys = [
        ForeignKey(
            entity = RideSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["segmentId", "timeMillis"])],
)
data class HeartRateSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val segmentId: Long,
    val timeMillis: Long,
    val bpm: Int,
)

/** kind는 MediaKind의 name, uri는 갤러리(MediaStore) 항목, optionsJson은 만들 때의 옵션(음악 제외). */
@Entity(
    tableName = "trip_media",
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
data class TripMediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val tripId: Long,
    val kind: String,
    val uri: String,
    val createdAt: Long,
    val optionsJson: String,
)

/**
 * points는 PolylineCodec 문자열, localDate는 ISO(yyyy-MM-dd).
 * 경계 상자(min/max lat/lon)는 점이 없으면 null.
 */
@Entity(
    tableName = "segment_route_simplified",
    foreignKeys = [
        ForeignKey(
            entity = RideSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SegmentRouteSimplifiedEntity(
    @PrimaryKey val segmentId: Long,
    val localDate: String,
    val distanceM: Double,
    val movingTimeMs: Long,
    val points: String,
    val minLat: Double?,
    val minLon: Double?,
    val maxLat: Double?,
    val maxLon: Double?,
)

/** 여행 이름표(사용자 제목, 알아낸 지역). `trip` 표와 따로 둬서 여행 상태 저장이 덮어쓰지 못한다. 여행을 지우면 같이 지워진다. */
@Entity(
    tableName = "trip_label",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TripLabelEntity(
    @PrimaryKey val tripId: Long,
    val customTitle: String?,
    val regionName: String?,
    /** The share text the user edited, and the stamp of the auto text it was written against (both null = none). */
    val shareText: String? = null,
    val shareStamp: String? = null,
)

package com.cowork.bikerecoder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    /** [TripDeleteCondition.NO_SEGMENTS][com.cowork.bikerecoder.core.recording.TripDeleteCondition]; rows deleted. */
    @Query(
        "DELETE FROM trip WHERE id = :id AND status = 'COMPLETED' " +
            "AND NOT EXISTS (SELECT 1 FROM ride_segment WHERE tripId = :id)",
    )
    abstract suspend fun deleteCompletedWithoutSegments(id: Long): Int

    /** [TripDeleteCondition.NOT_RECORDING][com.cowork.bikerecoder.core.recording.TripDeleteCondition]; rows deleted. */
    @Query(
        "DELETE FROM trip WHERE id = :id AND status = 'COMPLETED' " +
            "AND NOT EXISTS (SELECT 1 FROM ride_segment WHERE tripId = :id AND endedAt IS NULL)",
    )
    abstract suspend fun deleteCompletedNotRecording(id: Long): Int

    /** [TripDeleteCondition.SHORT_NOT_RECORDING][com.cowork.bikerecoder.core.recording.TripDeleteCondition]; rows deleted. */
    @Query(
        "DELETE FROM trip WHERE id = :id AND status = 'COMPLETED' " +
            "AND NOT EXISTS (SELECT 1 FROM ride_segment WHERE tripId = :id AND endedAt IS NULL) " +
            "AND EXISTS (SELECT 1 FROM ride_segment WHERE tripId = :id) " +
            "AND (SELECT COALESCE(SUM(distanceM), 0) FROM ride_segment WHERE tripId = :id) < :maxDistanceM",
    )
    abstract suspend fun deleteShortCompletedNotRecording(id: Long, maxDistanceM: Double): Int

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

    /** Trips with segments that all have no distance (none in [tripRideRows]). */
    @Query("SELECT tripId FROM ride_segment GROUP BY tripId HAVING MAX(distanceM) <= 0 ORDER BY tripId")
    abstract suspend fun emptyTripIds(): List<Long>
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

@Dao
abstract class PostDao {
    /**
     * extras가 없거나 할 일이 남은 끝난 구간, 최근 시작 순. FAILED는 max(구간 끝, 처음 읽은 시각) + [giveUpMs] 안일 때만
     * (판정 R14, `HrRetryPolicy.failedRetryUntil`과 같은 식). 자세하지 않은 READY는 구간 끝 + [giveUpMs] 안일 때만
     * (스펙 §6.1). `HrRetryPolicy.shouldTry`와 같은 조건.
     */
    @Query(
        "SELECT s.* FROM ride_segment s LEFT JOIN segment_extras e ON e.segmentId = s.id " +
            "WHERE s.endedAt IS NOT NULL AND (e.segmentId IS NULL OR e.photoScanDone = 0 OR e.simplifiedDone = 0 " +
            "OR e.hrStatus = 'PENDING' OR (e.hrStatus = 'FAILED' " +
            "AND :now <= MAX(s.endedAt, COALESCE(e.hrFirstTriedAt, s.endedAt)) + :giveUpMs) " +
            "OR (e.hrStatus = 'READY' AND e.hrDetailed = 0 AND :now <= s.endedAt + :giveUpMs)) " +
            "ORDER BY s.startedAt DESC, s.id DESC",
    )
    abstract suspend fun segmentsNeedingWork(now: Long, giveUpMs: Long): List<RideSegmentEntity>

    @Query("SELECT * FROM segment_extras WHERE segmentId = :segmentId")
    abstract suspend fun extras(segmentId: Long): SegmentExtrasEntity?

    @Query("SELECT * FROM segment_extras WHERE segmentId IN (:segmentIds)")
    abstract fun observeExtras(segmentIds: List<Long>): Flow<List<SegmentExtrasEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveExtras(extras: SegmentExtrasEntity)

    @Query("SELECT * FROM ride_photo WHERE segmentId IN (:segmentIds) ORDER BY takenAt ASC, id ASC")
    abstract fun observePhotos(segmentIds: List<Long>): Flow<List<RidePhotoEntity>>

    /** 같은 (segmentId, mediaId)가 있으면 건너뛴다. 외래 키 위반은 IGNORE와 상관없이 실패한다. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPhotos(photos: List<RidePhotoEntity>)

    @Query("UPDATE ride_photo SET hidden = :hidden WHERE id = :photoId")
    abstract suspend fun setHidden(photoId: Long, hidden: Boolean)

    @Query("DELETE FROM ride_photo WHERE mediaId IN (:mediaIds)")
    abstract suspend fun deletePhotosByMediaId(mediaIds: List<Long>)

    @Query("DELETE FROM ride_photo WHERE segmentId = :segmentId AND mediaId IN (:mediaIds)")
    abstract suspend fun deletePhotos(segmentId: Long, mediaIds: List<Long>)

    @Query("DELETE FROM heart_rate_sample WHERE segmentId = :segmentId")
    abstract suspend fun deleteSamples(segmentId: Long)

    @Insert
    abstract suspend fun insertSamples(samples: List<HeartRateSampleEntity>)

    @Transaction
    open suspend fun replaceSamples(segmentId: Long, samples: List<HeartRateSampleEntity>) {
        deleteSamples(segmentId)
        insertSamples(samples)
    }

    @Query("SELECT * FROM heart_rate_sample WHERE segmentId = :segmentId ORDER BY timeMillis ASC, id ASC")
    abstract suspend fun samples(segmentId: Long): List<HeartRateSampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSimplified(route: SegmentRouteSimplifiedEntity)

    @Query("SELECT * FROM segment_route_simplified ORDER BY localDate ASC, segmentId ASC")
    abstract suspend fun simplifiedRoutes(): List<SegmentRouteSimplifiedEntity>

    /**
     * 기록 목록 카드(스펙 §6.11): 빈 구간이 아닌 구간마다 단순화 경로, 숨기지 않은 사진 수, READY 심박을 한 번에(바인드 변수
     * 없음). 여행 id·시작 순.
     */
    @Query(
        "SELECT s.id AS segmentId, s.tripId AS tripId, s.movingTimeMs AS movingTimeMs, r.points AS points, " +
            "(SELECT COUNT(*) FROM ride_photo p WHERE p.segmentId = s.id AND p.hidden = 0) AS photoCount, " +
            "CASE WHEN e.hrStatus = 'READY' THEN e.avgBpm END AS avgBpm, " +
            "CASE WHEN e.hrStatus = 'READY' THEN e.maxBpm END AS maxBpm, " +
            "COALESCE(e.hrDetailed, 0) AS hrDetailed " +
            "FROM ride_segment s " +
            "LEFT JOIN segment_route_simplified r ON r.segmentId = s.id " +
            "LEFT JOIN segment_extras e ON e.segmentId = s.id " +
            "WHERE s.distanceM > 0 ORDER BY s.tripId ASC, s.startedAt ASC, s.id ASC",
    )
    abstract suspend fun segmentCards(): List<SegmentCardRow>
}

/** [points]: PolylineCodec 문자열(경로가 없으면 null). [avgBpm]·[maxBpm]: READY일 때만. */
data class SegmentCardRow(
    val segmentId: Long,
    val tripId: Long,
    val movingTimeMs: Long,
    val points: String?,
    val photoCount: Int,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val hrDetailed: Boolean,
)

@Dao
interface TripMediaDao {
    @Query("SELECT * FROM trip_media WHERE tripId = :tripId ORDER BY createdAt DESC, id DESC")
    fun observe(tripId: Long): Flow<List<TripMediaEntity>>

    @Query("SELECT * FROM trip_media WHERE kind = 'VIDEO' ORDER BY createdAt DESC, id DESC")
    fun observeVideos(): Flow<List<TripMediaEntity>>

    @Insert
    suspend fun insert(media: TripMediaEntity): Long

    @Query("DELETE FROM trip_media WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
abstract class TripLabelDao {
    @Query("SELECT * FROM trip_label WHERE tripId = :tripId")
    abstract suspend fun label(tripId: Long): TripLabelEntity?

    @Query("SELECT * FROM trip_label")
    abstract fun observeAll(): Flow<List<TripLabelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsert(entity: TripLabelEntity)

    /** 읽고 쓰는 사이에 다른 칸이 바뀌지 않도록 한 트랜잭션으로 한다. */
    @Transaction
    open suspend fun setCustomTitle(tripId: Long, title: String?) {
        val current = label(tripId) ?: TripLabelEntity(tripId, null, null)
        upsert(current.copy(customTitle = title))
    }

    @Transaction
    open suspend fun setRegionName(tripId: Long, name: String) {
        val current = label(tripId) ?: TripLabelEntity(tripId, null, null)
        upsert(current.copy(regionName = name))
    }

    /** A null [text] clears both share columns, whatever [stamp] is. */
    @Transaction
    open suspend fun setShareText(tripId: Long, text: String?, stamp: String?) {
        val current = label(tripId) ?: TripLabelEntity(tripId, null, null)
        upsert(current.copy(shareText = text, shareStamp = if (text == null) null else stamp))
    }
}

/** 끝난 구간 전부(거르지 않음). 통계 화면이 구간이 바뀔 때마다 다시 받는다. */
@Dao
interface RideStatsDao {
    @Query(
        "SELECT id AS segmentId, tripId, startedAt, localDate, distanceM, movingTimeMs, ascentM, endedAt " +
            "FROM ride_segment WHERE endedAt IS NOT NULL ORDER BY startedAt, id",
    )
    fun observeEnded(): Flow<List<RideStatRow>>
}

data class RideStatRow(
    val segmentId: Long,
    val tripId: Long,
    val startedAt: Long,
    val localDate: String,
    val distanceM: Double,
    val movingTimeMs: Long,
    val ascentM: Double,
    val endedAt: Long,
)

data class ImportedNameRow(val tripId: Long, val fileName: String)

data class SegmentSpanRow(val spanStart: Long, val spanEnd: Long)

@Dao
interface ImportDao {
    @Insert
    suspend fun insertImport(row: TripImportEntity)

    @Query("SELECT tripId, fileName FROM trip_import")
    fun observeImported(): Flow<List<ImportedNameRow>>

    /**
     * Deletes the imported trips (those with a `trip_import` row) that are COMPLETED and have no open segment; CASCADE
     * takes their segments, points, photos, labels, media and import rows. A trip without a `trip_import` row is never
     * touched. Rows deleted.
     */
    @Query(
        "DELETE FROM trip WHERE id IN (SELECT tripId FROM trip_import) AND status = 'COMPLETED' " +
            "AND NOT EXISTS (SELECT 1 FROM ride_segment WHERE ride_segment.tripId = trip.id AND ride_segment.endedAt IS NULL)",
    )
    suspend fun deleteCompletedImportedTrips(): Int

    /** 모든 구간의 시간 구간. 열린 구간은 끝을 [now]로 본다. */
    @Query("SELECT startedAt AS spanStart, COALESCE(endedAt, :now) AS spanEnd FROM ride_segment")
    suspend fun segmentSpans(now: Long): List<SegmentSpanRow>
}

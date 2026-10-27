package com.cowork.bikerecoder.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HeartRateStats
import com.cowork.bikerecoder.core.post.HrRetryPolicy
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.post.PolylineCodec
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.RidePhoto
import com.cowork.bikerecoder.post.SegmentCard
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.post.SegmentGone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** [log]: simplified routes that cannot be read; [compute]: where they are decoded (off the main thread). */
class RoomPostStore(
    private val db: AppDatabase,
    private val log: WarnLog = androidWarnLog("RoomPostStore"),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : PostStore {
    private val dao = db.postDao()

    override suspend fun segmentsNeedingWork(now: Long): List<RideSegment> =
        dao.segmentsNeedingWork(now, HrRetryPolicy.GIVE_UP_MS).map { it.toModel() }

    override suspend fun extras(segmentId: Long): SegmentExtras? = dao.extras(segmentId)?.toModel()

    override fun observeExtras(segmentIds: List<Long>): Flow<List<SegmentExtras>> =
        dao.observeExtras(segmentIds).map { list -> list.map { it.toModel() } }

    override suspend fun saveExtras(extras: SegmentExtras) =
        writing(extras.segmentId) { dao.saveExtras(extras.toEntity()) }

    override fun observePhotos(segmentIds: List<Long>): Flow<List<RidePhoto>> =
        dao.observePhotos(segmentIds).map { list -> list.map { it.toModel() } }

    override suspend fun upsertPhotos(segmentId: Long, photos: List<PlacedPhoto>) {
        if (photos.isEmpty()) return
        writing(segmentId) { dao.insertPhotos(photos.map { it.toEntity(segmentId) }) }
    }

    override suspend fun setHidden(photoId: Long, hidden: Boolean) = dao.setHidden(photoId, hidden)

    override suspend fun deletePhotosByMediaId(mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        // API 30의 SQLite는 바인드 변수를 999개까지만 받는다.
        db.withTransaction {
            mediaIds.distinct().chunked(MAX_BIND_ARGS).forEach { dao.deletePhotosByMediaId(it) }
        }
    }

    override suspend fun deletePhotos(segmentId: Long, mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        db.withTransaction {
            mediaIds.distinct().chunked(MAX_BIND_ARGS).forEach { dao.deletePhotos(segmentId, it) }
        }
    }

    override suspend fun replaceSamples(segmentId: Long, samples: List<HeartRateSample>) =
        writing(segmentId) { dao.replaceSamples(segmentId, samples.map { it.toEntity(segmentId) }) }

    override suspend fun samples(segmentId: Long): List<HeartRateSample> = dao.samples(segmentId).map { it.toModel() }

    override suspend fun saveSimplified(segmentId: Long, entry: CumulativeEntry) =
        writing(segmentId) { dao.saveSimplified(entry.toEntity(segmentId)) }

    /** A route that cannot be read is left out (and reported), not the whole list. */
    override suspend fun cumulativeEntries(): List<CumulativeEntry> {
        val rows = dao.simplifiedRoutes()
        return withContext(compute) { rows.toEntries(log) }
    }

    /** One query for every listed segment; an unreadable route is left out (and reported), not the segment. */
    override suspend fun segmentCards(): List<SegmentCard> {
        val rows = dao.segmentCards()
        return withContext(compute) { rows.map { it.toModel(log) } }
    }

    /** 구간 행이 없어 외래 키가 막으면 [SegmentGone]. */
    private suspend inline fun <T> writing(segmentId: Long, block: () -> T): T =
        try {
            block()
        } catch (e: SQLiteConstraintException) {
            throw SegmentGone(segmentId, e)
        }

    private companion object {
        const val MAX_BIND_ARGS = 500
    }
}

internal fun PlacedPhoto.toEntity(segmentId: Long) = RidePhotoEntity(
    id = 0,
    segmentId = segmentId,
    mediaId = mediaId,
    takenAt = takenAt,
    lat = point.lat,
    lon = point.lon,
    positionSource = source.name,
    hidden = false,
)

internal fun RidePhotoEntity.toModel() = RidePhoto(
    id = id,
    segmentId = segmentId,
    mediaId = mediaId,
    takenAt = takenAt,
    point = GeoPoint(lat, lon),
    source = PhotoPositionSource.valueOf(positionSource),
    hidden = hidden,
)

internal fun SegmentExtras.toEntity() = SegmentExtrasEntity(
    segmentId = segmentId,
    photosScannedAt = photosScannedAt,
    photoScanDone = photoScanDone,
    hrStatus = hrStatus.name,
    hrFirstTriedAt = hrFirstTriedAt,
    hrLastTriedAt = hrLastTriedAt,
    hrDetailed = hrDetailed,
    avgBpm = avgBpm,
    maxBpm = maxBpm,
    calories = calories,
    simplifiedDone = simplifiedDone,
)

internal fun SegmentExtrasEntity.toModel() = SegmentExtras(
    segmentId = segmentId,
    photosScannedAt = photosScannedAt,
    photoScanDone = photoScanDone,
    hrStatus = HrStatus.valueOf(hrStatus),
    hrFirstTriedAt = hrFirstTriedAt,
    hrLastTriedAt = hrLastTriedAt,
    hrDetailed = hrDetailed,
    avgBpm = avgBpm,
    maxBpm = maxBpm,
    calories = calories,
    simplifiedDone = simplifiedDone,
)

internal fun HeartRateSample.toEntity(segmentId: Long) =
    HeartRateSampleEntity(id = 0, segmentId = segmentId, timeMillis = timeMillis, bpm = bpm)

internal fun HeartRateSampleEntity.toModel() = HeartRateSample(timeMillis = timeMillis, bpm = bpm)

internal fun CumulativeEntry.toEntity(segmentId: Long) = SegmentRouteSimplifiedEntity(
    segmentId = segmentId,
    localDate = localDate.toString(),
    distanceM = distanceM,
    movingTimeMs = movingTimeMs,
    points = PolylineCodec.encode(points),
    minLat = points.minOfOrNull { it.lat },
    minLon = points.minOfOrNull { it.lon },
    maxLat = points.maxOfOrNull { it.lat },
    maxLon = points.maxOfOrNull { it.lon },
)

/** The rows as entries, in order; a row whose points or date cannot be read is left out and reported to [log]. */
internal fun List<SegmentRouteSimplifiedEntity>.toEntries(log: WarnLog): List<CumulativeEntry> = mapNotNull { row ->
    try {
        row.toModel()
    } catch (e: IllegalArgumentException) {
        log.warn("Skipping the unreadable simplified route of segment ${row.segmentId}", e)
        null
    } catch (e: DateTimeParseException) {
        log.warn("Skipping the simplified route of segment ${row.segmentId} with an unreadable date", e)
        null
    }
}

internal fun SegmentCardRow.toModel(log: WarnLog) = SegmentCard(
    segmentId = segmentId,
    tripId = tripId,
    movingTimeMs = movingTimeMs,
    route = points?.let { encoded ->
        try {
            PolylineCodec.decode(encoded)
        } catch (e: IllegalArgumentException) {
            log.warn("Skipping the unreadable simplified route of segment $segmentId", e)
            null
        }
    },
    photoCount = photoCount,
    heartRate = if (avgBpm != null && maxBpm != null) HeartRateStats(avgBpm, maxBpm, hrDetailed) else null,
)

internal fun SegmentRouteSimplifiedEntity.toModel() = CumulativeEntry(
    segmentId = segmentId,
    localDate = LocalDate.parse(localDate),
    distanceM = distanceM,
    movingTimeMs = movingTimeMs,
    points = PolylineCodec.decode(points),
)

package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HeartRateStats
import com.cowork.bikerecoder.core.post.HrRetryPolicy
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.recording.RideSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// Shared by the JVM tests (src/test) and the device tests (src/androidTest). Thread-safe.

/**
 * In-memory [PostStore] like RoomPostStore. [segments] are the ride segments that exist (the ride_segment table):
 * writes for a segment not among them throw [SegmentGone] (the foreign key). [beforeList] runs at the start of every
 * [segmentsNeedingWork] (a hook to hold or count runs). [segmentCardReads] counts [segmentCards] calls; while
 * [failSegmentCards] they throw.
 */
class FakePostStore(private val segments: suspend () -> List<RideSegment>) : PostStore {
    val extrasById = MutableStateFlow<Map<Long, SegmentExtras>>(emptyMap())
    val photos = MutableStateFlow<List<RidePhoto>>(emptyList())
    val samplesById: MutableMap<Long, List<HeartRateSample>> = ConcurrentHashMap()
    val simplified: MutableMap<Long, CumulativeEntry> = ConcurrentHashMap()
    private val nextPhotoId = AtomicLong(1L)

    @Volatile var beforeList: suspend () -> Unit = {}

    @Volatile var failSegmentCards = false
    private val cardReads = AtomicInteger()
    val segmentCardReads: Int get() = cardReads.get()

    override suspend fun segmentsNeedingWork(now: Long): List<RideSegment> {
        beforeList()
        val extras = extrasById.value
        return segments().filter { s ->
            val endedAt = s.endedAt ?: return@filter false
            val e = extras[s.id] ?: return@filter true
            !e.photoScanDone || !e.simplifiedDone ||
                HrRetryPolicy.shouldTry(e.hrStatus, e.hrDetailed, endedAt, e.hrFirstTriedAt, now)
        }.sortedWith(compareByDescending<RideSegment> { it.startedAt }.thenByDescending { it.id })
    }

    override suspend fun extras(segmentId: Long): SegmentExtras? = extrasById.value[segmentId]

    override fun observeExtras(segmentIds: List<Long>): Flow<List<SegmentExtras>> =
        extrasById.map { all -> segmentIds.mapNotNull { all[it] } }

    override suspend fun saveExtras(extras: SegmentExtras) {
        requireSegment(extras.segmentId)
        extrasById.update { it + (extras.segmentId to extras) }
    }

    override fun observePhotos(segmentIds: List<Long>): Flow<List<RidePhoto>> =
        photos.map { all -> all.filter { it.segmentId in segmentIds }.sortedWith(compareBy({ it.takenAt }, { it.id })) }

    override suspend fun upsertPhotos(segmentId: Long, photos: List<PlacedPhoto>) {
        if (photos.isEmpty()) return
        requireSegment(segmentId)
        this.photos.update { current ->
            val have = current.filter { it.segmentId == segmentId }.mapTo(HashSet()) { it.mediaId }
            current + photos.filter { it.mediaId !in have }.distinctBy { it.mediaId }.map {
                RidePhoto(nextPhotoId.getAndIncrement(), segmentId, it.mediaId, it.takenAt, it.point, it.source, hidden = false)
            }
        }
    }

    override suspend fun setHidden(photoId: Long, hidden: Boolean) {
        photos.update { all -> all.map { if (it.id == photoId) it.copy(hidden = hidden) else it } }
    }

    override suspend fun deletePhotosByMediaId(mediaIds: Collection<Long>) {
        photos.update { all -> all.filterNot { it.mediaId in mediaIds } }
    }

    override suspend fun deletePhotos(segmentId: Long, mediaIds: Collection<Long>) {
        photos.update { all -> all.filterNot { it.segmentId == segmentId && it.mediaId in mediaIds } }
    }

    override suspend fun replaceSamples(segmentId: Long, samples: List<HeartRateSample>) {
        requireSegment(segmentId)
        samplesById[segmentId] = samples
    }

    override suspend fun samples(segmentId: Long): List<HeartRateSample> =
        samplesById[segmentId].orEmpty().sortedBy { it.timeMillis }

    override suspend fun saveSimplified(segmentId: Long, entry: CumulativeEntry) {
        requireSegment(segmentId)
        simplified[segmentId] = entry.copy(segmentId = segmentId)
    }

    override suspend fun cumulativeEntries(): List<CumulativeEntry> =
        simplified.values.sortedWith(compareBy({ it.localDate }, { it.segmentId }))

    override suspend fun segmentCards(): List<SegmentCard> {
        cardReads.incrementAndGet()
        if (failSegmentCards) throw IOException("disk I/O error")
        val extras = extrasById.value
        val shown = photos.value.filterNot { it.hidden }.groupingBy { it.segmentId }.eachCount()
        return segments().filter { it.totals.distanceM > 0.0 }
            .sortedWith(compareBy({ it.tripId }, { it.startedAt }, { it.id }))
            .map { s ->
                val e = extras[s.id]?.takeIf { it.hrStatus == HrStatus.READY }
                val avg = e?.avgBpm
                val max = e?.maxBpm
                SegmentCard(
                    segmentId = s.id,
                    tripId = s.tripId,
                    movingTimeMs = s.totals.movingTimeMs,
                    route = simplified[s.id]?.points,
                    photoCount = shown[s.id] ?: 0,
                    heartRate = if (avg != null && max != null) HeartRateStats(avg, max, e.hrDetailed) else null,
                )
            }
    }

    private suspend fun requireSegment(segmentId: Long) {
        if (segments().none { it.id == segmentId }) throw SegmentGone(segmentId)
    }
}

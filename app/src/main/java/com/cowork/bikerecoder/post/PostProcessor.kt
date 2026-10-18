package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.HrRetryPolicy
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPlacement
import com.cowork.bikerecoder.core.post.RouteSimplifier
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TrackPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 끝난 구간의 후처리(스펙 §1.1): 사진 찾기, 심박 읽기, 누적 지도용 단순화 경로. 앱이 화면에 있을 때만 [request]로 돌린다.
 *
 * 한 번에 한 바퀴만 돈다([runLock]). 실행 중에 온 [request]는 끝난 뒤 한 바퀴로 합친다. 기록 중인 구간(`endedAt == null`)은
 * 건드리지 않는다. 일 하나의 실패는 그 구간·그 일만 남기고(다음 실행 때 다시) 계속하며, 어떤 예외도 [scope]로 새지 않는다
 * ([CancellationException]만 다시 던진다).
 */
class PostProcessor(
    private val rideStore: RideStore,
    private val store: PostStore,
    private val photos: PhotoSource,
    private val heartRate: HeartRateSource,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val warnLog: WarnLog = androidWarnLog("PostProcessor"),
) {
    /** 한 바퀴씩만. */
    private val runLock = Mutex()

    /** 한 구간 extras의 읽고-고쳐-쓰기(바퀴와 [refetchHeartRate]가 같은 행을 덮어쓰지 않게). */
    private val writeLock = Mutex()

    /** 아직 시작하지 않은 한 바퀴가 이미 기다리고 있다. */
    private val queued = AtomicBoolean(false)

    private val _running = MutableStateFlow(false)

    /** 한 바퀴가 도는 중. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 한 바퀴를 [scope]에서 돌린다. 실행 중이면 끝난 뒤 한 번 더(몇 번 불러도 한 번). 겹쳐 돌지 않는다. */
    fun request() {
        if (!queued.compareAndSet(false, true)) return
        scope.launch {
            runLock.withLock {
                queued.set(false)
                cycle()
            }
        }
    }

    /** 한 바퀴를 끝까지(다른 바퀴가 돌고 있으면 끝난 뒤). 테스트용. */
    suspend fun runOnce() {
        runLock.withLock { cycle() }
    }

    /**
     * [다시 가져오기]: 재시도 한도와 상관없이 지금 그 구간의 심박을 읽는다. 샘플이 있으면 저장된 심박을 덮어쓴다.
     * 이미 READY인 구간은 읽기가 실패하거나 비어도 저장된 심박을 지우지 않는다. 열린 구간·없는 구간은 그대로 둔다.
     */
    suspend fun refetchHeartRate(segmentId: Long) {
        try {
            val segment = rideStore.segment(segmentId) ?: return
            val endedAt = segment.endedAt ?: return
            readHeartRate(segment, endedAt, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (gone: SegmentGone) {
            // deleted meanwhile
        } catch (e: Exception) {
            warnLog.warn("Heart rate refetch failed for segment $segmentId", e)
        }
    }

    /** 화면을 열 때: 그 구간들의 사진 중 갤러리에서 지워진 것([PhotoSource.existing]에 없는 mediaId)을 지운다. */
    suspend fun pruneDeletedPhotos(segmentIds: List<Long>) {
        if (segmentIds.isEmpty()) return
        try {
            val mediaIds = store.observePhotos(segmentIds).first().mapTo(HashSet()) { it.mediaId }
            if (mediaIds.isEmpty()) return
            val missing = mediaIds - photos.existing(mediaIds)
            store.deletePhotosByMediaId(missing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("Could not check for deleted photos", e)
        }
    }

    private suspend fun cycle() {
        _running.value = true
        try {
            for (segment in store.segmentsNeedingWork(clock())) {
                val endedAt = segment.endedAt ?: continue
                try {
                    process(segment, endedAt)
                } catch (gone: SegmentGone) {
                    // deleted during the run: nothing left to do for it
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("Post-processing run failed", e)
        } finally {
            _running.value = false
        }
    }

    /** ①②③을 따로 한다. [SegmentGone]은 그 구간을 그만두게 위로 보낸다. */
    private suspend fun process(segment: RideSegment, endedAt: Long) {
        val now = clock()
        val extras = store.extras(segment.id) ?: SegmentExtras(segment.id)
        var track: List<TrackPoint>? = null
        val points: suspend () -> List<TrackPoint> = { track ?: rideStore.points(segment.id).also { track = it } }

        if (!extras.photoScanDone && photos.access() != PhotoAccess.NONE) {
            step("Photo scan", segment) { scanPhotos(segment, endedAt, now, points) }
        }
        if (HrRetryPolicy.shouldTry(extras.hrStatus, endedAt, now)) {
            step("Heart rate read", segment) { readHeartRate(segment, endedAt, now) }
        }
        if (!extras.simplifiedDone) {
            step("Route simplification", segment) { simplify(segment, points()) }
        }
    }

    private suspend inline fun step(what: String, segment: RideSegment, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SegmentGone) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("$what failed for segment ${segment.id}", e)
        }
    }

    /** 실패하면 [SegmentExtras.photoScanDone]은 그대로(다음 실행 때 조용히 다시 찾는다 — 판정 P2). */
    private suspend fun scanPhotos(segment: RideSegment, endedAt: Long, now: Long, points: suspend () -> List<TrackPoint>) {
        val candidates = photos.photos(PhotoPlacement.windowStart(segment.startedAt), PhotoPlacement.windowEnd(endedAt))
        if (candidates.isNotEmpty()) {
            val track = points()
            store.upsertPhotos(segment.id, candidates.mapNotNull { PhotoPlacement.place(it, track) })
        }
        updateExtras(segment.id) {
            it.copy(photosScannedAt = now, photoScanDone = now > endedAt + PHOTO_RESCAN_MS)
        }
    }

    private suspend fun readHeartRate(segment: RideSegment, endedAt: Long, now: Long) {
        val read = try {
            if (heartRate.availability() != HealthAvailability.AVAILABLE || !heartRate.hasPermissions()) {
                HrRead.Failed
            } else {
                heartRate.read(segment.startedAt, endedAt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("Heart rate read failed for segment ${segment.id}", e)
            HrRead.Failed
        }
        val status = HrRetryPolicy.after(read, endedAt, now)
        writeLock.withLock {
            val current = store.extras(segment.id) ?: SegmentExtras(segment.id)
            val tried = current.copy(hrFirstTriedAt = current.hrFirstTriedAt ?: now, hrLastTriedAt = now)
            val next = when {
                read is HrRead.Samples -> {
                    store.replaceSamples(segment.id, read.samples)
                    val stats = HeartRateSummary.of(read.samples, segment.totals.movingTimeMs)
                    tried.copy(
                        hrStatus = status,
                        hrDetailed = stats?.detailed ?: false,
                        avgBpm = stats?.avgBpm,
                        maxBpm = stats?.maxBpm,
                        calories = read.calories,
                    )
                }
                // keep heart rate already read (only [refetchHeartRate] reads a READY segment)
                current.hrStatus == HrStatus.READY -> tried
                else -> tried.copy(hrStatus = status)
            }
            store.saveExtras(next)
        }
    }

    private suspend fun simplify(segment: RideSegment, track: List<TrackPoint>) {
        val entry = CumulativeEntry(
            segmentId = segment.id,
            localDate = segment.localDate,
            distanceM = segment.totals.distanceM,
            movingTimeMs = segment.totals.movingTimeMs,
            points = RouteSimplifier.simplify(track.map { it.point }),
        )
        store.saveSimplified(segment.id, entry)
        updateExtras(segment.id) { it.copy(simplifiedDone = true) }
    }

    private suspend fun updateExtras(segmentId: Long, change: (SegmentExtras) -> SegmentExtras) {
        writeLock.withLock {
            store.saveExtras(change(store.extras(segmentId) ?: SegmentExtras(segmentId)))
        }
    }

    companion object {
        /** 구간 끝에서 이 시간 동안은 바퀴마다 사진을 다시 찾는다(늦게 저장된 사진). 지나면 한 번 더 찾고 끝. */
        const val PHOTO_RESCAN_MS = 24 * 60 * 60_000L
    }
}

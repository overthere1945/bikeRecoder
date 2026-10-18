package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.HrRetryPolicy
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoOwnership
import com.cowork.bikerecoder.core.post.PhotoPlacement
import com.cowork.bikerecoder.core.post.RouteSimplifier
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TrackPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 끝난 구간의 후처리(스펙 §1.1): 사진 찾기, 심박 읽기, 누적 지도용 단순화 경로. 앱이 화면에 있을 때만 [request]로 돌린다.
 *
 * 한 번에 한 바퀴만 돈다([runLock]). 실행 중에 온 [request]는 끝난 뒤 한 바퀴로 합친다. 기록 중인 구간(`endedAt == null`)은
 * 건드리지 않는다. 일 하나의 실패는 그 구간·그 일만 남기고(다음 실행 때 다시) 계속하며, 어떤 예외도 [scope]로 새지 않는다
 * (이 코루틴이 정말 취소되었을 때의 [CancellationException]만 다시 던진다).
 */
class PostProcessor(
    private val rideStore: RideStore,
    private val store: PostStore,
    private val photos: PhotoSource,
    private val heartRate: HeartRateSource,
    /** Wall-clock milliseconds (also the summary's refetch schedule, [com.cowork.bikerecoder.ui.record.SummaryExtras]). */
    internal val clock: () -> Long,
    private val scope: CoroutineScope,
    private val warnLog: WarnLog = androidWarnLog("PostProcessor"),
) {
    /** 한 바퀴씩만. */
    private val runLock = Mutex()

    /** 한 구간 extras의 읽고-고쳐-쓰기(바퀴와 [refetchHeartRate]가 같은 행을 덮어쓰지 않게). */
    private val writeLock = Mutex()

    /** [queued]와 [busy]를 함께 바꾼다(한 바퀴가 끝나는 순간의 [request]도 놓치지 않게). */
    private val requestLock = Any()

    /** 아직 시작하지 않은 한 바퀴가 이미 기다리고 있다. [requestLock] 아래에서만. */
    private var queued = false

    private val _running = MutableStateFlow(false)

    /** 한 바퀴가 도는 중. [request] 직후 차례를 기다리는 동안은 아직 false. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _finishedRuns = MutableStateFlow(0)

    /**
     * 끝난 바퀴 수(실패한 바퀴도). 끝날 때마다 늘어서, 바퀴가 아주 짧아 [running]의 true를 못 보고 지나쳐도 끝난 것은 안다
     * — 화면이 결과를 다시 읽는 신호.
     */
    val finishedRuns: StateFlow<Int> = _finishedRuns.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /**
     * 한 바퀴가 돌거나 차례를 기다리는 중: [request] 하는 순간 true, 기다리는 바퀴 없이 한 바퀴가 끝나면 false.
     * [finishedRuns]가 늘기 전에 바뀐다.
     */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _routesSaved = MutableStateFlow(0)

    /** 저장한 단순화 경로 수. 한 바퀴 도중에도 구간마다 늘어 — 누적 지도가 그때그때 다시 읽는 신호. */
    val routesSaved: StateFlow<Int> = _routesSaved.asStateFlow()

    /** 한 바퀴를 [scope]에서 돌린다. 실행 중이면 끝난 뒤 한 번 더(몇 번 불러도 한 번). 겹쳐 돌지 않는다. */
    fun request() {
        synchronized(requestLock) {
            if (queued) return
            queued = true
            _busy.value = true
        }
        scope.launch {
            runLock.withLock {
                synchronized(requestLock) { queued = false }
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
     * 이미 READY인 구간은 읽기가 실패하거나 비어도 저장된 심박을 지우지 않는다(판정 R7). 열린 구간·없는 구간은 그대로 둔다.
     */
    suspend fun refetchHeartRate(segmentId: Long) {
        try {
            val segment = rideStore.segment(segmentId) ?: return
            val endedAt = segment.endedAt ?: return
            val now = clock()
            saveHeartRate(segment, endedAt, now, if (heartRateReadable()) readSource(segment, endedAt) else null)
        } catch (gone: SegmentGone) {
            // deleted meanwhile
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
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
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            warnLog.warn("Could not check for deleted photos", e)
        }
    }

    /**
     * 예외는 모두 여기서 멈춘다. 이 코루틴이 정말 취소된 경우만 [ensureActive]가 다시 던지고, 출처가 던진
     * [CancellationException](예: 내부 시간 초과)은 다른 실패처럼 그 일·그 구간만 실패로 남긴다.
     */
    private suspend fun cycle() {
        _running.value = true
        _busy.value = true
        try {
            // Health Connect access, checked once per pass and only if some segment needs its heart rate (I4).
            var readable: Boolean? = null
            val canReadHeartRate: suspend () -> Boolean = { readable ?: heartRateReadable().also { readable = it } }
            for (segment in store.segmentsNeedingWork(clock())) {
                val endedAt = segment.endedAt ?: continue
                try {
                    process(segment, endedAt, canReadHeartRate)
                } catch (gone: SegmentGone) {
                    // deleted during the run: nothing left to do for it
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    warnLog.warn("Post-processing failed for segment ${segment.id}", e)
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            warnLog.warn("Post-processing run failed", e)
        } finally {
            _running.value = false
            synchronized(requestLock) { _busy.value = queued }
            _finishedRuns.update { it + 1 }
        }
    }

    /**
     * ①②③을 따로 한다. [SegmentGone]은 그 구간을 그만두게 위로 보낸다. [canReadHeartRate]가 false면(판정 R8) 심박은
     * 읽지도 쓰지도 않는다 — 상태는 PENDING 그대로, 바뀐 것이 없으니 쓸 것도 없다.
     */
    private suspend fun process(segment: RideSegment, endedAt: Long, canReadHeartRate: suspend () -> Boolean) {
        val now = clock()
        val extras = store.extras(segment.id) ?: SegmentExtras(segment.id)
        var track: List<TrackPoint>? = null
        val points: suspend () -> List<TrackPoint> = { track ?: rideStore.points(segment.id).also { track = it } }

        if (!extras.photoScanDone) {
            step("Photo scan", segment) {
                val access = photos.access()
                if (access != PhotoAccess.NONE && !rescanThrottled(extras, endedAt, now, access)) {
                    scanPhotos(segment, endedAt, now, access, points)
                }
            }
        }
        if (HrRetryPolicy.shouldTry(extras.hrStatus, endedAt, extras.hrFirstTriedAt, now) && canReadHeartRate()) {
            step("Heart rate read", segment) { saveHeartRate(segment, endedAt, now, readSource(segment, endedAt)) }
        }
        if (!extras.simplifiedDone) {
            step("Route simplification", segment) { simplify(segment, points()) }
        }
    }

    /**
     * 판정 R16: "선택한 사진만"([PhotoAccess.PARTIAL])이면 끝난 지 24시간이 지난 구간의 사진은 마지막으로 찾은 뒤
     * 24시간이 지나야 다시 찾는다 — 그 구간은 끝나지 않으니(R15) 앱을 열 때마다 구간마다 찾고 쓰지 않게. 구간 끝 뒤
     * 24시간 안은 바퀴마다, [PhotoAccess.FULL]은 언제나 찾는다(모두 허용하면 다음 바퀴에 나머지를 찾고 끝낸다).
     */
    private fun rescanThrottled(extras: SegmentExtras, endedAt: Long, now: Long, access: PhotoAccess): Boolean {
        val scannedAt = extras.photosScannedAt ?: return false
        return access != PhotoAccess.FULL && now > endedAt + PHOTO_RESCAN_MS && now - scannedAt < PHOTO_RESCAN_MS
    }

    private suspend inline fun step(what: String, segment: RideSegment, block: () -> Unit) {
        try {
            block()
        } catch (e: SegmentGone) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            warnLog.warn("$what failed for segment ${segment.id}", e)
        }
    }

    /**
     * 실패하면 [SegmentExtras.photoScanDone]은 그대로(다음 실행 때 조용히 다시 찾는다 — 판정 P2). 끝내는 것은 모든 사진을
     * 볼 수 있을 때([PhotoAccess.FULL])뿐(판정 R15): "선택한 사진만"이면 나중에 모두 허용했을 때 나머지도 찾는다.
     *
     * 한 여행 안에서 사진은 주인 구간 하나에만 붙는다(판정 R13, [PhotoOwnership]): 형제 구간이 주인인 사진은 건너뛰고,
     * 전에 이 구간에 저장했지만 이제 형제가 주인인 사진(R13 이전 빌드의 중복, 또는 그 뒤에 시작한 구간)은 지운다.
     */
    private suspend fun scanPhotos(
        segment: RideSegment,
        endedAt: Long,
        now: Long,
        access: PhotoAccess,
        points: suspend () -> List<TrackPoint>,
    ) {
        val trip = rideStore.segments(segment.tripId)
        if (trip.none { it.id == segment.id }) throw SegmentGone(segment.id)
        val owns = { takenAt: Long -> PhotoOwnership.ownerOf(takenAt, trip) == segment.id }

        val disowned = store.observePhotos(listOf(segment.id)).first().filterNot { owns(it.takenAt) }
        store.deletePhotos(segment.id, disowned.map { it.mediaId })

        val candidates = photos.photos(PhotoPlacement.windowStart(segment.startedAt), PhotoPlacement.windowEnd(endedAt))
            .filter { owns(it.takenAt) }
        if (candidates.isNotEmpty()) {
            val track = points()
            store.upsertPhotos(segment.id, candidates.mapNotNull { PhotoPlacement.place(it, track) })
        }
        updateExtras(segment.id) {
            it.copy(photosScannedAt = now, photoScanDone = access == PhotoAccess.FULL && now > endedAt + PHOTO_RESCAN_MS)
        }
    }

    /**
     * 심박을 읽을 수 있는가: Health Connect를 쓸 수 있고 심박 권한이 있다. 확인이 예외를 던지면 읽을 수 없는 것으로
     * 본다(판정 R8 — FAILED가 아니라 이번에는 건너뜀).
     */
    private suspend fun heartRateReadable(): Boolean = try {
        heartRate.availability() == HealthAvailability.AVAILABLE && heartRate.hasPermissions()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        warnLog.warn("Could not check Health Connect access", e)
        false
    }

    /** 허용된 출처에서 한 번 읽는다. 예외는 실제 읽기 실패([HrRead.Failed]). */
    private suspend fun readSource(segment: RideSegment, endedAt: Long): HrRead = try {
        heartRate.read(segment.startedAt, endedAt)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        warnLog.warn("Heart rate read failed for segment ${segment.id}", e)
        HrRead.Failed
    }

    /**
     * [read]를 저장한다. null은 읽을 수 없었다는 뜻([다시 가져오기]에서만): 상태는 그대로 두고 hrLastTriedAt만 —
     * 판정 R8. 권한을 나중에 주면 오래된 PENDING 구간도 그때 READY·NONE이 된다. FAILED는 쓸 수 있고 허용된 출처의
     * 실제 읽기 실패뿐.
     */
    private suspend fun saveHeartRate(segment: RideSegment, endedAt: Long, now: Long, read: HrRead?) {
        // Not cancellable once begun: new samples are never left stored under the old summary and state.
        writeLock.withLock {
            withContext(NonCancellable) {
                val current = store.extras(segment.id) ?: SegmentExtras(segment.id)
                val next = when {
                    read == null -> current.copy(hrLastTriedAt = now)
                    read is HrRead.Samples -> {
                        store.replaceSamples(segment.id, read.samples)
                        val stats = HeartRateSummary.of(read.samples, segment.totals.movingTimeMs)
                        current.tried(now).copy(
                            hrStatus = HrRetryPolicy.after(read, endedAt, now),
                            hrDetailed = stats?.detailed ?: false,
                            avgBpm = stats?.avgBpm,
                            maxBpm = stats?.maxBpm,
                            calories = read.calories,
                        )
                    }
                    // keep heart rate already read (R7; only [refetchHeartRate] reads a READY segment)
                    current.hrStatus == HrStatus.READY -> current.tried(now)
                    else -> current.tried(now).copy(hrStatus = HrRetryPolicy.after(read, endedAt, now))
                }
                store.saveExtras(next)
            }
        }
    }

    private fun SegmentExtras.tried(now: Long) = copy(hrFirstTriedAt = hrFirstTriedAt ?: now, hrLastTriedAt = now)

    private suspend fun simplify(segment: RideSegment, track: List<TrackPoint>) {
        val entry = CumulativeEntry(
            segmentId = segment.id,
            localDate = segment.localDate,
            distanceM = segment.totals.distanceM,
            movingTimeMs = segment.totals.movingTimeMs,
            points = RouteSimplifier.simplify(track.map { it.point }),
        )
        store.saveSimplified(segment.id, entry)
        _routesSaved.update { it + 1 }
        updateExtras(segment.id) { it.copy(simplifiedDone = true) }
    }

    /** 행이 없거나 [change]로 무언가 바뀔 때만 쓴다(같은 값을 다시 써서 extras 관찰자를 깨우지 않게). */
    private suspend fun updateExtras(segmentId: Long, change: (SegmentExtras) -> SegmentExtras) {
        writeLock.withLock {
            val current = store.extras(segmentId)
            val next = change(current ?: SegmentExtras(segmentId))
            if (next != current) store.saveExtras(next)
        }
    }

    companion object {
        /**
         * 구간 끝에서 이 시간 동안은 바퀴마다 사진을 다시 찾는다(늦게 저장된 사진). 지나면 한 번 더 찾고 끝.
         * 심박 다시 읽기 한도와 같은 "구간 끝 + 24시간". "선택한 사진만"이면 끝나지 않고 이 간격으로 다시 찾는다(R16).
         */
        const val PHOTO_RESCAN_MS = HrRetryPolicy.GIVE_UP_MS
    }
}

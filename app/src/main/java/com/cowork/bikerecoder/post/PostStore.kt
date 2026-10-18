package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.recording.RideSegment
import kotlinx.coroutines.flow.Flow

/** 구간에 붙은 사진 한 장. 원본은 갤러리에 있고 [mediaId]는 MediaStore `_ID`. */
data class RidePhoto(
    val id: Long,
    val segmentId: Long,
    val mediaId: Long,
    val takenAt: Long,
    val point: GeoPoint,
    val source: PhotoPositionSource,
    val hidden: Boolean,
)

/** 구간 후처리 진행 상태와 심박 요약. */
data class SegmentExtras(
    val segmentId: Long,
    val photosScannedAt: Long? = null,
    val photoScanDone: Boolean = false,
    val hrStatus: HrStatus = HrStatus.PENDING,
    val hrFirstTriedAt: Long? = null,
    val hrLastTriedAt: Long? = null,
    val hrDetailed: Boolean = false,
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    val calories: Double? = null,
    val simplifiedDone: Boolean = false,
)

/** 쓰려는 구간이 이미 지워졌다(외래 키 위반). 후처리는 그 구간을 건너뛴다. */
class SegmentGone(val segmentId: Long, cause: Throwable? = null) :
    Exception("ride segment $segmentId no longer exists", cause)

/**
 * 후처리 결과 저장소. 구간에 쓰는 함수는 그 구간이 없으면 [SegmentGone]을 던진다
 * (실제로 쓸 행이 없는 호출 — 빈 목록 등 — 은 던지지 않는다).
 */
interface PostStore {
    /**
     * 끝난 구간 중 할 일이 남은 것(최근 시작 순 — 업그레이드 뒤 첫 실행도 마지막 주행부터): extras 없음, !photoScanDone, !simplifiedDone,
     * hrStatus == PENDING, 또는 hrStatus == FAILED이고 [now] <= HrRetryPolicy.failedRetryUntil(endedAt, hrFirstTriedAt)
     * (= max(endedAt, hrFirstTriedAt) + GIVE_UP_MS, 판정 R14).
     */
    suspend fun segmentsNeedingWork(now: Long): List<RideSegment>
    suspend fun extras(segmentId: Long): SegmentExtras?
    fun observeExtras(segmentIds: List<Long>): Flow<List<SegmentExtras>>
    suspend fun saveExtras(extras: SegmentExtras)

    /** 숨긴 사진도 포함, 찍은 순. */
    fun observePhotos(segmentIds: List<Long>): Flow<List<RidePhoto>>

    /** (segmentId, mediaId)가 이미 있으면 그대로 둔다(숨김 유지). */
    suspend fun upsertPhotos(segmentId: Long, photos: List<PlacedPhoto>)
    suspend fun setHidden(photoId: Long, hidden: Boolean)
    suspend fun deletePhotosByMediaId(mediaIds: Collection<Long>)

    /** 그 구간의 사진 중 [mediaIds]만 지운다(다른 구간의 같은 사진은 그대로 — 판정 R13 정리). */
    suspend fun deletePhotos(segmentId: Long, mediaIds: Collection<Long>)

    /** 그 구간의 심박 샘플을 모두 [samples]로 바꾼다. */
    suspend fun replaceSamples(segmentId: Long, samples: List<HeartRateSample>)

    /** 시간순. */
    suspend fun samples(segmentId: Long): List<HeartRateSample>

    /** [entry]의 segmentId 대신 [segmentId]로 저장한다. */
    suspend fun saveSimplified(segmentId: Long, entry: CumulativeEntry)

    /** 저장된 단순화 경로 전체, 날짜·구간 순. */
    suspend fun cumulativeEntries(): List<CumulativeEntry>
}

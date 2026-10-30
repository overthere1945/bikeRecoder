package com.cowork.bikerecoder.video

import kotlinx.coroutines.flow.Flow

/** 만든 파일의 종류: 영상(mp4) 또는 요약 이미지(png). */
enum class MediaKind { VIDEO, IMAGE }

/** 갤러리 항목의 MIME 형식(저장, 공유, 보기 인텐트). */
val MediaKind.mimeType: String
    get() = when (this) {
        MediaKind.VIDEO -> "video/mp4"
        MediaKind.IMAGE -> "image/png"
    }

/**
 * 여행에서 만든 영상·이미지 기록(스펙 §5.1). [uri]는 갤러리(MediaStore) 항목, [optionsJson]은 만들 때의 옵션
 * ([VideoOptionsJson], 음악 제외). 여행을 지우면 기록만 사라지고 갤러리 파일은 남는다.
 */
data class TripMedia(
    val id: Long,
    val tripId: Long,
    val kind: MediaKind,
    val uri: String,
    val createdAt: Long,
    val optionsJson: String,
)

interface TripMediaStore {
    /** [tripId] 여행의 기록, 최근에 만든 것부터. */
    fun observe(tripId: Long): Flow<List<TripMedia>>

    /** 모든 여행의 영상([MediaKind.VIDEO])만, 최근에 만든 것부터. 요약 탭 카드의 ▶ 영상용이다. */
    fun observeVideos(): Flow<List<TripMedia>>

    /** 들어온 [TripMedia.id]는 무시하고 새 id를 돌려준다. */
    suspend fun add(m: TripMedia): Long

    suspend fun remove(ids: Collection<Long>)
}

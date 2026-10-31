package com.cowork.bikerecoder.core.trip

import kotlinx.coroutines.flow.Flow

/**
 * 여행에 붙는 이름표: 사용자가 정한 제목과, 처음 한 번 알아낸 지역 이름, 공유 글. 모두 없을 수 있다.
 * [shareText]는 사용자가 고쳐 쓴 공유 글이고, [shareStamp]는 그때의 자동 글 바탕(요약) 표식이다.
 * 둘은 함께 저장되고 함께 지워진다.
 */
data class TripLabel(
    val customTitle: String? = null,
    val regionName: String? = null,
    val shareText: String? = null,
    val shareStamp: String? = null,
)

interface TripLabelStore {
    /** 줄이 없으면 빈 [TripLabel]. */
    suspend fun label(tripId: Long): TripLabel

    /** 이름표가 있는 여행만 담긴다. */
    fun observeLabels(): Flow<Map<Long, TripLabel>>

    /** 지역 이름은 그대로 두고 제목만 바꾼다. null이면 제목을 지운다. */
    suspend fun setCustomTitle(tripId: Long, title: String?)

    /** 제목은 그대로 두고 지역 이름만 바꾼다. */
    suspend fun setRegionName(tripId: Long, name: String)

    /** 제목·지역 이름은 그대로 두고 공유 글만 바꾼다. [text]가 null이면 [shareText]와 [shareStamp] 둘 다 지운다. */
    suspend fun setShareText(tripId: Long, text: String?, stamp: String?)
}

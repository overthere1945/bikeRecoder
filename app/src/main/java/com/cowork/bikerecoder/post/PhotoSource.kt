package com.cowork.bikerecoder.post

import android.net.Uri
import com.cowork.bikerecoder.core.post.PhotoCandidate

/** 사진 접근 상태. [PARTIAL]은 Android 14+ "선택한 사진만 허용"(선택된 사진만 찾는다). */
enum class PhotoAccess { FULL, PARTIAL, NONE }

/** 갤러리 사진의 출처. */
interface PhotoSource {
    fun access(): PhotoAccess

    /**
     * 카메라 폴더(`DCIM/Camera/`)의 이미지 중 찍은 시각(`DATE_TAKEN`, 없으면 `DATE_ADDED`×1000)이
     * [from, to](양끝 포함) 안인 것, 찍은 시각 순. `ACCESS_MEDIA_LOCATION`이 있으면 EXIF 위치도 채운다.
     */
    suspend fun photos(from: Long, to: Long): List<PhotoCandidate>

    /**
     * [mediaIds] 중 아직 갤러리에 있는 것. 모든 사진 접근([PhotoAccess.FULL])이 아니면 없어졌는지 알 수 없으므로
     * 받은 id를 모두 그대로 돌려준다(권한을 거둔 것만으로 저장된 사진을 지우지 않게).
     */
    suspend fun existing(mediaIds: Collection<Long>): Set<Long>

    fun uri(mediaId: Long): Uri
}

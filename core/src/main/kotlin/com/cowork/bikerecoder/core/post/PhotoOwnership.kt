package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.recording.RideSegment

/**
 * 한 여행 안에서 사진 한 장은 구간 하나에만 붙는다(판정 R13). 이어 붙은 구간("새 구간으로 나누기", 안내 끝 뒤 새 기록)은
 * 사진 창(앞뒤 [PhotoPlacement.WINDOW_MS])이 겹치므로, 각 구간은 자기가 주인인 사진만 저장한다.
 */
object PhotoOwnership {

    /**
     * [takenAt]에 찍은 사진의 주인 구간 id: [startedAt, endedAt](열린 구간은 끝이 없음)에 [takenAt]이 들어가는 구간,
     * 없으면 시작이나 끝이 가장 가까운 구간. 같으면 앞 구간(시작이 이른 쪽, 같으면 id가 작은 쪽). [segments]가 비면 null.
     * [segments]는 한 여행의 구간들이며 순서는 상관없다.
     */
    fun ownerOf(takenAt: Long, segments: List<RideSegment>): Long? =
        segments
            .minWithOrNull(compareBy<RideSegment>({ distance(takenAt, it) }, { it.startedAt }, { it.id }))
            ?.id

    /** 구간 안이면 0, 아니면 가까운 경계까지의 시간. */
    private fun distance(t: Long, s: RideSegment): Long {
        val end = s.endedAt
        return when {
            t < s.startedAt -> s.startedAt - t
            end != null && t > end -> t - end
            else -> 0L
        }
    }
}

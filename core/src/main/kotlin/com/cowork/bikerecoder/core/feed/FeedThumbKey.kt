package com.cowork.bikerecoder.core.feed

import java.security.MessageDigest
import kotlin.math.roundToLong

/** 피드 위성 그림의 키에 드는 구간 하나(스펙 §2.4): 구간 번호, 끝난 때(ms), 거리(m). */
data class ThumbSegment(val id: Long, val endedAt: Long, val distanceM: Double)

/**
 * 피드 위성 그림 파일 이름의 키(스펙 §2.4). 그림이 달라질 때(구간이 늘거나 줄거나, 끝난 때·거리가 바뀔 때) 키가 바뀌어
 * 새로 그리고, 같으면 저장된 그림을 그대로 쓴다. 구간을 번호 순으로 "id:endedAt:거리(0.1 m 반올림)"로 쓰고 [VERSION]을 이어
 * SHA-256을 낸 앞 12자리 16진수.
 */
object FeedThumbKey {
    /** 그리는 방식을 바꾸면 올린다: 저장된 그림이 모두 새로 그려진다. */
    const val VERSION = 1

    private const val HEX_CHARS = 12

    fun of(segments: List<ThumbSegment>): String {
        val text = segments.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.endedAt}:${(it.distanceM * 10).roundToLong()}" } +
            "|v$VERSION"
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HEX_CHARS)
    }
}

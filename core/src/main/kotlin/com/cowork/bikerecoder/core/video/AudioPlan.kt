package com.cowork.bikerecoder.core.video

/**
 * 배경음악 자르기(마이크로초): 음악 파일 [startUs]부터 [takeUs]만큼 쓰고, [startUs] 뒤 [fadeStartUs]부터 끝까지 소리를 줄인다.
 * [takeUs]·[fadeStartUs]는 [startUs] 기준.
 */
data class AudioPlan(val startUs: Long, val takeUs: Long, val fadeStartUs: Long)

/**
 * 배경음악 계산(스펙 §3.1⑤, §5.3): 음악 파일 [startUs](박에 맞춘 시작점, 없으면 0)부터 영상 길이만큼 쓰고, 곡이 먼저 끝나면
 * 반복하지 않고 곡 끝까지. 쓰는 부분의 마지막 2초 페이드.
 */
object AudioPlanner {

    const val FADE_US = 2_000_000L

    fun plan(musicDurationUs: Long, videoDurationUs: Long, startUs: Long = 0): AudioPlan {
        val take = minOf(musicDurationUs - startUs, videoDurationUs).coerceAtLeast(0L)
        return AudioPlan(startUs = startUs, takeUs = take, fadeStartUs = (take - FADE_US).coerceAtLeast(0L))
    }
}

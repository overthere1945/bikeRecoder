package com.cowork.bikerecoder.core.video

/** 배경음악 자르기: 처음부터 [takeUs]까지 쓰고 [fadeStartUs]부터 끝까지 소리를 줄인다(마이크로초). */
data class AudioPlan(val takeUs: Long, val fadeStartUs: Long)

/** 배경음악 계산(스펙 §3.1⑤): 영상보다 길면 영상 길이로 자르고, 짧으면 반복하지 않고 음악 길이까지. 마지막 2초 페이드. */
object AudioPlanner {

    const val FADE_US = 2_000_000L

    fun plan(musicDurationUs: Long, videoDurationUs: Long): AudioPlan {
        val take = minOf(musicDurationUs, videoDurationUs).coerceAtLeast(0L)
        return AudioPlan(takeUs = take, fadeStartUs = (take - FADE_US).coerceAtLeast(0L))
    }
}

package com.cowork.bikerecoder.core.video

import kotlin.math.ceil
import kotlin.math.roundToLong

/** 만들기 전 예상(스펙 §3.6, §3.4). */
object VideoEstimate {

    /** 지도를 다시 찍지 않는 프레임([FramePlan.reuseMap]) 한 장의 시간(초). */
    const val REUSED_SECONDS_PER_FRAME = 0.03
    const val BITRATE_BPS = 8_000_000L
    const val SIZE_MARGIN = 1.2

    /** 예상 시간(분, 올림, 최소 1분). [secondsPerFrame] = 지도를 찍는 프레임 한 장의 시간(기본 0.25초, 이 휴대폰의 실제 값이 있으면 그것). */
    fun minutes(frames: List<FramePlan>, secondsPerFrame: Double = 0.25): Int {
        val reused = frames.count { it.reuseMap }
        val seconds = (frames.size - reused) * secondsPerFrame + reused * REUSED_SECONDS_PER_FRAME
        return ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    }

    /** 예상 저장 공간(바이트) = 8 Mbps × 길이 × 1.2. */
    fun bytes(lengthSeconds: Double): Long = (BITRATE_BPS / 8.0 * lengthSeconds * SIZE_MARGIN).roundToLong()
}

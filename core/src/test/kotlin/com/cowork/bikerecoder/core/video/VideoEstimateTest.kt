package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VideoEstimateTest {

    private fun frame(index: Int, reuse: Boolean) =
        FramePlan(index, SceneKind.RIDE, 0, 0f, photo = null, caption = null, fade = 0f, reuseMap = reuse)

    @Test
    fun estimateMinutes() {
        val frames = List(1440) { frame(it, reuse = it < 200) }
        // ⌈(1240 × 0.25 + 200 × 0.03) / 60⌉ = ⌈316 / 60⌉ = 6
        assertEquals(6, VideoEstimate.minutes(frames))
        // 장당 실제 값이 있으면 그것: ⌈(1240 × 0.5 + 6) / 60⌉ = ⌈626 / 60⌉ = 11
        assertEquals(11, VideoEstimate.minutes(frames, secondsPerFrame = 0.5))
    }

    @Test
    fun remainingFramesByCount() {
        // 작업 중 남은 시간: 같은 식을 프레임 수로 — ⌈(1240 × 0.5 + 200 × 0.03) / 60⌉ = 11
        assertEquals(11, VideoEstimate.minutes(mapFrames = 1240, reusedFrames = 200, secondsPerFrame = 0.5))
        assertEquals(1, VideoEstimate.minutes(mapFrames = 0, reusedFrames = 0, secondsPerFrame = 0.5))
    }

    @Test
    fun estimateIsAtLeastOneMinute() {
        assertEquals(1, VideoEstimate.minutes(List(10) { frame(it, reuse = false) }))
        assertEquals(1, VideoEstimate.minutes(emptyList()))
    }

    @Test
    fun estimateBytes() {
        assertEquals(72_000_000L, VideoEstimate.bytes(60.0))
        assertEquals(36_000_000L, VideoEstimate.bytes(30.0))
    }

    @Test
    fun satelliteMb() {
        assertEquals(20, VideoEstimate.satelliteMb(VideoLength.SHORT, frames = 720, bytesPerFrame = null))
        assertEquals(35, VideoEstimate.satelliteMb(VideoLength.NORMAL, frames = 1440, bytesPerFrame = null))
        assertEquals(60, VideoEstimate.satelliteMb(VideoLength.LONG, frames = 2880, bytesPerFrame = null))
        // 실측: ⌈50_000 × 720 / 1_000_000⌉ = 36
        assertEquals(36, VideoEstimate.satelliteMb(VideoLength.NORMAL, frames = 720, bytesPerFrame = 50_000.0))
        // 올림
        assertEquals(2, VideoEstimate.satelliteMb(VideoLength.NORMAL, frames = 11, bytesPerFrame = 100_000.0))
    }
}

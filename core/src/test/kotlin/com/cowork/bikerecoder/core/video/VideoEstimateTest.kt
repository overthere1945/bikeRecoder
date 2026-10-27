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
    fun estimateIsAtLeastOneMinute() {
        assertEquals(1, VideoEstimate.minutes(List(10) { frame(it, reuse = false) }))
        assertEquals(1, VideoEstimate.minutes(emptyList()))
    }

    @Test
    fun estimateBytes() {
        assertEquals(72_000_000L, VideoEstimate.bytes(60.0))
        assertEquals(36_000_000L, VideoEstimate.bytes(30.0))
    }
}

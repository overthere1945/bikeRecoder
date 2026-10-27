package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AudioPlanTest {

    @Test
    fun audioShortMusicEndsEarly() {
        // 음악 10초, 영상 60초: 반복하지 않고 10초에서 끝, 8초부터 줄인다.
        assertEquals(AudioPlan(takeUs = 10_000_000, fadeStartUs = 8_000_000), AudioPlanner.plan(10_000_000, 60_000_000))
    }

    @Test
    fun audioFadeTwoSeconds() {
        assertEquals(AudioPlan(takeUs = 60_000_000, fadeStartUs = 58_000_000), AudioPlanner.plan(300_000_000, 60_000_000))
        // 2초보다 짧으면 처음부터 줄인다.
        assertEquals(AudioPlan(takeUs = 1_500_000, fadeStartUs = 0), AudioPlanner.plan(1_500_000, 60_000_000))
    }
}

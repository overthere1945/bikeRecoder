package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AudioPlanTest {

    @Test
    fun audioShortMusicEndsEarly() {
        // 음악 10초, 영상 60초: 반복하지 않고 10초에서 끝, 8초부터 줄인다.
        assertEquals(AudioPlan(startUs = 0, takeUs = 10_000_000, fadeStartUs = 8_000_000), AudioPlanner.plan(10_000_000, 60_000_000))
    }

    @Test
    fun audioFadeTwoSeconds() {
        assertEquals(AudioPlan(startUs = 0, takeUs = 60_000_000, fadeStartUs = 58_000_000), AudioPlanner.plan(300_000_000, 60_000_000))
        // 2초보다 짧으면 처음부터 줄인다.
        assertEquals(AudioPlan(startUs = 0, takeUs = 1_500_000, fadeStartUs = 0), AudioPlanner.plan(1_500_000, 60_000_000))
    }

    @Test
    fun startOffsetTakesFromThere() {
        // 음악 60초를 5초부터: 영상 30초만큼(파일 5~35초) 쓰고 파일 33초(= 시작 뒤 28초)부터 줄인다.
        assertEquals(AudioPlan(startUs = 5_000_000, takeUs = 30_000_000, fadeStartUs = 28_000_000), AudioPlanner.plan(60_000_000, 30_000_000, 5_000_000))
    }

    @Test
    fun offsetPastShortMusic() {
        // 음악 20초를 5초부터: 곡 끝까지 15초만, 시작 뒤 13초부터 줄인다.
        assertEquals(AudioPlan(startUs = 5_000_000, takeUs = 15_000_000, fadeStartUs = 13_000_000), AudioPlanner.plan(20_000_000, 30_000_000, 5_000_000))
    }
}

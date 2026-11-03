package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BeatFinderTest {

    /**
     * 박마다 10 ms의 0.8 소리가 나는 곡의 RMS 배열(창 1024, 간격 512). [silenceS]초까지는 0.001로 조용하고,
     * 그 뒤부터 (창이 겹친 곳에) 균일 잡음 [noise]를 더한다. 첫 박은 [silenceS]에 온다.
     */
    private fun clicks(bpm: Double, seconds: Double, silenceS: Double, noise: Double, seed: Long = 7L): FloatArray {
        val rnd = Random(seed)
        val frames = (seconds * BeatFinder.SAMPLE_RATE / BeatFinder.HOP).toInt()
        val beatS = 60.0 / bpm
        val hopS = BeatFinder.HOP.toDouble() / BeatFinder.SAMPLE_RATE
        val windowS = BeatFinder.WINDOW.toDouble() / BeatFinder.SAMPLE_RATE
        return FloatArray(frames) { i ->
            val from = i * hopS
            val to = from + windowS
            if (to <= silenceS) return@FloatArray 0.001f
            var v = noise * rnd.nextDouble()
            // 이 창과 겹치는 10 ms 소리가 하나라도 있으면 크다.
            val firstBeat = ceil((from - 0.01 - silenceS) / beatS).toInt().coerceAtLeast(0)
            if (silenceS + firstBeat * beatS < to) v += 0.8
            v.toFloat()
        }
    }

    private fun clicks(bpm: Int, seconds: Double, silenceS: Double, noise: Double) =
        clicks(bpm.toDouble(), seconds, silenceS, noise)

    @Test
    fun findsTempoAndPhase() {
        for (bpm in listOf(90, 124, 150)) {
            val b = BeatFinder.find(clicks(bpm, 40.0, silenceS = 2.0, noise = 0.05))!!
            assertEquals(60_000_000.0 / bpm, b.beatUs.toDouble(), 60_000_000.0 / bpm - 60_000_000.0 / (bpm + 1), "bpm $bpm")
            assertEquals(2_000_000.0, b.startUs.toDouble(), 20_000.0, "bpm $bpm")
        }
    }

    @Test
    fun beatRightAtTheStartOfTheSoundIsTheFirstBeat() {
        // 곡이 킥으로 시작하면 첫 박은 0 근처다(한 박 뒤가 아니다).
        val b = BeatFinder.find(clicks(120, 30.0, silenceS = 0.0, noise = 0.05))!!
        assertEquals(0.0, b.startUs.toDouble(), 20_000.0)
    }

    @Test
    fun beatStaysOnTheGridLateInTheTrack() {
        // 박 간격 오차가 쌓이지 않는다: 60초 곡 끝 가까운 박이 20 ms 안에 있다.
        val bpm = 123.5
        val b = BeatFinder.find(clicks(bpm, 60.0, silenceS = 1.0, noise = 0.05))!!
        val late = 100
        val expected = 1_000_000.0 + late * 60_000_000.0 / bpm
        assertEquals(expected, (b.startUs + late * b.beatUs).toDouble(), 20_000.0)
    }

    @Test
    fun barIsFourBeats() {
        assertEquals(2_000_000L, Beats(startUs = 0, beatUs = 500_000).barUs)
    }

    @Test
    fun noiseOnlyHasNoBeat() {
        val rnd = Random(5)
        val energy = FloatArray(40 * BeatFinder.SAMPLE_RATE / BeatFinder.HOP) { (0.3 * rnd.nextDouble()).toFloat() }
        assertNull(BeatFinder.find(energy))
    }

    @Test
    fun silenceAndTooShortHaveNoBeat() {
        assertNull(BeatFinder.find(FloatArray(4000) { 0.001f }))
        assertNull(BeatFinder.find(clicks(120, 3.0, silenceS = 0.0, noise = 0.0)))
        assertNull(BeatFinder.find(FloatArray(0)))
    }

    @Test
    fun slowTempoDoubled() {
        val b = assertNotNull(BeatFinder.find(clicks(62, 40.0, silenceS = 2.0, noise = 0.05)))
        assertEquals(60_000_000.0 / 124, b.beatUs.toDouble(), 60_000_000.0 / 124 - 60_000_000.0 / 125)
    }

    @Test
    fun firstSoundSkipsSilence() {
        val energy = clicks(120, 20.0, silenceS = 3.0, noise = 0.0)
        assertEquals(3_000_000.0, BeatFinder.firstSoundUs(energy).toDouble(), 12_000.0)
    }

    @Test
    fun firstSoundIsZeroWithoutSound() {
        assertEquals(0L, BeatFinder.firstSoundUs(FloatArray(1000) { 0.001f }))
        assertEquals(0L, BeatFinder.firstSoundUs(FloatArray(0)))
    }
}

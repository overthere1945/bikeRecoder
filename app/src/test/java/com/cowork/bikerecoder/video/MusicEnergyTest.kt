package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.video.BeatFinder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class MusicEnergyTest {

    /**
     * [seconds]초 딸깍 소리 열(인터리브 16비트): [firstS]초부터 [bpm]박마다 20 ms짜리 1 kHz 소리, 그 밖은 조용함.
     * 2채널이면 오른쪽은 왼쪽의 절반 크기.
     */
    private fun clicks(rate: Int, channels: Int, seconds: Double, bpm: Double, firstS: Double): ShortArray {
        val frames = (seconds * rate).toInt()
        val beatS = 60.0 / bpm
        return ShortArray(frames * channels) { i ->
            val frame = i / channels
            val t = frame.toDouble() / rate
            val sinceBeat = if (t < firstS) -1.0 else (t - firstS) % beatS
            val amplitude = if (sinceBeat in 0.0..0.02) 0.8 else 0.0
            val gain = if (i % channels == 1) 0.5 else 1.0
            (amplitude * gain * 32767 * sin(2 * PI * 1000 * t)).toInt().toShort()
        }
    }

    /** [pcm]을 [chunkFrames]장씩 디코더 버퍼처럼(네이티브 순서 바이트) 넣는다. */
    private fun feed(energy: MusicEnergy, pcm: ShortArray, channels: Int, chunkFrames: Int) {
        var offset = 0
        while (offset < pcm.size && !energy.full) {
            val n = minOf(chunkFrames * channels, pcm.size - offset)
            val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(pcm, offset, n)
            energy.push(bytes)
            offset += n
        }
    }

    private fun energyOf(rate: Int, channels: Int, pcm: ShortArray, maxUs: Long = 180_000_000L, chunkFrames: Int = 1_000): FloatArray =
        MusicEnergy(rate, channels, isFloat = false, maxUs = maxUs).also { feed(it, pcm, channels, chunkFrames) }.energy()

    @Test
    fun clickTrainAt48kHzStereoReadsTheTempo() {
        val pcm = clicks(48_000, 2, seconds = 20.0, bpm = 120.0, firstS = 0.25)
        val energy = energyOf(48_000, 2, pcm, chunkFrames = 997)

        // 한 창 = 44.1 kHz의 512표본 시간: 20초면 약 1722장(끝의 다 차지 않은 창은 버린다).
        assertTrue(abs(energy.size - 20.0 * 44_100 / 512) <= 3, "frames ${energy.size}")
        val beats = BeatFinder.find(energy)
        assertNotNull(beats, "no beat found")
        beats!!
        val bpm = 60_000_000.0 / beats.beatUs
        assertTrue(abs(bpm - 120.0) <= 1.0, "bpm $bpm")
        assertTrue(abs(beats.startUs - 250_000) <= 25_000, "start ${beats.startUs}")
        assertTrue(abs(BeatFinder.firstSoundUs(energy) - 250_000) <= 25_000, "first sound ${BeatFinder.firstSoundUs(energy)}")
    }

    /** 같은 소리면 표본율이 달라도 창이 같은 시각을 덮는다: 소리가 처음 나는 창과 장 수가 44.1 kHz와 같다. */
    @Test
    fun framesCoverTheSameTimeAtAnyRate() {
        val at441 = energyOf(44_100, 2, clicks(44_100, 2, seconds = 10.0, bpm = 100.0, firstS = 1.0))
        val at48 = energyOf(48_000, 2, clicks(48_000, 2, seconds = 10.0, bpm = 100.0, firstS = 1.0))
        val at22 = energyOf(22_050, 1, clicks(22_050, 1, seconds = 10.0, bpm = 100.0, firstS = 1.0))

        assertTrue(abs(at441.size - at48.size) <= 1, "${at441.size} vs ${at48.size}")
        assertTrue(abs(at441.size - at22.size) <= 1, "${at441.size} vs ${at22.size}")
        val first = at441.indexOfFirst { it > BeatFinder.SOUND_LEVEL }
        assertEquals(first, at48.indexOfFirst { it > BeatFinder.SOUND_LEVEL })
        assertEquals(first, at22.indexOfFirst { it > BeatFinder.SOUND_LEVEL })
        // 소리 크기도 같다(조용한 창 0, 딸깍 소리가 든 창은 비슷한 RMS).
        val peak441 = at441.max()
        assertTrue(abs(peak441 - at48.max()) < 0.05f * peak441, "$peak441 vs ${at48.max()}")
    }

    /** 1채널 PCM 그대로, 2채널은 평균: 왼쪽 0.8·오른쪽 0.4 사인이면 RMS는 0.6/√2. */
    @Test
    fun channelsAreMixedToMono() {
        val rate = 44_100
        val pcm = ShortArray(rate * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val gain = if (i % 2 == 0) 0.8 else 0.4
            (gain * 32767 * sin(2 * PI * 441 * t)).toInt().toShort()
        }
        val energy = energyOf(rate, 2, pcm)
        val expected = 0.6 / Math.sqrt(2.0)
        assertTrue(energy.all { abs(it - expected) < 0.01 }, "rms ${energy.first()} expected $expected")
    }

    @Test
    fun floatPcmIsRead() {
        val rate = 48_000
        val floats = FloatArray(rate) { (0.5 * sin(2 * PI * 480 * it / rate)).toFloat() }
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        bytes.asFloatBuffer().put(floats)
        val energy = MusicEnergy(rate, 1, isFloat = true, maxUs = 180_000_000L).also { it.push(bytes) }.energy()

        assertTrue(abs(energy.size - 44_100 / 512) <= 2, "frames ${energy.size}")
        assertTrue(energy.all { abs(it - 0.5 / Math.sqrt(2.0)) < 0.01 }, "rms ${energy.first()}")
    }

    /** [MusicEnergy.full]: [maxUs]만큼 받으면 더 받지 않는다(분석은 앞 180초만). */
    @Test
    fun stopsAtTheCap() {
        val rate = 48_000
        val energy = MusicEnergy(rate, 2, isFloat = false, maxUs = 3_000_000L)
        val pcm = clicks(rate, 2, seconds = 5.0, bpm = 120.0, firstS = 0.0)
        assertFalse(energy.full)
        feed(energy, pcm, 2, chunkFrames = 4_096)

        assertTrue(energy.full)
        val frames = energy.energy().size
        assertTrue(abs(frames - (3.0 * 44_100 / 512).roundToInt()) <= 2, "frames $frames")
        energy.push(ByteBuffer.allocate(4_000).order(ByteOrder.nativeOrder()))
        assertEquals(frames, energy.energy().size)
    }
}

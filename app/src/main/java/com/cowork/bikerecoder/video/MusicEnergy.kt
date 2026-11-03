package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.video.BeatFinder
import java.nio.ByteBuffer
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** 디코더가 낸 PCM(네이티브 바이트 순서)을 받는 곳([AudioTrackMaker]). [full]이면 더 풀지 않는다. */
internal interface PcmSink {
    val full: Boolean
    fun push(buffer: ByteBuffer)
}

/**
 * 박 찾기에 넘길 소리 크기(스펙 §5.2): 디코더가 낸 PCM(표본율 [sampleRate], [channels]채널 인터리브, 16비트 또는 [isFloat])을
 * 모노로 섞어(채널 평균) [BeatFinder]가 가정하는 44.1 kHz의 창 [BeatFinder.WINDOW]·간격 [BeatFinder.HOP]과 **같은 시간**을
 * 덮는 창마다 RMS(0~1)를 잰다. 창 k는 표본 round(k·HOP·rate/44100)부터 round(WINDOW·rate/44100)개. 끝에 다 차지 않은 창은 버린다.
 * 소리는 남기지 않고 창마다 값 하나만 둔다(180초면 약 1만 5천 개). [maxUs]만큼 받으면 [full].
 */
internal class MusicEnergy(private val sampleRate: Int, private val channels: Int, private val isFloat: Boolean, maxUs: Long) : PcmSink {
    init {
        require(sampleRate > 0 && channels > 0) { "$sampleRate Hz, $channels ch" }
    }

    private val windowSamples = (BeatFinder.WINDOW.toDouble() * sampleRate / BeatFinder.SAMPLE_RATE).roundToLong().coerceAtLeast(1)
    private val maxSamples = maxUs * sampleRate / 1_000_000L

    /** 다음에 들어올 모노 표본 번호. */
    private var index = 0L

    /** 열린 창은 [firstOpen] ..< [nextWindow]; 창 k의 제곱합은 [sums]\[k % [sums].size]. */
    private var firstOpen = 0
    private var nextWindow = 0
    private var nextStart = 0L
    private val sums = DoubleArray(BeatFinder.WINDOW / BeatFinder.HOP + 2)

    private var values = FloatArray(1_024)
    private var count = 0

    override val full: Boolean get() = index >= maxSamples

    private fun startOf(window: Int): Long = (window.toDouble() * BeatFinder.HOP * sampleRate / BeatFinder.SAMPLE_RATE).roundToLong()

    /** 디코더 출력 한 덩어리. [full]이 된 뒤의 표본은 버린다. */
    override fun push(buffer: ByteBuffer) {
        if (isFloat) {
            val floats = buffer.asFloatBuffer()
            while (floats.remaining() >= channels && !full) {
                var sum = 0f
                repeat(channels) { sum += floats.get() }
                add(sum / channels)
            }
        } else {
            val shorts = buffer.asShortBuffer()
            while (shorts.remaining() >= channels && !full) {
                var sum = 0f
                repeat(channels) { sum += shorts.get() / 32768f }
                add(sum / channels)
            }
        }
    }

    /** 모노 표본 하나. */
    private fun add(sample: Float) {
        while (index >= nextStart) {
            sums[nextWindow % sums.size] = 0.0
            nextWindow++
            nextStart = startOf(nextWindow)
        }
        val square = sample.toDouble() * sample
        for (w in firstOpen until nextWindow) sums[w % sums.size] += square
        index++
        while (firstOpen < nextWindow && startOf(firstOpen) + windowSamples <= index) {
            emit(sqrt(sums[firstOpen % sums.size] / windowSamples).toFloat().coerceIn(0f, 1f))
            firstOpen++
        }
    }

    private fun emit(rms: Float) {
        if (count == values.size) values = values.copyOf(values.size * 2)
        values[count++] = rms
    }

    /** 지금까지 다 찬 창들의 RMS. */
    fun energy(): FloatArray = values.copyOf(count)
}

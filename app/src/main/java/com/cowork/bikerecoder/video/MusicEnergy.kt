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

    /** 지금까지 받은 모노 표본 수. */
    val samples: Long get() = index

    /**
     * 열린 창은 [firstOpen] ..< [nextWindow]; 창 k의 제곱합은 [sums]\[k % [RING]], 끝(다음 표본 번호)은 [ends]\[k % [RING]].
     * 다음 창이 열리는 표본 [nextStart]와 가장 먼저 닫힐 창의 끝 [closeAt]을 미리 계산해 둔다.
     */
    private var firstOpen = 0
    private var nextWindow = 0
    private var nextStart = 0L
    private var closeAt = Long.MAX_VALUE
    private val sums = DoubleArray(RING)
    private val ends = LongArray(RING)

    private var values = FloatArray(1_024)
    private var count = 0

    /** 버퍼를 한 번에 읽어 두는 곳(다시 쓴다). */
    private var floats = FloatArray(0)
    private var shorts = ShortArray(0)

    override val full: Boolean get() = index >= maxSamples

    private fun startOf(window: Int): Long = (window.toDouble() * BeatFinder.HOP * sampleRate / BeatFinder.SAMPLE_RATE).roundToLong()

    /** 디코더 출력 한 덩어리. [full]이 된 뒤의 표본은 버린다. */
    override fun push(buffer: ByteBuffer) {
        if (isFloat) {
            val source = buffer.asFloatBuffer()
            val n = source.remaining() - source.remaining() % channels
            if (floats.size < n) floats = FloatArray(n)
            source.get(floats, 0, n)
            var i = 0
            while (i < n && !full) {
                var sum = 0f
                for (c in 0 until channels) sum += floats[i + c]
                add(sum / channels)
                i += channels
            }
        } else {
            val source = buffer.asShortBuffer()
            val n = source.remaining() - source.remaining() % channels
            if (shorts.size < n) shorts = ShortArray(n)
            source.get(shorts, 0, n)
            var i = 0
            while (i < n && !full) {
                var sum = 0
                for (c in 0 until channels) sum += shorts[i + c]
                add(sum / 32768f / channels)
                i += channels
            }
        }
    }

    /** 모노 표본 하나. */
    private fun add(sample: Float) {
        while (index >= nextStart) {
            val slot = nextWindow % RING
            sums[slot] = 0.0
            ends[slot] = nextStart + windowSamples
            if (firstOpen == nextWindow) closeAt = ends[slot]
            nextWindow++
            nextStart = startOf(nextWindow)
        }
        val square = sample.toDouble() * sample
        for (w in firstOpen until nextWindow) sums[w % RING] += square
        index++
        while (index >= closeAt) {
            emit(sqrt(sums[firstOpen % RING] / windowSamples).toFloat().coerceIn(0f, 1f))
            firstOpen++
            closeAt = if (firstOpen < nextWindow) ends[firstOpen % RING] else Long.MAX_VALUE
        }
    }

    private fun emit(rms: Float) {
        if (count == values.size) values = values.copyOf(values.size * 2)
        values[count++] = rms
    }

    /** 지금까지 다 찬 창들의 RMS. */
    fun energy(): FloatArray = values.copyOf(count)

    private companion object {
        /** 한 번에 열려 있을 수 있는 창 수의 상한(창이 간격의 두 배라 많아야 3개). */
        const val RING = BeatFinder.WINDOW / BeatFinder.HOP + 2
    }
}

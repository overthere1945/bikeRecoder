package com.cowork.bikerecoder.video

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.cowork.bikerecoder.core.video.AudioPlanner
import com.cowork.bikerecoder.core.video.BeatFinder
import com.cowork.bikerecoder.core.video.Beats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

/**
 * 음악 분석 결과(V1.11.0 스펙 §5.2): [beats] = 박(뚜렷하지 않으면 null), [firstSoundUs] = 처음 소리가 나는 음악 파일 시각
 * (박이 없을 때 음악을 여기서부터 튼다).
 */
data class MusicAnalysis(val beats: Beats?, val firstSoundUs: Long)

/**
 * 배경음악 트랙(스펙 §3.1⑤, §3.4): 고른 파일을 MediaExtractor + MediaCodec으로 PCM까지 풀고, 44.1 kHz 스테레오로 바꿔
 * 시작점부터 영상 길이로 자른 뒤(짧으면 반복하지 않고 그 길이까지) 마지막 2초를 서서히 줄여([AudioPlanner]) AAC 128 kbps로
 * 다시 인코딩한다. 결과는 메모리에 두었다가 [VideoEncoder.addAudio]로 같은 MP4에 넣는다(2분이면 약 2 MB).
 * 계획 전에는 [analyze]로 박을 찾는다(V1.11.0 스펙 §5).
 * 파일을 열거나 풀 수 없으면 [Unreadable] — 화면은 "음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?"를 보인다.
 */
class AudioTrackMaker(private val context: Context) {

    /** AAC 한 덩어리. [flags]는 MediaCodec 버퍼 플래그(muxer에 그대로 넘긴다). */
    class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** 인코딩한 음악 트랙: muxer에 넣을 [format](csd 포함), 시각 순 [samples], 길이 [durationUs]. */
    class Encoded(val format: MediaFormat, val samples: List<Sample>, val durationUs: Long)

    /** 음악 파일을 열거나 풀 수 없다. */
    class Unreadable(cause: Throwable? = null) : Exception("music file cannot be read", cause)

    /**
     * 음악 파일 [startUs](박에 맞춘 시작점, 박이 없으면 처음 소리가 나는 때)부터 영상 길이 [videoDurationUs]만큼의 트랙.
     * [startUs]가 곡 끝 뒤면 쓸 소리가 없어 [Unreadable].
     */
    suspend fun make(uri: Uri, videoDurationUs: Long, startUs: Long = 0): Encoded = withContext(Dispatchers.Default) {
        require(videoDurationUs > 0) { "video duration $videoDurationUs" }
        require(startUs >= 0) { "start $startUs" }
        val pcm = Pcm(capacity = framesOf(videoDurationUs), skip = framesOf(startUs))
        readable { decode(uri) { format -> Resampler(format, pcm) } }
        if (pcm.frames == 0) throw Unreadable()
        val plan = AudioPlanner.plan(musicDurationUs = startUs + usOf(pcm.frames), videoDurationUs = videoDurationUs, startUs = startUs)
        val takeFrames = minOf(pcm.frames, framesOf(plan.takeUs))
        pcm.fadeOut(fromFrame = framesOf(plan.fadeStartUs), toFrame = takeFrames)
        encode(pcm, takeFrames)
    }

    /**
     * 박 찾기(V1.11.0 스펙 §5.2): 앞 [ANALYSIS_US](180초)까지 풀어 모노 소리 크기([MusicEnergy])만 남기고(PCM은 두지 않는다)
     * [BeatFinder.find]·[BeatFinder.firstSoundUs].
     */
    suspend fun analyze(uri: Uri): MusicAnalysis = withContext(Dispatchers.Default) {
        var meter: MusicEnergy? = null
        var meterFormat: Triple<Int, Int, Boolean>? = null
        readable {
            decode(uri) { format ->
                // 형식이 그대로면 이어서 잰다(출력 형식 알림은 보통 첫 출력 전에 한 번).
                val pcm = pcmFormat(format)
                meter?.takeIf { pcm == meterFormat }
                    ?: MusicEnergy(pcm.first, pcm.second, pcm.third, ANALYSIS_US).also { meter = it; meterFormat = pcm }
            }
        }
        val energy = meter?.energy()
        if (energy == null || energy.isEmpty()) throw Unreadable()
        MusicAnalysis(BeatFinder.find(energy), BeatFinder.firstSoundUs(energy))
    }

    /** 풀다가 난 예외는 [Unreadable]로(취소는 그대로). */
    private suspend fun readable(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unreadable) {
            throw e
        } catch (e: Exception) {
            throw Unreadable(e)
        }
    }

    /**
     * [uri]의 첫 오디오 트랙을 풀어 [sinkFor]가 만든 곳에 [PcmSink.full]까지 넣는다. [sinkFor]는 디코더의 출력 형식을 알게
     * 될 때(형식이 바뀔 때마다) 불린다.
     */
    private suspend fun decode(uri: Uri, sinkFor: (MediaFormat) -> PcmSink) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Unreadable()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val info = MediaCodec.BufferInfo()
                var sink: PcmSink? = null
                var inputDone = false
                while (sink?.full != true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        sink = sinkFor(decoder.outputFormat)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val r = sink ?: sinkFor(decoder.outputFormat).also { sink = it }
                            val buffer = decoder.getOutputBuffer(o)!!
                            buffer.position(info.offset).limit(info.offset + info.size)
                            r.push(buffer.slice().order(ByteOrder.nativeOrder()))
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** [pcm]의 처음 [frames]장을 AAC로. */
    private suspend fun encode(pcm: Pcm, frames: Int): Encoded {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val samples = ArrayList<Sample>()
            var outputFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var next = 0 // 다음에 넣을 장
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buffer = encoder.getInputBuffer(i)!!
                        val count = minOf(frames - next, buffer.remaining() / BYTES_PER_FRAME)
                        val ptsUs = usOf(next)
                        if (count <= 0) {
                            encoder.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm.samples, next * CHANNELS, count * CHANNELS)
                            encoder.queueInputBuffer(i, 0, count * BYTES_PER_FRAME, ptsUs, 0)
                            next += count
                        }
                    }
                }
                val o = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.outputFormat
                } else if (o >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig) {
                        val buffer = encoder.getOutputBuffer(o)!!
                        buffer.position(info.offset).limit(info.offset + info.size)
                        val data = ByteArray(info.size).also { buffer.get(it) }
                        samples += Sample(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    encoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return Encoded(checkNotNull(outputFormat) { "AAC encoder gave no format" }, samples, usOf(frames))
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    /** 44.1 kHz 스테레오 16비트 PCM: 앞 [skip]장은 버리고 그 뒤 [capacity]장까지 모은다(필요한 만큼만 늘린다). */
    private class Pcm(private val capacity: Int, private var skip: Int = 0) {
        var samples = ShortArray(minOf(capacity, SAMPLE_RATE) * CHANNELS)
            private set
        var frames = 0
            private set
        val full: Boolean get() = frames >= capacity

        fun add(left: Short, right: Short) {
            if (full) return
            if (skip > 0) {
                skip--
                return
            }
            if (frames * CHANNELS + 1 >= samples.size) {
                samples = samples.copyOf(minOf(capacity, samples.size / CHANNELS * 2) * CHANNELS)
            }
            samples[frames * CHANNELS] = left
            samples[frames * CHANNELS + 1] = right
            frames++
        }

        /** [fromFrame]부터 [toFrame]까지 1에서 0으로 고르게 줄인다. */
        fun fadeOut(fromFrame: Int, toFrame: Int) {
            val length = toFrame - fromFrame
            if (length <= 0) return
            for (f in fromFrame until toFrame) {
                val gain = (toFrame - f).toFloat() / length
                for (c in 0 until CHANNELS) {
                    val i = f * CHANNELS + c
                    samples[i] = (samples[i] * gain).toInt().toShort()
                }
            }
        }
    }

    /**
     * 디코더 출력(아무 표본율, 1채널 이상, 16비트 또는 float)을 44.1 kHz 스테레오로 바꿔 [out]에 넣는다. 선형 보간.
     * 1채널은 양쪽에 같게, 3채널 이상은 앞의 두 채널(왼쪽·오른쪽)을 쓴다.
     */
    private class Resampler(format: MediaFormat, private val out: Pcm) : PcmSink {
        private val inRate: Int
        private val channels: Int
        private val isFloat: Boolean

        init {
            val (rate, count, float) = pcmFormat(format)
            inRate = rate
            channels = count
            isFloat = float
        }

        private val step = inRate.toDouble() / SAMPLE_RATE
        private var inIndex = 0L // 다음에 들어올 입력 장 번호
        private var outIndex = 0L // 다음에 낼 출력 장 번호
        private var prevLeft = 0f
        private var prevRight = 0f

        override val full: Boolean get() = out.full

        override fun push(buffer: ByteBuffer) {
            if (isFloat) {
                val floats = buffer.asFloatBuffer()
                while (floats.remaining() >= channels && !out.full) {
                    val left = floats.get()
                    val right = if (channels > 1) floats.get() else left
                    repeat(channels - minOf(channels, 2)) { floats.get() }
                    frame(left, right)
                }
            } else {
                val shorts = buffer.asShortBuffer()
                while (shorts.remaining() >= channels && !out.full) {
                    val left = shorts.get() / 32768f
                    val right = if (channels > 1) shorts.get() / 32768f else left
                    repeat(channels - minOf(channels, 2)) { shorts.get() }
                    frame(left, right)
                }
            }
        }

        /** 입력 장 [inIndex] 하나: 앞 장과 이 장 사이에 드는 출력 장을 보간해 낸다. */
        private fun frame(left: Float, right: Float) {
            if (inIndex == 0L) {
                prevLeft = left
                prevRight = right
            }
            while (!out.full) {
                val position = outIndex * step // 출력 장의 입력 위치
                if (position > inIndex) break
                val t = (position - (inIndex - 1)).toFloat().coerceIn(0f, 1f)
                out.add(toShort(prevLeft + (left - prevLeft) * t), toShort(prevRight + (right - prevRight) * t))
                outIndex++
            }
            prevLeft = left
            prevRight = right
            inIndex++
        }

        private fun toShort(v: Float): Short = (v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
    }

    private companion object {
        /** 박 찾기에 푸는 길이: 앞 180초. */
        const val ANALYSIS_US = 180_000_000L

        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val BIT_RATE = 128_000
        const val BYTES_PER_FRAME = CHANNELS * 2
        const val TIMEOUT_US = 5_000L

        /**
         * 디코더 출력 형식: (표본율, 채널 수, float인가). 16비트·float가 아니거나 표본율·채널 수가 없으면 [Unreadable].
         */
        fun pcmFormat(format: MediaFormat): Triple<Int, Int, Boolean> {
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    throw Unreadable(IllegalArgumentException("PCM encoding $encoding"))
                }
                isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
            }
            if (rate <= 0 || channels <= 0) throw Unreadable(IllegalArgumentException("$rate Hz, $channels ch"))
            return Triple(rate, channels, isFloat)
        }

        fun framesOf(us: Long): Int = (us * SAMPLE_RATE / 1_000_000L).toInt()
        fun usOf(frames: Int): Long = frames * 1_000_000L / SAMPLE_RATE
    }
}

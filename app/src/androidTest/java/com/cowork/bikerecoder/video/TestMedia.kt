package com.cowork.bikerecoder.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** 기기 테스트용 미디어 도구: 사인파 음원을 직접 만들어(저작권 파일 없음) M4A로 쓰고, 만든 MP4를 읽어 확인한다. */
object TestMedia {

    private const val TIMEOUT_US = 10_000L

    fun openForWrite(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(
        file,
        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
    )

    /** [seconds]초 길이의 [freqHz] 사인파(진폭 0.5)를 AAC로 인코딩해 [file]에 M4A로 쓴다. */
    fun writeSineM4a(file: File, seconds: Double, sampleRate: Int, channels: Int, freqHz: Double = 440.0) {
        val frames = (seconds * sampleRate).toInt()
        val pcm = ShortArray(frames * channels) { i ->
            val t = (i / channels).toDouble() / sampleRate
            (0.5 * 32767 * sin(2 * PI * freqHz * t)).toInt().toShort()
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        openForWrite(file).use { pfd ->
            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var offset = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = minOf(pcm.size - offset, buf.remaining() / 2)
                        val pts = (offset / channels).toLong() * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                            offset += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (o >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()
        }
    }

    class Track(val format: MediaFormat, val sampleCount: Int, val syncIndices: List<Int>, val lastPtsUs: Long) {
        val durationUs: Long get() = format.getLong(MediaFormat.KEY_DURATION)
    }

    /** [file]에서 [mimePrefix]("video/" 또는 "audio/")로 시작하는 트랙들. */
    fun tracks(file: File, mimePrefix: String): List<Track> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount)
                .filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(mimePrefix) }
                .map { index ->
                    extractor.unselectAllTracks()
                    extractor.selectTrack(index)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var count = 0
                    var last = -1L
                    val sync = mutableListOf<Int>()
                    while (extractor.sampleTrackIndex >= 0) {
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) sync += count
                        last = extractor.sampleTime
                        count++
                        extractor.advance()
                    }
                    Track(extractor.getTrackFormat(index), count, sync, last)
                }
        } finally {
            extractor.release()
        }
    }

    private fun MediaExtractor.unselectAllTracks() = (0 until trackCount).forEach { unselectTrack(it) }

    class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
        val frames: Int get() = samples.size / channels

        /** [fromS]~[toS]초 구간, [channel] 채널의 RMS. */
        fun rms(fromS: Double, toS: Double, channel: Int = 0): Double {
            val a = (fromS * sampleRate).toInt()
            val b = minOf((toS * sampleRate).toInt(), frames)
            var sum = 0.0
            for (f in a until b) {
                val v = samples[f * channels + channel].toDouble()
                sum += v * v
            }
            return sqrt(sum / (b - a))
        }

        /** [fromS]~[toS]초 구간에서 [channel] 채널이 음에서 양으로 지나간 횟수 = 주파수 × 길이. */
        fun upwardZeroCrossings(fromS: Double, toS: Double, channel: Int = 0): Int {
            val a = (fromS * sampleRate).toInt()
            val b = minOf((toS * sampleRate).toInt(), frames)
            var n = 0
            for (f in a + 1 until b) {
                if (samples[(f - 1) * channels + channel] < 0 && samples[f * channels + channel] >= 0) n++
            }
            return n
        }
    }

    /** 영상 한 장의 가운데 화소(Y, U, V)와 그 장의 시각. */
    class CenterYuv(val ptsUs: Long, val y: Int, val u: Int, val v: Int)

    /** [file]의 비디오 트랙을 MediaCodec으로 모두 풀어 장마다 가운데 화소를 돌려준다(MediaMetadataRetriever는 S22에서 정지 장면의 P 프레임을 빈 장으로 풀어 쓰지 않는다). */
    fun decodeVideoCenters(file: File): List<CenterYuv> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val index = (0 until extractor.trackCount)
            .first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = mutableListOf<CenterYuv>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (o >= 0) {
                if (info.size > 0) {
                    val image = codec.getOutputImage(o)!!
                    val cx = image.width / 2
                    val cy = image.height / 2
                    fun sample(plane: Int, x: Int, y: Int): Int {
                        val p = image.planes[plane]
                        return p.buffer.get(y * p.rowStride + x * p.pixelStride).toInt() and 0xff
                    }
                    out += CenterYuv(info.presentationTimeUs, sample(0, cx, cy), sample(1, cx / 2, cy / 2), sample(2, cx / 2, cy / 2))
                    image.close()
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        return out
    }

    /** [file]의 첫 오디오 트랙을 16비트 PCM으로 풀어 낸다. */
    fun decodeAudio(file: File): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val index = (0 until extractor.trackCount)
            .first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ArrayList<Short>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                buf.position(info.offset).limit(info.offset + info.size)
                val shorts = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                while (shorts.hasRemaining()) out += shorts.get()
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        return Pcm(rate, channels, out.toShortArray())
    }
}

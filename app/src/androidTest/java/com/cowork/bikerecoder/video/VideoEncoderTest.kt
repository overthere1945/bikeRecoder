package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Color
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.video.Aspect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** 앱 캐시에 MP4를 실제로 인코딩해 MediaExtractor로 확인한다. 만든 파일은 테스트가 끝나면 지운다. */
@RunWith(AndroidJUnit4::class)
class VideoEncoderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach { it.delete() }
        files.forEach { assertFalse("left behind: $it", it.exists()) }
    }

    private fun newFile(): File = File.createTempFile("video_encoder_test", ".mp4", context.cacheDir).also { files += it }

    /**
     * 48장(2초)을 [first] 색 24장, [second] 색 23장, 마지막 1장 흰색으로 그려 [file]에 쓰고 인코더 크기를 돌려준다.
     * 장마다 색이 정해져 있어 빠지거나 밀린 장, 끝에서 잘린 장을 잡는다.
     */
    private fun encode(file: File, aspect: Aspect, first: Int, second: Int, forceFallback: Boolean = false): Pair<Int, Int> {
        TestMedia.openForWrite(file).use { pfd ->
            val (encoder, fellBack) = VideoEncoder.open(pfd.fileDescriptor, aspect, forceFallback)
            encoder.use {
                assertEquals(forceFallback, fellBack)
                repeat(48) { i -> encoder.drawFrame { canvas -> canvas.drawColor(colorOf(i, first, second)) } }
                encoder.finish()
            }
            return encoder.width to encoder.height
        }
    }

    private fun assertVideo(file: File, width: Int, height: Int) {
        val video = TestMedia.tracks(file, "video/").single()
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, video.format.getString(MediaFormat.KEY_MIME))
        assertEquals(width, video.format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(height, video.format.getInteger(MediaFormat.KEY_HEIGHT))
        assertTrue("frames ${video.sampleCount}", abs(video.sampleCount - 48) <= 1)
        assertTrue("duration ${video.durationUs}", abs(video.durationUs - 2_000_000L) <= 100_000L)
        // 프레임 시각 = index / 24: 마지막 장(47번)은 1.958초.
        assertTrue("last pts ${video.lastPtsUs}", abs(video.lastPtsUs - 47 * 1_000_000L / 24) <= 1_000L)
        // 키프레임 1초마다: 0번과 24번 근처.
        assertEquals(0, video.syncIndices.first())
        assertTrue("sync ${video.syncIndices}", video.syncIndices.any { it in 23..25 })
        assertTrue("no audio", TestMedia.tracks(file, "audio/").isEmpty())
    }

    private fun colorOf(index: Int, first: Int, second: Int): Int = when {
        index < 24 -> first
        index < 47 -> second
        else -> Color.WHITE
    }

    /** 48장 모두 풀어, 장마다 가운데 색이 그 순번에 그린 색과 같다(빠지거나 밀린 장, 끝에서 잘린 장이 없다). */
    private fun assertFrameColors(file: File, first: Int, second: Int) {
        val frames = TestMedia.decodeVideoCenters(file)
        assertEquals(48, frames.size)
        frames.forEach { frame ->
            val index = Math.round(frame.ptsUs * 24 / 1_000_000.0).toInt()
            val expected = yuvOf(colorOf(index, first, second))
            val actual = intArrayOf(frame.y, frame.u, frame.v)
            assertTrue(
                "frame $index: expected YUV ${expected.toList()} got ${actual.toList()}",
                expected.indices.all { abs(expected[it] - actual[it]) <= 30 },
            )
        }
    }

    /** BT.601 제한 범위 YUV(인코더가 쓰는 변환과 몇 단계 차이는 허용 범위 안). */
    private fun yuvOf(color: Int): IntArray {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return intArrayOf(
            (16 + 65.481 * r + 128.553 * g + 24.966 * b).toInt(),
            (128 - 37.797 * r - 74.203 * g + 112.0 * b).toInt(),
            (128 + 112.0 * r - 93.786 * g - 18.214 * b).toInt(),
        )
    }

    @Test
    fun twoSecondPortraitVideoOpens() {
        val file = newFile()
        assertEquals(1080 to 1920, encode(file, Aspect.PORTRAIT, Color.RED, Color.BLUE))
        assertVideo(file, 1080, 1920)
        assertFrameColors(file, Color.RED, Color.BLUE)
    }

    @Test
    fun landscapeVideoOpens() {
        val file = newFile()
        assertEquals(1920 to 1080, encode(file, Aspect.LANDSCAPE, Color.BLUE, Color.GREEN))
        assertVideo(file, 1920, 1080)
        assertFrameColors(file, Color.BLUE, Color.GREEN)
    }

    @Test
    fun fallsBackTo720WhenAsked() {
        val portrait = newFile()
        assertEquals(720 to 1280, encode(portrait, Aspect.PORTRAIT, Color.GREEN, Color.RED, forceFallback = true))
        assertVideo(portrait, 720, 1280)
        assertFrameColors(portrait, Color.GREEN, Color.RED)

        val landscape = newFile()
        assertEquals(1280 to 720, encode(landscape, Aspect.LANDSCAPE, Color.RED, Color.GREEN, forceFallback = true))
        assertVideo(landscape, 1280, 720)
        assertFrameColors(landscape, Color.RED, Color.GREEN)
    }
}

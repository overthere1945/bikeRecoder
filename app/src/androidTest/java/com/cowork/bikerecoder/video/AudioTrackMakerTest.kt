package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Color
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.video.Aspect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** 테스트가 사인파 음원을 직접 만들어 쓴다(저작권 파일 없음). 만든 파일은 앱 캐시에 두고 테스트가 끝나면 지운다. */
@RunWith(AndroidJUnit4::class)
class AudioTrackMakerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val maker = AudioTrackMaker(context)
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach { it.delete() }
        files.forEach { assertFalse("left behind: $it", it.exists()) }
    }

    private fun newFile(suffix: String): File =
        File.createTempFile("audio_track_maker_test", suffix, context.cacheDir).also { files += it }

    private fun sine(seconds: Double, sampleRate: Int = 44_100, channels: Int = 2): File =
        newFile(".m4a").also { TestMedia.writeSineM4a(it, seconds, sampleRate, channels) }

    /** 2초(48장) 영상에 [music]을 넣어 MP4를 만든다. */
    private fun videoWith(music: AudioTrackMaker.Encoded?): File {
        val file = newFile(".mp4")
        TestMedia.openForWrite(file).use { pfd ->
            VideoEncoder.open(pfd.fileDescriptor, Aspect.PORTRAIT).first.use { encoder ->
                encoder.addAudio(music)
                repeat(48) { encoder.drawFrame { it.drawColor(Color.DKGRAY) } }
                encoder.finish()
            }
        }
        return file
    }

    private fun assertAac441Stereo(format: MediaFormat) {
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
        assertEquals(44_100, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
    }

    /**
     * 기본 허용 0.1초: AAC는 한 번 거칠 때마다 앞뒤 채움(priming 2048 표본 + 마지막 프레임 채움, 약 0.05~0.07초)만큼 길어진다
     * (1초 사인파 음원을 풀면 약 1.07초, 다시 AAC로 넣은 트랙은 거기에 약 0.05초 더).
     */
    private fun assertAbout(expectedUs: Long, actualUs: Long, toleranceUs: Long = 100_000L) =
        assertTrue("expected ~$expectedUs, got $actualUs", abs(expectedUs - actualUs) <= toleranceUs)

    @Test
    fun musicTrackTrimmedAndFaded() {
        val music = runBlocking { maker.make(Uri.fromFile(sine(5.0)), 2_000_000L) }
        assertAbout(2_000_000L, music.durationUs, 1_000L)
        assertAac441Stereo(music.format)

        val video = videoWith(music)
        val audio = TestMedia.tracks(video, "audio/").single()
        assertAac441Stereo(audio.format)
        assertAbout(2_000_000L, audio.durationUs)
        assertAbout(2_000_000L, TestMedia.tracks(video, "video/").single().durationUs)

        // 2초 영상이면 페이드가 처음부터 끝까지: 앞부분은 크게, 마지막 구간은 거의 0까지 줄어든다.
        val pcm = TestMedia.decodeAudio(video)
        val early = pcm.rms(0.15, 0.45)
        val late = pcm.rms(1.65, 1.95)
        assertTrue("early $early", early > 0.5 * 0.707 * 32767 * 0.6)
        assertTrue("early $early late $late", late < early * 0.3)
        assertTrue("right channel faded too", pcm.rms(1.65, 1.95, channel = 1) < pcm.rms(0.15, 0.45, channel = 1) * 0.3)
    }

    @Test
    fun shortMusicEndsEarly() {
        val music = runBlocking { maker.make(Uri.fromFile(sine(1.0)), 2_000_000L) }
        assertAbout(1_000_000L, music.durationUs)

        val video = videoWith(music)
        // 반복하거나 영상 길이(2초)까지 늘리지 않는다: 트랙 = 음악 길이.
        assertAbout(music.durationUs, TestMedia.tracks(video, "audio/").single().durationUs)
        assertAbout(2_000_000L, TestMedia.tracks(video, "video/").single().durationUs)
    }

    @Test
    fun monoFortyEightKhzIsConverted() {
        val music = runBlocking { maker.make(Uri.fromFile(sine(1.5, sampleRate = 48_000, channels = 1)), 2_000_000L) }
        assertAac441Stereo(music.format)
        assertAbout(1_500_000L, music.durationUs)

        val video = videoWith(music)
        val audio = TestMedia.tracks(video, "audio/").single()
        assertAac441Stereo(audio.format)
        assertAbout(music.durationUs, audio.durationUs)

        // 음 높이가 그대로(440 Hz): 0.2~0.7초에 약 220번. 리샘플을 빼먹으면 44.1/48배(약 404 Hz, 약 202번)가 된다.
        val pcm = TestMedia.decodeAudio(video)
        assertEquals(2, pcm.channels)
        val left = pcm.upwardZeroCrossings(0.2, 0.7, channel = 0)
        val right = pcm.upwardZeroCrossings(0.2, 0.7, channel = 1)
        assertTrue("left $left", left in 218..222)
        assertTrue("right $right", right in 218..222)
        assertTrue("both channels carry the mono signal", pcm.rms(0.2, 0.4, channel = 1) > 3_000)
    }

    @Test
    fun unreadableMusicThrows() {
        val text = newFile(".m4a").apply { writeText("이것은 음악 파일이 아닙니다") }
        assertThrows(AudioTrackMaker.Unreadable::class.java) {
            runBlocking { maker.make(Uri.fromFile(text), 2_000_000L) }
        }
    }
}

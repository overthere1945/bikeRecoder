package com.cowork.bikerecoder.video

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 앱에 넣은 기본 음악 세 곡(tools/music/README.md)의 박을 휴대폰 디코더로 풀어 찾는다(스펙 §5.2): 빠르기는 만든 값 ±1박,
 * 첫 박부터 소리가 나므로 첫 박은 0.1초 안.
 */
@RunWith(AndroidJUnit4::class)
class BuiltInMusicBeatsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val maker = AudioTrackMaker(context)

    /** 곡 [res]를 앱이 고르는 것과 같은 `android.resource://…/raw/이름`으로 연다. */
    private fun assertBeats(res: Int, bpm: Double) {
        val name = context.resources.getResourceEntryName(res)
        val uri = Uri.parse("android.resource://${context.packageName}/raw/$name")
        val started = SystemClock.elapsedRealtime()
        val beats = runBlocking { maker.analyze(uri) }.beats
        // 분석 시간(스펙 §5.2, 판정 P12: 150초 곡에 2초 이하 목표). logcat 태그 BuiltInMusicBeats.
        Log.i(TAG, "$name analysed in ${SystemClock.elapsedRealtime() - started} ms")
        assertNotNull("$name: no beat", beats)
        val found = 60_000_000.0 / beats!!.beatUs
        assertTrue("$name: $found BPM, expected $bpm", abs(found - bpm) <= 1.0)
        assertTrue("$name: first beat ${beats.startUs} µs", beats.startUs < 100_000)
    }

    @Test
    fun sunnyPedal() = assertBeats(R.raw.music_sunny_pedal, 124.0)

    @Test
    fun windRoad() = assertBeats(R.raw.music_wind_road, 112.0)

    @Test
    fun overTheHill() = assertBeats(R.raw.music_over_the_hill, 132.0)

    private companion object {
        const val TAG = "BuiltInMusicBeats"
    }
}

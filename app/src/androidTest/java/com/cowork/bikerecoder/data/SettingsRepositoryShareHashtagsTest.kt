package com.cowork.bikerecoder.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.activity.ActivityType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 기기 Context의 DataStore를 쓰므로 기기에서 돌린다. 시험 앞뒤로 값을 지워 다른 시험·앱 설정에 남기지 않는다. */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryShareHashtagsTest {
    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    // update로 null을 쓰면 키가 지워진다 = 저장된 적 없음.
    @Before
    fun clearBefore() = runBlocking { clearAll() }

    @After
    fun clearAfter() = runBlocking { clearAll() }

    private suspend fun clearAll() = repo.update {
        it.copy(shareHashtags = null, shareHashtagsWalk = null, shareHashtagsRun = null, shareHashtagsHike = null)
    }

    @Test
    fun defaultsToNull() = runBlocking {
        assertNull(repo.settings.first().shareHashtags)
    }

    @Test
    fun emptyIsKeptAsEmpty() = runBlocking {
        repo.setShareHashtags("")
        assertEquals("", repo.settings.first().shareHashtags)
    }

    @Test
    fun valueRoundTrips() = runBlocking {
        repo.setShareHashtags("#자출")
        assertEquals("#자출", repo.settings.first().shareHashtags)
    }

    @Test
    fun otherSettingsSurviveUpdate() = runBlocking {
        repo.setShareHashtags("#자출")
        repo.update { it.copy(voiceEnabled = !it.voiceEnabled) }
        assertEquals("#자출", repo.settings.first().shareHashtags)
        repo.update { it.copy(voiceEnabled = !it.voiceEnabled) }
    }

    @Test
    fun defaultsPerType() = runBlocking {
        val settings = repo.settings.first()
        assertNull(settings.shareHashtagsWalk)
        assertNull(settings.shareHashtagsRun)
        assertNull(settings.shareHashtagsHike)
        assertEquals("#자전거 #라이딩", settings.hashtagsFor(ActivityType.BIKE))
        assertEquals("#걷기 #산책", settings.hashtagsFor(ActivityType.WALK))
        assertEquals("#러닝 #달리기", settings.hashtagsFor(ActivityType.RUN))
        assertEquals("#등산 #하이킹", settings.hashtagsFor(ActivityType.HIKE))

        // Each activity keeps its own value; "" means none and does not fall back to the first value.
        repo.setShareHashtags(ActivityType.WALK, "#동네한바퀴")
        repo.setShareHashtags(ActivityType.RUN, "")
        val saved = repo.settings.first()
        assertEquals("#동네한바퀴", saved.hashtagsFor(ActivityType.WALK))
        assertEquals("", saved.hashtagsFor(ActivityType.RUN))
        assertEquals("#등산 #하이킹", saved.hashtagsFor(ActivityType.HIKE))
        assertNull(saved.shareHashtags)

        // update() writes all four back and a null removes the key again.
        repo.update { it.copy(shareHashtagsWalk = null) }
        assertEquals("#걷기 #산책", repo.settings.first().hashtagsFor(ActivityType.WALK))
        assertEquals("", repo.settings.first().hashtagsFor(ActivityType.RUN))
    }

    @Test
    fun bikeKeyUnchanged() = runBlocking {
        // The bike's value is still the one `share_hashtags` slot: the old call and the typed call share it, and the
        // other activities' values never touch it.
        repo.setShareHashtags("#자출")
        assertEquals("#자출", repo.settings.first().hashtagsFor(ActivityType.BIKE))
        repo.setShareHashtags(ActivityType.WALK, "#걷자")
        repo.setShareHashtags(ActivityType.HIKE, "#산")
        assertEquals("#자출", repo.settings.first().shareHashtags)

        repo.setShareHashtags(ActivityType.BIKE, "#출근")
        assertEquals("#출근", repo.settings.first().shareHashtags)
        assertEquals("#걷자", repo.settings.first().shareHashtagsWalk)
        assertEquals("#산", repo.settings.first().shareHashtagsHike)
    }
}

package com.cowork.bikerecoder.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun clearBefore() = runBlocking { repo.update { it.copy(shareHashtags = null) } }

    @After
    fun clearAfter() = runBlocking { repo.update { it.copy(shareHashtags = null) } }

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
}

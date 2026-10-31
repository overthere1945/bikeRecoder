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

/** 기기 Context의 DataStore를 쓰므로 기기에서 돌린다. 시험 앞뒤로 목표를 비워 다른 시험·앱 설정에 남기지 않는다. */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryGoalsTest {
    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Before
    fun clearBefore() = runBlocking { repo.setGoals(null, null) }

    @After
    fun clearAfter() = runBlocking { repo.setGoals(null, null) }

    @Test
    fun goalsRoundTrip() = runBlocking {
        assertNull(repo.settings.first().weeklyGoalKm)
        assertNull(repo.settings.first().yearlyGoalKm)

        repo.setGoals(weeklyKm = 100, yearlyKm = 5_000)
        assertEquals(100, repo.settings.first().weeklyGoalKm)
        assertEquals(5_000, repo.settings.first().yearlyGoalKm)

        repo.setGoals(weeklyKm = null, yearlyKm = 4_000)
        assertNull(repo.settings.first().weeklyGoalKm)
        assertEquals(4_000, repo.settings.first().yearlyGoalKm)

        repo.setGoals(weeklyKm = null, yearlyKm = null)
        assertNull(repo.settings.first().weeklyGoalKm)
        assertNull(repo.settings.first().yearlyGoalKm)
    }

    @Test
    fun otherSettingsSurviveGoalChanges() = runBlocking {
        val before = repo.settings.first().copy(weeklyGoalKm = null, yearlyGoalKm = null)

        repo.setGoals(weeklyKm = 50, yearlyKm = null)

        assertEquals(before.copy(weeklyGoalKm = 50), repo.settings.first())
    }
}

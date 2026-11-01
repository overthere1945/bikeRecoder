package com.cowork.bikerecoder.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.ui.stats.GoalKm
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
    fun clearBefore() = clear()

    @After
    fun clearAfter() = clear()

    private fun clear() = runBlocking {
        for (type in ActivityType.entries) repo.setGoals(type, null, null)
        repo.setStatsActivity(null)
    }

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

    /** V1.9.0 spec §4.2: the bike goal is the goal that was always stored, under the old keys. */
    @Test
    fun bikeGoalKeepsOldKeys() = runBlocking {
        repo.setGoals(weeklyKm = 120, yearlyKm = 3_000)
        val settings = repo.settings.first()
        assertEquals(120, settings.weeklyGoalKm)
        assertEquals(3_000, settings.yearlyGoalKm)
        assertEquals(mapOf(ActivityType.BIKE to GoalKm(120, 3_000)), settings.goals)
        assertEquals(emptyMap<ActivityType, GoalKm>(), settings.otherGoals)

        // The activity form for the bike writes the same keys.
        repo.setGoals(ActivityType.BIKE, 80, null)
        assertEquals(80, repo.settings.first().weeklyGoalKm)
        assertNull(repo.settings.first().yearlyGoalKm)

        // Read again by a new repository over the same file: the old two keys are the bike's goal.
        val again = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(GoalKm(80, null), again.settings.first().goals.getValue(ActivityType.BIKE))
    }

    @Test
    fun walkGoalUsesOwnKeys() = runBlocking {
        repo.setGoals(ActivityType.WALK, 20, 500)
        repo.setGoals(ActivityType.RUN, null, 800)
        val settings = repo.settings.first()
        assertNull(settings.weeklyGoalKm)
        assertNull(settings.yearlyGoalKm)
        assertEquals(GoalKm(20, 500), settings.otherGoals[ActivityType.WALK])
        assertEquals(GoalKm(null, 800), settings.otherGoals[ActivityType.RUN])
        assertNull(settings.otherGoals[ActivityType.HIKE])

        repo.setGoals(ActivityType.WALK, null, null)
        assertNull(repo.settings.first().otherGoals[ActivityType.WALK])
        assertEquals(GoalKm(null, 800), repo.settings.first().goals[ActivityType.RUN])
    }

    @Test
    fun statsActivityRoundTrips() = runBlocking {
        assertNull(repo.settings.first().statsActivity)
        repo.setStatsActivity(ActivityType.HIKE)
        assertEquals(ActivityType.HIKE, repo.settings.first().statsActivity)
        repo.setStatsActivity(null)
        assertNull(repo.settings.first().statsActivity)
    }
}

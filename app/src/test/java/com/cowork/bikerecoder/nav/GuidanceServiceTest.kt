package com.cowork.bikerecoder.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceServiceTest {

    @Test
    fun theServiceStopsOnceTheArrivalAnnouncementIsSpoken() = runTest {
        val speaking = MutableStateFlow(true) // "목적지에 도착했습니다" is queued
        launch {
            delay(2_000)
            speaking.value = false
        }

        awaitVoiceIdle(speaking, maxWaitMs = 5_000)

        assertEquals(2_000, currentTime)
    }

    @Test
    fun aVoiceThatNeverFinishesDelaysTheStopByAtMostTheLimit() = runTest {
        awaitVoiceIdle(MutableStateFlow(true), maxWaitMs = 5_000)

        assertEquals(5_000, currentTime)
    }

    @Test
    fun anIdleVoiceDoesNotDelayTheStop() = runTest {
        awaitVoiceIdle(MutableStateFlow(false), maxWaitMs = 5_000)

        assertEquals(0, currentTime)
    }

    @Test
    fun aFailedForegroundStartIsReportedUntilTheNextSuccessfulOne() {
        val status = GuidanceServiceStatus()
        assertNull(status.warning.value)

        status.foregroundFailed()
        assertEquals("화면이 꺼지면 안내가 멈출 수 있습니다", status.warning.value)

        status.foregroundStarted()
        assertNull(status.warning.value)
    }
}

package com.cowork.bikerecoder.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackThrottleTest {

    @Test
    fun theFirstValueGoesAtOnceThenTheLatestAtMostEveryPeriod() = runTest {
        // A new track every second for 12 s (values 0..12).
        val source = flow {
            for (i in 0..12) {
                emit(i)
                if (i < 12) delay(1_000)
            }
        }
        val out = mutableListOf<Pair<Long, Int>>()

        source.atMostEvery(4_700).collect { out += currentTime to it }

        assertEquals(listOf(0L to 0, 4_700L to 4, 9_400L to 9, 14_100L to 12), out, "the last value is never lost")
    }

    @Test
    fun aQuietSourceIsNotDelayed() = runTest {
        val out = mutableListOf<Pair<Long, Int>>()
        flow {
            emit(1)
            delay(20_000)
            emit(2)
        }.atMostEvery(5_000).collect { out += currentTime to it }

        assertEquals(listOf(0L to 1, 20_000L to 2), out)
    }
}

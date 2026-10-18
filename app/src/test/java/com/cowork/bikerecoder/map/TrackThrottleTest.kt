package com.cowork.bikerecoder.map

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

        source.atMostEveryUnless(4_700, { currentTime }) { _, _ -> false }.collect { out += currentTime to it }

        assertEquals(listOf(0L to 0, 4_700L to 4, 9_400L to 9, 14_100L to 12), out, "the last value is never lost")
    }

    @Test
    fun aQuietSourceIsNotDelayed() = runTest {
        val out = mutableListOf<Pair<Long, Int>>()
        flow {
            emit(1)
            delay(20_000)
            emit(2)
        }.atMostEveryUnless(5_000, { currentTime }) { _, _ -> false }.collect { out += currentTime to it }

        assertEquals(listOf(0L to 1, 20_000L to 2), out)
    }

    @Test
    fun aChangeThatMustShowAtOnceIsNotHeldBack() = runTest {
        // Live updates within one ten; a new ten must be drawn at once.
        val source = flow {
            emit(1)
            delay(1_000)
            emit(2)
            delay(1_000)
            emit(10)
            delay(1_000)
            emit(11)
        }
        val out = mutableListOf<Pair<Long, Int>>()

        source.atMostEveryUnless(5_000, { currentTime }) { prev, next -> prev / 10 != next / 10 }
            .collect { out += currentTime to it }

        assertEquals(listOf(0L to 1, 2_000L to 10, 7_000L to 11), out, "10 at once; 11 is a live update again")
    }

    @Test
    fun onlyAGrowingTrackIsThrottled() {
        val a = GeoPoint(37.5, 127.0)
        val b = GeoPoint(37.6, 127.0)
        val track = TrackDrawing(listOf(a), null, emptyList())
        assertFalse(redrawAtOnce(track, track.copy(track = listOf(a, b))), "the live track grows: throttled")
        assertTrue(redrawAtOnce(track, track.copy(colors = listOf("#E53935"))), "[기본] → [심박]")
        assertTrue(redrawAtOnce(track.copy(colors = listOf("#E53935")), track), "[심박] → [기본]")
        assertTrue(redrawAtOnce(track, track.copy(lines = listOf(MapLine(listOf(a, b))))), "the trip's lines")
    }
}

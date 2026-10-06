package com.cowork.bikerecoder.location

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpxLocationSourceTest {
    private fun fix(i: Int) = LocationFix(
        point = GeoPoint(37.0 + i * 0.0001, 127.0),
        accuracyM = 5f,
        speedMps = 4f,
        bearingDeg = 0f,
        timeMillis = 1_000_000L + i * 10_000L,
    )

    @Test
    fun gpxSourceEmitsAllFixesInOrderWithSpeedup() = runBlocking {
        val fixes = (0..2).map(::fix)
        val source = GpxLocationSource(fixes, speedup = 100.0)

        val start = System.nanoTime()
        val emitted = source.fixes().toList()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(fixes, emitted)
        assertEquals(listOf(1_000_000L, 1_010_000L, 1_020_000L), emitted.map { it.timeMillis })
        // 2 gaps of 10s / 100 = 200ms total; must be well under real-time (20s) and not instant.
        assertTrue("elapsed $elapsedMs ms should be < 400", elapsedMs < 400)
        assertTrue("elapsed $elapsedMs ms should be >= 150 (delays applied)", elapsedMs >= 150)
    }
}

package com.cowork.bikerecoder.location

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SharedLocationSourceTest {

    private fun fix(i: Int) = LocationFix(
        point = GeoPoint(37.5 + i * 0.0001, 127.0),
        accuracyM = 5f,
        speedMps = 3f,
        bearingDeg = null,
        timeMillis = 1_000L * i,
    )

    private val fixes = (0..5).map(::fix)

    /** The scope the shared stream lives in: a child job so the test can end it, and uncaught failures show up. */
    private fun TestScope.shareScope(): CoroutineScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))

    private fun TestScope.collectInto(shared: SharedLocationSource, into: MutableList<LocationFix>): Job =
        launch { shared.fixes().collect { into += it } }

    @Test
    fun `two subscribers share one upstream collection`() = runTest {
        val source = ScriptedLocationSource(fixes, speedup = 1.0)
        val shareScope = shareScope()
        val shared = SharedLocationSource({ source }, shareScope)
        val a = mutableListOf<LocationFix>()
        val b = mutableListOf<LocationFix>()
        val jobA = collectInto(shared, a)
        val jobB = collectInto(shared, b)

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(fixes, a)
        assertEquals(fixes, b)
        assertEquals(1, source.collections)
        jobA.cancel()
        jobB.cancel()
        shareScope.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `upstream stops when the last subscriber leaves`() = runTest {
        val source = ScriptedLocationSource(fixes, speedup = 1.0)
        val shareScope = shareScope()
        val shared = SharedLocationSource({ source }, shareScope)
        val a = mutableListOf<LocationFix>()
        val b = mutableListOf<LocationFix>()
        val jobA = collectInto(shared, a)
        val jobB = collectInto(shared, b)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(source.collecting)

        jobA.cancel()
        runCurrent()
        assertTrue(source.collecting) // one subscriber left
        jobB.cancel()
        runCurrent()
        assertFalse(source.collecting)

        val c = mutableListOf<LocationFix>()
        val jobC = collectInto(shared, c)
        runCurrent()
        assertEquals(2, source.collections)
        assertTrue(source.collecting)
        assertEquals(listOf(fixes[0]), c) // a fresh start, not the last fix of the earlier run
        jobC.cancel()
        shareScope.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `late subscriber gets the latest fix first`() = runTest {
        val source = ScriptedLocationSource(fixes, speedup = 1.0)
        val shareScope = shareScope()
        val shared = SharedLocationSource({ source }, shareScope)
        val a = mutableListOf<LocationFix>()
        val jobA = collectInto(shared, a)
        advanceTimeBy(2_500) // f0 at 0, f1 at 1000, f2 at 2000
        runCurrent()
        assertEquals(fixes.take(3), a)

        val b = mutableListOf<LocationFix>()
        val jobB = collectInto(shared, b)
        runCurrent()
        assertEquals(listOf(fixes[2]), b)

        advanceTimeBy(1_000) // f3 at 3000
        runCurrent()
        assertEquals(listOf(fixes[2], fixes[3]), b)
        assertEquals(1, source.collections)
        jobA.cancel()
        jobB.cancel()
        shareScope.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `upstream failure reaches every subscriber`() = runTest {
        val failing = object : LocationSource {
            override fun fixes(): Flow<LocationFix> = flow {
                emit(fix(1))
                throw IOException("gps")
            }
        }
        val shareScope = shareScope()
        val shared = SharedLocationSource({ failing }, shareScope)
        val results = mutableListOf<Pair<List<LocationFix>, Throwable?>>()
        repeat(2) {
            launch {
                val got = mutableListOf<LocationFix>()
                var error: Throwable? = null
                shared.fixes().catch { error = it }.collect { got += it }
                results += got to error
            }
        }
        runCurrent()

        assertEquals(2, results.size)
        results.forEach { (got, error) ->
            assertEquals(listOf(fix(1)), got)
            assertTrue(error is IOException)
            assertEquals("gps", error!!.message)
        }
        assertTrue(shareScope.coroutineContext[Job]!!.isActive)
        shareScope.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `upstream end completes subscribers`() = runTest {
        val ending = object : LocationSource {
            override fun fixes(): Flow<LocationFix> = flowOf(fix(1), fix(2))
        }
        val shareScope = shareScope()
        val shared = SharedLocationSource({ ending }, shareScope)
        val lists = mutableListOf<List<LocationFix>>()
        repeat(2) { launch { lists += shared.fixes().toList() } }
        runCurrent()

        assertEquals(listOf(listOf(fix(1), fix(2)), listOf(fix(1), fix(2))), lists)
        shareScope.coroutineContext[Job]!!.cancel()
    }
}

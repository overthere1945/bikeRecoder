package com.cowork.bikerecoder.feed

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.nav.FakeTripLabelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 지역 저장(스펙 §2.5, 규칙 D3): 한 번 알아낸 지역은 저장하고, 못 알아낸 여행은 이 프로세스에서 다시 묻지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripRegionsTest {

    private val labels = FakeTripLabelStore()
    private val warnings = mutableListOf<Pair<String, Throwable?>>()
    private val asked = mutableListOf<GeoPoint>()
    private var answer: suspend (GeoPoint) -> String? = { "서울 강서구" }
    private val regions = TripRegions(labels, { asked += it; answer(it) }, { m, e -> warnings += m to e })
    private val start = GeoPoint(37.55, 126.85)

    @Test
    fun storedRegionSkipsLookup() = runTest {
        labels.setRegionName(1, "경기 양평군")

        assertEquals("경기 양평군", regions.ensure(1, start))

        assertEquals(emptyList<GeoPoint>(), asked)
    }

    @Test
    fun successIsStored() = runTest {
        assertEquals("서울 강서구", regions.ensure(1, start))

        assertEquals(TripLabel(regionName = "서울 강서구"), labels.label(1))
        assertEquals(listOf(start), asked)
        // The second call reads the stored name.
        assertEquals("서울 강서구", regions.ensure(1, start))
        assertEquals(listOf(start), asked)
    }

    @Test
    fun failureNotRetriedThisProcess() = runTest {
        answer = { null }
        assertNull(regions.ensure(1, start))
        answer = { "서울 강서구" }

        assertNull(regions.ensure(1, start))

        assertEquals(1, asked.size)
        assertEquals(TripLabel(), labels.label(1))
        // Another trip is asked on its own.
        assertEquals("서울 강서구", regions.ensure(2, start))
    }

    @Test
    fun aThrowingLookupIsLoggedAndNotRetried() = runTest {
        answer = { throw IllegalStateException("boom") }

        assertNull(regions.ensure(1, start))
        assertNull(regions.ensure(1, start))

        assertEquals(1, asked.size)
        assertEquals(1, warnings.size)
        assertEquals("boom", warnings.single().second?.message)
    }

    @Test
    fun slowLookupGivesUpAfterFiveSeconds() = runTest {
        answer = { delay(10_000); "서울 강서구" }

        assertNull(regions.ensure(1, start))

        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(TripLabel(), labels.label(1))
        // Given up for this process: no second wait.
        assertNull(regions.ensure(1, start))
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(1, asked.size)
    }

    @Test
    fun concurrentCallersForOneTripLookUpOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = { gate.await(); "서울 강서구" }

        val calls = List(3) { async { regions.ensure(1, start) } }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(List(3) { "서울 강서구" }, calls.awaitAll())
        assertEquals(1, asked.size)
        assertEquals(1, labels.regionWrites.size)
    }

    @Test
    fun aCancelledLookupIsNotRemembered() = runTest {
        val entered = CompletableDeferred<Unit>()
        answer = { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val first = async { regions.ensure(1, start) }
        entered.await()
        first.cancel()
        answer = { "서울 강서구" }

        assertEquals("서울 강서구", regions.ensure(1, start))
    }
}

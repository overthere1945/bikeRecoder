package com.cowork.bikerecoder.ui.common

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchError
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceLookupTest {
    private val p1 = GeoPoint(37.5, 127.0)
    private val p2 = GeoPoint(35.16, 129.16)

    /** addressOf blocked per point until the test completes its deferred. */
    private val gates = mutableMapOf<GeoPoint, CompletableDeferred<String?>>()
    private fun gate(p: GeoPoint) = gates.getOrPut(p) { CompletableDeferred() }
    private fun TestScope.lookup() = PlaceLookup({ gate(it).await() }, this)

    @Test
    fun longPressShowsLoadingThenTheAddress() = runTest(StandardTestDispatcher()) {
        val lookup = lookup()
        lookup.onLongPress(p1)
        assertEquals(PressedPlace(p1, null, loading = true), lookup.pressed.value)
        gate(p1).complete("부산 해운대구 우동")
        runCurrent()
        val pressed = lookup.pressed.value!!
        assertEquals("부산 해운대구 우동", pressed.address)
        assertEquals(false, pressed.loading)
        assertEquals("부산 해운대구 우동", pressed.title)
    }

    @Test
    fun failedLookupFallsBackToCoordinates() = runTest(StandardTestDispatcher()) {
        val lookup = lookup()
        lookup.onLongPress(p1)
        gate(p1).complete(null)
        runCurrent()
        val pressed = lookup.pressed.value!!
        assertEquals("37.50000, 127.00000", pressed.title)
        assertEquals(false, pressed.loading)
    }

    @Test
    fun aNewerLongPressWinsOverAnOlderLookup() = runTest(StandardTestDispatcher()) {
        val lookup = lookup()
        lookup.onLongPress(p1)
        runCurrent()
        lookup.onLongPress(p2)
        runCurrent()
        gate(p1).complete("이전 주소")
        runCurrent()
        assertEquals(p2, lookup.pressed.value!!.point)
        assertEquals(true, lookup.pressed.value!!.loading)
        gate(p2).complete("새 주소")
        runCurrent()
        assertEquals(PressedPlace(p2, "새 주소", loading = false), lookup.pressed.value)
    }

    @Test
    fun dismissCancelsTheLookup() = runTest(StandardTestDispatcher()) {
        val lookup = lookup()
        lookup.onLongPress(p1)
        runCurrent()
        lookup.dismiss()
        assertNull(lookup.pressed.value)
        gate(p1).complete("늦은 주소")
        runCurrent()
        assertNull(lookup.pressed.value)
    }

    private class FakeSearch(private val answer: suspend () -> SearchResult<String?>) : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> = SearchResult.Ok(emptyList())
        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> = answer()
    }

    @Test
    fun addressOrNullMapsEveryFailureToNull() = runTest {
        assertNull(FakeSearch { SearchResult.Ok("  ") }.addressOrNull(p1))
        assertNull(FakeSearch { SearchResult.Ok(null) }.addressOrNull(p1))
        assertNull(FakeSearch { SearchResult.Err(SearchError.NETWORK) }.addressOrNull(p1))
        assertNull(FakeSearch { throw IOException("down") }.addressOrNull(p1))
        assertEquals("역삼동", FakeSearch { SearchResult.Ok("역삼동") }.addressOrNull(p1))
    }
}

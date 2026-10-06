package com.cowork.bikerecoder.ui.main

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeTripStore()
    private val tripManager = TripManager(store, RecordingOfflineMaps(), clock = { 1_000L })
    private val guidance = MutableStateFlow<NavUiState>(NavUiState.Idle)

    private val noSearch = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> = SearchResult.Ok(emptyList())
        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> = SearchResult.Ok(null)
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = MainViewModel(tripManager, store, noSearch, guidance).also { runCurrent() }

    private fun stop(name: String, isDestination: Boolean) =
        TripStop(0, 0, 0, name, GeoPoint(37.5, 127.0), isDestination, visitedAt = null)

    private suspend fun multiDayTrip(): Long = tripManager.startTrip(
        TripType.MULTI_DAY,
        RouteProfile.BALANCED,
        listOf(stop("남산", isDestination = false), stop("여의도", isDestination = true)),
    ).id

    @Test
    fun aMultiDayTripWithStopsLeftOffersToResumeGuidance() = runTest(dispatcher) {
        val tripId = multiDayTrip()
        store.markVisited(store.stops(tripId).first().id, 500L)

        val banner = viewModel().state.value.banner!!

        assertEquals(TripBanner(tripId, "여의도", 1, BannerAction.RESUME), banner)
        assertEquals("🚩 여의도 · 여러 날 1일차", banner.text)
        assertEquals("이어서 안내", banner.action.label)
    }

    @Test
    fun aMultiDayTripWithoutStopsLeftAsksForTheNextDestination() = runTest(dispatcher) {
        // E.g. a single-day trip converted to multi-day after arriving: its destination is visited.
        val tripId = multiDayTrip()
        store.stops(tripId).forEach { store.markVisited(it.id, 500L) }

        val banner = viewModel().state.value.banner!!

        assertEquals(TripBanner(tripId, "여의도", 1, BannerAction.NEXT_DESTINATION), banner)
        assertEquals("🚩 여의도 · 여러 날 1일차", banner.text)
        assertEquals("다음 목적지 정하기", banner.action.label)
    }

    @Test
    fun runningGuidanceShowsTheGuidanceBannerForAnyTripType() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(vm.state.value.guiding)

        guidance.value = NavUiState.Starting(tripId = 3)
        runCurrent()
        assertTrue(vm.state.value.guiding)

        guidance.value = NavUiState.Finished(3, TripType.SINGLE_DAY, com.cowork.bikerecoder.nav.FinishReason.ARRIVED)
        runCurrent()
        assertFalse(vm.state.value.guiding)
    }

    @Test
    fun whileGuidingTheTripBannerGivesWayToTheGuidanceBanner() = runTest(dispatcher) {
        multiDayTrip()
        val vm = viewModel()
        assertTrue(vm.state.value.banner != null)

        guidance.value = NavUiState.Starting(tripId = 1)
        runCurrent()

        assertTrue(vm.state.value.guiding)
        assertNull(vm.state.value.visibleBanner)
    }
}

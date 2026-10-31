package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

/** The "고도" map screen's state (V1.7.0 spec §2.2): a trip's, a segment's or the plan's profile, paths and colours. */
@OptIn(ExperimentalCoroutinesApi::class)
class ElevationMapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val rides = FakeRideStore(FakeTripStore())
    private val handoff = ElevationHandoff()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** [count] points 20 m apart going north from [startLat], climbing 1 m per point; returns the segment id. */
    private suspend fun segment(startedAt: Long, startLat: Double, count: Int = 30): Long {
        val day = LocalDate.of(2026, 10, 25)
        val step = 20.0 / 111_320.0
        val points = List(count) { i ->
            TrackPoint(startedAt + i * 1_000L, GeoPoint(startLat + i * step, 127.0), 30.0 + i, 5f, 5f)
        }
        val totals = SegmentTotals.ZERO.copy(distanceM = (count - 1) * 20.0, movingTimeMs = count * 1_000L)
        val id = rides.openSegment(TRIP, startedAt, day)
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, startedAt + count * 1_000L, totals)
        return id
    }

    private fun TestScope.viewModel(source: ElevationSource) =
        ElevationMapViewModel(source, rides, handoff, log = { _, _ -> }, compute = dispatcher)

    @Test
    fun tripLoadsProfile() = runTest(dispatcher) {
        // Stored out of order: the later one first; the trip is drawn in start order.
        segment(startedAt = 10_000_000L, startLat = 37.60)
        segment(startedAt = 1_000_000L, startLat = 37.50, count = 20)

        val vm = viewModel(ElevationSource.Trip(TRIP))
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(2, ready.paths.size)
        assertEquals(listOf(20, 30), ready.paths.map { it.size })
        assertEquals(37.50, ready.paths[0][0].lat, 1e-9)
        assertEquals(ready.paths.map { it.size }, ready.colors.map { it.size })
        // The segments join without the gap between them: 19 × 20 m + 29 × 20 m.
        assertEquals(960.0, ready.profile.totalM, 5.0)
        assertEquals(null, ready.scrubM)
    }

    @Test
    fun segmentLoadsProfile() = runTest(dispatcher) {
        segment(startedAt = 1_000_000L, startLat = 37.50)
        val second = segment(startedAt = 10_000_000L, startLat = 37.60, count = 25)

        val vm = viewModel(ElevationSource.Segment(second))
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(1, ready.paths.size)
        assertEquals(25, ready.paths[0].size)
        assertEquals(37.60, ready.paths[0][0].lat, 1e-9)
        assertEquals(480.0, ready.profile.totalM, 5.0)
    }

    @Test
    fun planTakesHandoffOnce() = runTest(dispatcher) {
        val path = List(30) { GeoPoint(37.5 + it * 20.0 / 111_320.0, 127.0) }
        val profile = ElevationProfile.fromSegments(
            listOf(path.mapIndexed { i, p -> TrackPoint(i * 1_000L, p, 10.0 + i, null, 5f) }),
        )!!
        handoff.put(profile, path)

        val first = viewModel(ElevationSource.Plan)
        advanceUntilIdle()
        val second = viewModel(ElevationSource.Plan)
        advanceUntilIdle()

        val ready = first.uiState.value as ElevationMapUiState.Ready
        assertTrue(ready.profile === profile)
        assertEquals(listOf(path), ready.paths)
        assertEquals(30, ready.colors.single().size)
        assertEquals(ElevationMapUiState.Closed, second.uiState.value)
    }

    @Test
    fun planWithoutHandoffCloses() = runTest(dispatcher) {
        val vm = viewModel(ElevationSource.Plan)
        advanceUntilIdle()

        assertEquals(ElevationMapUiState.Closed, vm.uiState.value)
    }

    @Test
    fun scrubStoresDistance() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50)
        val vm = viewModel(ElevationSource.Segment(id))
        advanceUntilIdle()

        vm.scrub(250.0)
        assertEquals(250.0, (vm.uiState.value as ElevationMapUiState.Ready).scrubM)

        // Outside the profile: held at its ends.
        vm.scrub(5_000.0)
        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(ready.profile.totalM, ready.scrubM)
    }

    @Test
    fun tripWithoutAltitudesCloses() = runTest(dispatcher) {
        // Points without altitude: no profile.
        val day = LocalDate.of(2026, 10, 25)
        val points = List(30) { i -> TrackPoint(i * 1_000L, GeoPoint(37.5 + i * 20.0 / 111_320.0, 127.0), null, 5f, 5f) }
        val totals = SegmentTotals.ZERO.copy(distanceM = 580.0, movingTimeMs = 30_000L)
        val id = rides.openSegment(TRIP, 0L, day)
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, 30_000L, totals)

        val vm = viewModel(ElevationSource.Trip(TRIP))
        advanceUntilIdle()

        assertEquals(ElevationMapUiState.Closed, vm.uiState.value)
    }

    @Test
    fun storeFailureCloses() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50)
        val failing = object : RideStore by rides {
            override suspend fun points(segmentId: Long): List<TrackPoint> = throw IOException("disk")
        }
        val warned = mutableListOf<String>()

        val trip = ElevationMapViewModel(ElevationSource.Trip(TRIP), failing, handoff, { m, _ -> warned += m }, dispatcher)
        val segment = ElevationMapViewModel(ElevationSource.Segment(id), failing, handoff, { m, _ -> warned += m }, dispatcher)
        advanceUntilIdle()

        assertEquals(ElevationMapUiState.Closed, trip.uiState.value)
        assertEquals(ElevationMapUiState.Closed, segment.uiState.value)
        assertEquals(2, warned.size)
    }

    private companion object {
        const val TRIP = 7L
    }
}

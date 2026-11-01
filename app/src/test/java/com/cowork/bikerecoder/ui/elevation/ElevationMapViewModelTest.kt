package com.cowork.bikerecoder.ui.elevation

import androidx.lifecycle.SavedStateHandle
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.SpeedBand
import com.cowork.bikerecoder.map.pathColors
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

/**
 * The "고도" map screen's state (V1.7.0 spec §2.2, V1.8.0 spec §2.4): a trip's, a segment's or the plan's profile,
 * speed and heart rate, the tabs they give, paths and the current tab's colours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElevationMapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val rides = FakeRideStore(FakeTripStore())
    private val posts = FakePostStore { emptyList() }
    private val handoff = ElevationHandoff()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /**
     * [count] points 20 m apart going north from [startLat], climbing 1 m per point (none without [altitude]) at 18 km/h;
     * with [bpm] a heart-rate sample at every [samplesEvery]-th point, stored READY as [detailed] (no figures). Returns
     * the segment id.
     */
    private suspend fun segment(
        startedAt: Long,
        startLat: Double,
        count: Int = 30,
        altitude: Boolean = true,
        bpm: Int? = null,
        samplesEvery: Int = 1,
        detailed: Boolean = true,
        activity: ActivityType = ActivityType.BIKE,
    ): Long {
        val day = LocalDate.of(2026, 10, 25)
        val step = 20.0 / 111_320.0
        val points = List(count) { i ->
            TrackPoint(startedAt + i * 1_000L, GeoPoint(startLat + i * step, 127.0), if (altitude) 30.0 + i else null, 5f, 5f)
        }
        val totals = SegmentTotals.ZERO.copy(distanceM = (count - 1) * 20.0, movingTimeMs = count * 1_000L)
        val id = rides.openSegment(TRIP, startedAt, day, activity)
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, startedAt + count * 1_000L, totals)
        if (bpm != null) {
            posts.samplesById[id] = points.filterIndexed { i, _ -> i % samplesEvery == 0 }.map { HeartRateSample(it.timeMillis, bpm) }
            posts.extrasById.update { it + (id to SegmentExtras(id, hrStatus = HrStatus.READY, hrDetailed = detailed)) }
        }
        return id
    }

    private fun TestScope.viewModel(source: ElevationSource, tab: ChartTab = ChartTab.ELEVATION) =
        ElevationMapViewModel(source, rides, posts, handoff, tab, log = { _, _ -> }, compute = dispatcher)

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
        assertEquals(960.0, ready.profile!!.totalM, 5.0)
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
        assertEquals(480.0, ready.profile!!.totalM, 5.0)
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

    /** V1.9.0 §3.5: 달리기만 있는 여행의 속도 탭은 페이스 이름·페이스 범례·달리기 색 기준이다. */
    @Test
    fun speedTabLegendForRun() = runTest(dispatcher) {
        segment(startedAt = 1_000_000L, startLat = 37.50, activity = ActivityType.RUN)
        segment(startedAt = 10_000_000L, startLat = 37.60, activity = ActivityType.RUN)

        val vm = viewModel(ElevationSource.Trip(TRIP), ChartTab.SPEED)
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(SpeedChartStyle(ActivityType.RUN), ready.speedStyle)
        assertEquals("페이스", ChartTab.SPEED.titleFor(ready.speedStyle))
        assertEquals("고도", ChartTab.ELEVATION.titleFor(ready.speedStyle))
        assertEquals(
            listOf("7'00\" 넘게", "6'~7'", "5'~6'", "4'~5'", "4'00\" 미만"),
            metricLegend(MetricKind.SPEED, ready.speedStyle).map { it.first },
        )
        assertEquals(SpeedBand.entries.map { it.hex }, metricLegend(MetricKind.SPEED, ready.speedStyle).map { it.second })
        assertEquals(null, ChartText.legendNote(ready.speedStyle))
        // 18 km/h is faster than 15: the run scale's top colour on every point.
        assertEquals(setOf(SpeedBand.SPRINT.hex), ready.colors.flatten().toSet())
        assertTrue(ready.speedDescription.startsWith("페이스 그래프, 평균 "))
    }

    /** 섞인 여행은 속도(km/h)이고, 범례 자리에 안내가 오며, 선 색은 구간마다 자기 종류의 기준이다. */
    @Test
    fun mixedLegendNote() = runTest(dispatcher) {
        segment(startedAt = 1_000_000L, startLat = 37.50, activity = ActivityType.BIKE)
        segment(startedAt = 10_000_000L, startLat = 37.60, activity = ActivityType.RUN)

        val vm = viewModel(ElevationSource.Trip(TRIP), ChartTab.SPEED)
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertTrue(ready.speedStyle.mixed)
        assertEquals("속도", ChartTab.SPEED.titleFor(ready.speedStyle))
        assertEquals("종류마다 색 기준이 다릅니다", ChartText.legendNote(ready.speedStyle))
        assertEquals(emptyList<Pair<String, String>>(), metricLegend(MetricKind.SPEED, ready.speedStyle))
        assertTrue(ready.speedDescription.startsWith("속도 그래프, 평균 "))
        // 18 km/h: a bike's 15~20, a run's 15 and up.
        assertEquals(setOf(SpeedBand.STEADY.hex), ready.colors[0].toSet())
        assertEquals(setOf(SpeedBand.SPRINT.hex), ready.colors[1].toSet())
    }

    @Test
    fun segmentSourceUsesItsOwnActivity() = runTest(dispatcher) {
        segment(startedAt = 1_000_000L, startLat = 37.50, activity = ActivityType.BIKE)
        val walk = segment(startedAt = 10_000_000L, startLat = 37.60, activity = ActivityType.WALK)

        val vm = viewModel(ElevationSource.Segment(walk), ChartTab.SPEED)
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(SpeedChartStyle(ActivityType.WALK), ready.speedStyle)
        assertEquals(setOf(SpeedBand.SPRINT.hex), ready.colors.flatten().toSet(), "18 km/h is a walk's fastest band")
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
        assertEquals(ready.profile!!.totalM, ready.scrubM)
    }

    @Test
    fun tripWithNothingToDrawCloses() = runTest(dispatcher) {
        // One point: no line, so neither a profile nor a speed or heart-rate series.
        val day = LocalDate.of(2026, 10, 25)
        val points = listOf(TrackPoint(0L, GeoPoint(37.5, 127.0), 30.0, 5f, 5f))
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

        val trip = ElevationMapViewModel(
            ElevationSource.Trip(TRIP), failing, posts, handoff, ChartTab.ELEVATION, { m, _ -> warned += m }, dispatcher,
        )
        val segment = ElevationMapViewModel(
            ElevationSource.Segment(id), failing, posts, handoff, ChartTab.ELEVATION, { m, _ -> warned += m }, dispatcher,
        )
        advanceUntilIdle()

        assertEquals(ElevationMapUiState.Closed, trip.uiState.value)
        assertEquals(ElevationMapUiState.Closed, segment.uiState.value)
        assertEquals(2, warned.size)
    }

    @Test
    fun tabsFollowData() = runTest(dispatcher) {
        val plain = segment(startedAt = 1_000_000L, startLat = 37.50)
        val withHeart = segment(startedAt = 10_000_000L, startLat = 37.60, bpm = 130)

        val noHeart = viewModel(ElevationSource.Segment(plain))
        val all = viewModel(ElevationSource.Segment(withHeart))
        advanceUntilIdle()

        // Without heart rate: [고도][속도].
        val a = noHeart.uiState.value as ElevationMapUiState.Ready
        assertEquals(listOf(ChartTab.ELEVATION, ChartTab.SPEED), a.tabs)
        assertEquals(ChartTab.ELEVATION, a.tab)
        assertNotNull(a.profile)
        assertNotNull(a.speed)
        assertNull(a.heartRate)
        val b = all.uiState.value as ElevationMapUiState.Ready
        assertEquals(ChartTab.entries, b.tabs)
        assertEquals(130.0, b.heartRate!!.maxValue, 1e-9)
        // Read out with the stored totals (580 m in 30 s).
        assertEquals(ChartText.speedDescription(580.0 / 30.0 * 3.6, 0.0), b.speedDescription)
    }

    /** READY stored heart rate of segment [id] (detailed as [segment] stored it). */
    private fun stored(id: Long, avgBpm: Int?, maxBpm: Int?) {
        posts.extrasById.update {
            it + (id to (it[id] ?: SegmentExtras(id)).copy(hrStatus = HrStatus.READY, avgBpm = avgBpm, maxBpm = maxBpm))
        }
    }

    @Test
    fun tripHeartDescriptionUsesStoredAverage() = runTest(dispatcher) {
        // 30 s with 30 samples at 110, then 60 s with 15 samples at 150: the samples' mean is 123, the moving-time
        // weighted mean of the stored averages (110 × 30 + 150 × 60) / 90 is 137.
        val a = segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 110)
        val b = segment(startedAt = 10_000_000L, startLat = 37.60, count = 60, bpm = 150, samplesEvery = 4)
        stored(a, avgBpm = 110, maxBpm = 124)
        stored(b, avgBpm = 150, maxBpm = 171)

        val vm = viewModel(ElevationSource.Trip(TRIP))
        advanceUntilIdle()

        assertEquals(ChartText.heartDescription(137, 171), (vm.uiState.value as ElevationMapUiState.Ready).heartDescription)
    }

    @Test
    fun segmentHeartDescriptionUsesStoredValues() = runTest(dispatcher) {
        // Samples all at 130; the stored values (from the watch's own summary) differ.
        val id = segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 130)
        stored(id, avgBpm = 128, maxBpm = 171)

        val vm = viewModel(ElevationSource.Segment(id))
        advanceUntilIdle()

        assertEquals(ChartText.heartDescription(128, 171), (vm.uiState.value as ElevationMapUiState.Ready).heartDescription)
    }

    @Test
    fun heartDescriptionFallsBackToSamples() = runTest(dispatcher) {
        // No figures stored for one segment, no highest for the other: the samples' mean and highest.
        val a = segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 110)
        val b = segment(startedAt = 10_000_000L, startLat = 37.60, bpm = 150)
        stored(b, avgBpm = 150, maxBpm = null)

        val trip = viewModel(ElevationSource.Trip(TRIP))
        val segment = viewModel(ElevationSource.Segment(a))
        advanceUntilIdle()

        assertEquals(ChartText.heartDescription(130, 150), (trip.uiState.value as ElevationMapUiState.Ready).heartDescription)
        assertEquals(ChartText.heartDescription(110, 110), (segment.uiState.value as ElevationMapUiState.Ready).heartDescription)
    }

    @Test
    fun tripHeartRateJoinsSegments() = runTest(dispatcher) {
        segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 110)
        segment(startedAt = 10_000_000L, startLat = 37.60, bpm = 150)

        val vm = viewModel(ElevationSource.Trip(TRIP), ChartTab.HEART_RATE)
        advanceUntilIdle()

        // Each segment's own samples colour its path (green, orange); the second path's first point is at the joint,
        // which reads the first segment's end.
        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(ChartTab.HEART_RATE, ready.tab)
        assertEquals(List(30) { "#43A047" }, ready.colors[0])
        assertEquals(List(29) { "#FB8C00" }, ready.colors[1].drop(1))
    }

    /** Final review 1: heart rate that is not detailed has no tab; in a trip such a segment has no value (grey). */
    @Test
    fun heartRateThatIsNotDetailedIsNotCharted() = runTest(dispatcher) {
        segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 110)
        val b = segment(startedAt = 10_000_000L, startLat = 37.60, bpm = 150, detailed = false)

        val sparse = viewModel(ElevationSource.Segment(b), ChartTab.HEART_RATE)
        val trip = viewModel(ElevationSource.Trip(TRIP), ChartTab.HEART_RATE)
        advanceUntilIdle()

        val one = sparse.uiState.value as ElevationMapUiState.Ready
        assertEquals(listOf(ChartTab.ELEVATION, ChartTab.SPEED), one.tabs)
        assertNull(one.heartRate)
        val both = trip.uiState.value as ElevationMapUiState.Ready
        assertEquals(110.0, both.heartRate!!.maxValue, 1e-9)
        assertEquals(List(30) { "#43A047" }, both.colors[0])
        assertEquals(List(29) { "#9E9E9E" }, both.colors[1].drop(1))
    }

    /** The selected tab is kept in the saved state (the route's `tab`), so it outlives the process. */
    @Test
    fun selectedTabIsSaved() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 130)
        val handle = SavedStateHandle(mapOf(ElevationMapViewModel.ARG_TAB to ChartTab.ELEVATION.arg))
        val vm = ElevationMapViewModel(
            ElevationSource.Segment(id), rides, posts, handoff, ChartTab.of(handle[ElevationMapViewModel.ARG_TAB]),
            { _, _ -> }, dispatcher, savedState = handle,
        )
        advanceUntilIdle()

        vm.selectTab(ChartTab.HEART_RATE)
        assertEquals("heartRate", handle.get<String>(ElevationMapViewModel.ARG_TAB))
        // A new screen made from the saved state opens on it.
        val again = ElevationMapViewModel(
            ElevationSource.Segment(id), rides, posts, handoff, ChartTab.of(handle[ElevationMapViewModel.ARG_TAB]),
            { _, _ -> }, dispatcher, savedState = handle,
        )
        advanceUntilIdle()
        assertEquals(ChartTab.HEART_RATE, (again.uiState.value as ElevationMapUiState.Ready).tab)
    }

    /** A tab that is not there is not saved. */
    @Test
    fun missingTabIsNotSaved() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50)
        val handle = SavedStateHandle()
        val vm = ElevationMapViewModel(
            ElevationSource.Segment(id), rides, posts, handoff, ChartTab.ELEVATION, { _, _ -> }, dispatcher, savedState = handle,
        )
        advanceUntilIdle()

        vm.selectTab(ChartTab.HEART_RATE)
        assertNull(handle.get<String>(ElevationMapViewModel.ARG_TAB))
        vm.selectTab(ChartTab.SPEED)
        assertEquals("speed", handle.get<String>(ElevationMapViewModel.ARG_TAB))
    }

    @Test
    fun heartRateReadFailureLeavesTheRest() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 130)
        val failing = object : PostStore by posts {
            override suspend fun samples(segmentId: Long): List<HeartRateSample> = throw IOException("disk")
        }
        val warned = mutableListOf<String>()

        val vm = ElevationMapViewModel(
            ElevationSource.Segment(id), rides, failing, handoff, ChartTab.HEART_RATE, { m, _ -> warned += m }, dispatcher,
        )
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(listOf(ChartTab.ELEVATION, ChartTab.SPEED), ready.tabs)
        assertNull(ready.heartRate)
        assertEquals(1, warned.size)
    }

    @Test
    fun missingTabFallsBackToFirst() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50)

        val vm = viewModel(ElevationSource.Segment(id), ChartTab.HEART_RATE)
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(ChartTab.ELEVATION, ready.tab)
        // A tab that is not there is not selected.
        vm.selectTab(ChartTab.HEART_RATE)
        assertEquals(ready, vm.uiState.value)
    }

    @Test
    fun scrubKeptAcrossTabs() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 130)
        val vm = viewModel(ElevationSource.Segment(id))
        advanceUntilIdle()

        vm.scrub(250.0)
        val scrubbed = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(scrubbed.profile!!.pointAt(250.0), scrubbed.marker)
        vm.selectTab(ChartTab.SPEED)
        vm.selectTab(ChartTab.HEART_RATE)

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(ChartTab.HEART_RATE, ready.tab)
        assertEquals(250.0, ready.scrubM)
        assertEquals(scrubbed.marker, ready.marker)
    }

    @Test
    fun colorsFollowTab() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50, bpm = 130)
        val vm = viewModel(ElevationSource.Segment(id))
        advanceUntilIdle()

        val elevation = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(pathColors(elevation.paths, elevation.profile!!), elevation.colors)
        vm.selectTab(ChartTab.SPEED)
        // 18 km/h: STEADY green.
        assertEquals(listOf(List(30) { "#66BB6A" }), (vm.uiState.value as ElevationMapUiState.Ready).colors)
        vm.selectTab(ChartTab.HEART_RATE)
        // 130 bpm: yellow.
        assertEquals(listOf(List(30) { "#FDD835" }), (vm.uiState.value as ElevationMapUiState.Ready).colors)
        vm.selectTab(ChartTab.ELEVATION)
        assertEquals(elevation.colors, (vm.uiState.value as ElevationMapUiState.Ready).colors)
        assertNotEquals(elevation.colors, listOf(List(30) { "#66BB6A" }))
    }

    @Test
    fun noElevationOpensOnSpeed() = runTest(dispatcher) {
        val id = segment(startedAt = 1_000_000L, startLat = 37.50, altitude = false)

        val vm = viewModel(ElevationSource.Segment(id))
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertNull(ready.profile)
        assertEquals(listOf(ChartTab.SPEED), ready.tabs)
        assertEquals(ChartTab.SPEED, ready.tab)
        // The scrub is held on the speed's axis and the dot is on the line.
        vm.scrub(5_000.0)
        val scrubbed = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(ready.speed!!.totalM, scrubbed.scrubM)
        assertEquals(ready.paths[0].last(), scrubbed.marker)
    }

    @Test
    fun planHasOnlyElevation() = runTest(dispatcher) {
        val path = List(30) { GeoPoint(37.5 + it * 20.0 / 111_320.0, 127.0) }
        val profile = ElevationProfile.fromSegments(
            listOf(path.mapIndexed { i, p -> TrackPoint(i * 1_000L, p, 10.0 + i, 5f, 5f) }),
        )!!
        handoff.put(profile, path)

        val vm = viewModel(ElevationSource.Plan, ChartTab.SPEED)
        advanceUntilIdle()

        val ready = vm.uiState.value as ElevationMapUiState.Ready
        assertEquals(listOf(ChartTab.ELEVATION), ready.tabs)
        assertEquals(ChartTab.ELEVATION, ready.tab)
        assertNull(ready.speed)
        assertNull(ready.heartRate)
        vm.scrub(100.0)
        assertEquals(profile.pointAt(100.0), (vm.uiState.value as ElevationMapUiState.Ready).marker)
    }

    private companion object {
        const val TRIP = 7L
    }
}

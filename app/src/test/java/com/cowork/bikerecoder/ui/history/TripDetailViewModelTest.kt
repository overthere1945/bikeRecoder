package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.feed.TripRegions
import com.cowork.bikerecoder.nav.FakeTripLabelStore
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/** 여행 기록 화면의 제목 줄(스펙 §2.5): 보이는 제목, 지역 찾기, 제목 바꾸기. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val store = FakePostStore { rides.segments(TRIP) }
    private val labels = FakeTripLabelStore()
    private val asked = mutableListOf<GeoPoint>()
    private var region: String? = "서울 강서구"
    private val regions = TripRegions(labels, { asked += it; region }, { _, _ -> })

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun evening() {
        trips.trips[TRIP] = Trip(TRIP, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
        val day = LocalDate.of(2026, 10, 25)
        val startedAt = day.atTime(19, 0).atZone(SEOUL).toInstant().toEpochMilli()
        val totals = SegmentTotals.ZERO.copy(distanceM = 1_500.0, movingTimeMs = 60_000)
        val id = rides.openSegment(TRIP, startedAt, day)
        rides.appendPoints(id, listOf(TrackPoint(startedAt, FIRST_POINT, null, 5f, 5f)), totals)
        rides.closeSegment(id, startedAt + 60_000, totals)
    }

    private fun TestScope.detail() = TripDetailViewModel(
        rides, TRIP, store,
        PostProcessor(rides, store, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> }),
        compute = dispatcher,
        tripStore = trips,
        tripLabels = labels,
        tripRegions = regions,
    )

    @Test
    fun openingLooksUpTheRegionOnceAndShowsIt() = runTest(dispatcher) {
        evening()
        val vm = detail()
        advanceUntilIdle()

        assertEquals("저녁 라이딩 · 서울 강서구", vm.title.value)
        assertNull(vm.customTitle.value)
        assertEquals(listOf(FIRST_POINT), asked)
        assertEquals(TripLabel(regionName = "서울 강서구"), labels.label(TRIP))

        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, asked.size)
    }

    @Test
    fun noRegionShowsTheAutomaticTitleOnly() = runTest(dispatcher) {
        evening()
        region = null
        val vm = detail()
        advanceUntilIdle()

        assertEquals("저녁 라이딩", vm.title.value)
    }

    @Test
    fun renameSavesCleanedTitle() = runTest(dispatcher) {
        evening()
        val vm = detail()
        advanceUntilIdle()

        vm.rename("  한강 한 바퀴 ")
        advanceUntilIdle()

        assertEquals("한강 한 바퀴", labels.label(TRIP).customTitle)
        assertEquals("한강 한 바퀴", vm.title.value)
        assertEquals("한강 한 바퀴", vm.customTitle.value)
        // The region stays stored.
        assertEquals("서울 강서구", labels.label(TRIP).regionName)
    }

    @Test
    fun blankRenameRestoresAutoTitle() = runTest(dispatcher) {
        evening()
        val vm = detail()
        advanceUntilIdle()
        vm.rename("한강 한 바퀴")
        advanceUntilIdle()

        vm.rename("   ")
        advanceUntilIdle()

        assertNull(labels.label(TRIP).customTitle)
        assertNull(vm.customTitle.value)
        assertEquals("저녁 라이딩 · 서울 강서구", vm.title.value)
    }

    /** The dialog's hint is the automatic title, also while a custom title is shown. */
    @Test
    fun autoTitleStaysAutomaticWhileACustomTitleIsSet() = runTest(dispatcher) {
        evening()
        val vm = detail()
        advanceUntilIdle()
        assertEquals("저녁 라이딩 · 서울 강서구", vm.autoTitle.value)

        vm.rename("한강 한 바퀴")
        advanceUntilIdle()

        assertEquals("한강 한 바퀴", vm.title.value)
        assertEquals("저녁 라이딩 · 서울 강서구", vm.autoTitle.value)
    }

    @Test
    fun aLongTitleIsCutAtThirtyCodePoints() = runTest(dispatcher) {
        evening()
        val vm = detail()
        advanceUntilIdle()

        vm.rename("🚴".repeat(31))
        advanceUntilIdle()

        assertEquals("🚴".repeat(30), vm.customTitle.value)
    }

    private companion object {
        const val TRIP = 1L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val FIRST_POINT = GeoPoint(37.5, 127.0)
    }
}

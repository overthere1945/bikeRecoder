package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.export.ExportFormat
import com.cowork.bikerecoder.export.ExportResult
import com.cowork.bikerecoder.export.ExportState
import com.cowork.bikerecoder.feed.TripRegions
import com.cowork.bikerecoder.nav.FakeTripLabelStore
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

/** 여행 기록 화면의 제목 줄(스펙 §2.5): 보이는 제목, 지역 찾기, 제목 바꾸기. V1.7.0: 하강·최고 고도 합계와 고도 단면(스펙 §1.5, §2.3). */
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

    private val exports = mutableListOf<Triple<Long, ExportFormat, String>>()
    private var exportResult: ExportResult = ExportResult.Saved("content://downloads/7", "bikeRecoder_20261025_1900 (1).gpx", "application/gpx+xml")

    private fun TestScope.detail(imported: Flow<Map<Long, String>> = flowOf(emptyMap())) = TripDetailViewModel(
        rides, TRIP, store,
        PostProcessor(rides, store, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> }),
        compute = dispatcher,
        tripStore = trips,
        tripLabels = labels,
        tripRegions = regions,
        imported = imported,
        exportTrip = { id, format, title ->
            exports += Triple(id, format, title)
            exportResult
        },
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

    /** 북쪽으로 [count]개 점, 10 m 간격, 고도는 [eleAt]. */
    private fun track(startedAt: Long, startLat: Double, count: Int, eleAt: (Int) -> Double?): List<TrackPoint> =
        List(count) { i ->
            val lat = startLat + i * 10.0 / 111_320.0
            TrackPoint(startedAt + i * 2_000L, GeoPoint(lat, 127.0), eleAt(i), 5f, 5f)
        }

    /** 한 구간을 [points]·[totals]로 저장하고 닫는다. */
    private suspend fun ride(startedAt: Long, day: LocalDate, points: List<TrackPoint>, totals: SegmentTotals) {
        val id = rides.openSegment(TRIP, startedAt, day)
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, points.last().timeMillis, totals)
    }

    private suspend fun openTrip() {
        trips.trips[TRIP] = Trip(TRIP, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
    }

    @Test
    fun descentIsSumMaxAltitudeIsMax() = runTest(dispatcher) {
        openTrip()
        val d1 = LocalDate.of(2026, 10, 24)
        val d2 = LocalDate.of(2026, 10, 25)
        val a = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000, descentM = 30.0, maxAltitudeM = 80.0)
        val b = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000, descentM = 12.5, maxAltitudeM = 140.0)
        ride(1_000_000L, d1, track(1_000_000L, 37.5, 5) { 10.0 }, a)
        ride(90_000_000L, d2, track(90_000_000L, 37.6, 5) { 10.0 }, b)
        val vm = detail()
        advanceUntilIdle()

        assertEquals(42.5, vm.total.value.descentM)
        assertEquals(140.0, vm.total.value.maxAltitudeM)
    }

    @Test
    fun allMaxAltitudesNullIsNull() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000, descentM = 5.0, maxAltitudeM = null)
        ride(1_000_000L, LocalDate.of(2026, 10, 24), track(1_000_000L, 37.5, 5) { null }, t)
        ride(90_000_000L, LocalDate.of(2026, 10, 25), track(90_000_000L, 37.6, 5) { null }, t)
        val vm = detail()
        advanceUntilIdle()

        assertNull(vm.total.value.maxAltitudeM)
        assertEquals(10.0, vm.total.value.descentM)
        // 고도가 없는 여행은 단면이 없고, 계산은 끝났다.
        assertNull(vm.profile.value)
        assertEquals(true, vm.profileLoaded.value)
    }

    @Test
    fun profileFromSegmentsInStartOrder() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000)
        val later = track(90_000_000L, 37.6, 61) { 100.0 + it }       // 600 m, 올라간다
        val earlier = track(1_000_000L, 37.5, 31) { 50.0 }           // 300 m, 평평하다
        // 늦은 구간을 먼저 저장해도 시작 시각 순으로 이어 붙인다.
        ride(90_000_000L, LocalDate.of(2026, 10, 25), later, t)
        ride(1_000_000L, LocalDate.of(2026, 10, 24), earlier, t)
        val vm = detail()
        advanceUntilIdle()

        val expected = ElevationProfile.fromSegments(listOf(earlier, later))!!
        val actual = requireNotNull(vm.profile.value)
        assertEquals(expected.totalM, actual.totalM)
        assertEquals(expected.boundariesM, actual.boundariesM)
        assertEquals(expected.elevationsM.toList(), actual.elevationsM.toList())
        assertEquals(1, actual.boundariesM.size)
    }

    /** 가져온 여행(V1.6.0)도 기록한 여행과 같이 저장된 점으로 단면이 만들어진다. */
    @Test
    fun importedTripHasProfile() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 600.0, movingTimeMs = 60_000, ascentM = 30.0, maxAltitudeM = 130.0)
        ride(1_000_000L, LocalDate.of(2026, 10, 24), track(1_000_000L, 37.5, 61) { 100.0 + it * 0.5 }, t)
        val vm = detail(imported = flowOf(mapOf(TRIP to "Relive_0514.gpx")))
        advanceUntilIdle()

        assertEquals("Relive_0514.gpx", vm.importedFrom.value)
        assertEquals(600.0, requireNotNull(vm.profile.value).totalM, 1.0)
    }

    /** V1.7.0 spec §4.1, §4.2: the trip is exported under the title the screen shows; the dialog shows what was saved. */
    @Test
    fun exportUsesTheShownTitle() = runTest(dispatcher) {
        evening()
        val vm = detail()
        advanceUntilIdle()
        assertEquals(false, vm.recording.value)

        vm.export(ExportFormat.GPX)
        assertEquals(ExportState.Running, vm.exportState.value)
        advanceUntilIdle()

        assertEquals(listOf(Triple(TRIP, ExportFormat.GPX, "저녁 라이딩 · 서울 강서구")), exports)
        assertEquals(ExportState.Done(exportResult as ExportResult.Saved), vm.exportState.value)
        vm.clearExport()
        assertEquals(ExportState.Idle, vm.exportState.value)
    }

    /** A trip with an open segment dims the menu; if the exporter finds one anyway, the outcome says so. */
    @Test
    fun openSegmentIsRecording() = runTest(dispatcher) {
        evening()
        rides.openSegment(TRIP, 99_000_000L, LocalDate.of(2026, 10, 26))
        exportResult = ExportResult.Recording
        val vm = detail()
        advanceUntilIdle()

        assertEquals(true, vm.recording.value)
        vm.export(ExportFormat.KML)
        advanceUntilIdle()
        assertEquals(ExportState.Recording, vm.exportState.value)
    }

    private companion object {
        const val TRIP = 1L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val FIRST_POINT = GeoPoint(37.5, 127.0)
    }
}

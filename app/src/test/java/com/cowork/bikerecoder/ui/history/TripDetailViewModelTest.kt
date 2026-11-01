package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.series.MetricSeries
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
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 여행 기록 화면의 제목 줄(스펙 §2.5): 보이는 제목, 지역 찾기, 제목 바꾸기. V1.7.0: 하강·최고 고도 합계와 고도 단면(스펙 §1.5, §2.3).
 * V1.8.0: 속도·심박 계열과 그래프 낭독(스펙 §2.1, §2.2).
 */
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
    private var exportGate: CompletableDeferred<Unit>? = null
    private var exportResult: ExportResult = ExportResult.Saved("content://downloads/7", "bikeRecoder_20261025_1900 (1).gpx", "application/gpx+xml")

    private fun TestScope.detail(
        imported: Flow<Map<Long, String>> = flowOf(emptyMap()),
        profileOf: (List<List<TrackPoint>>) -> ElevationProfile? = ElevationProfile.Companion::fromSegments,
        speedOf: (TrackAxis, List<List<TrackPoint>>) -> MetricSeries? = MetricSeries.Companion::speed,
        postStore: PostStore = store,
    ) = TripDetailViewModel(
        rides, TRIP, postStore,
        PostProcessor(rides, store, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> }),
        compute = dispatcher,
        tripStore = trips,
        tripLabels = labels,
        tripRegions = regions,
        imported = imported,
        profileOf = profileOf,
        speedOf = speedOf,
        exportTrip = { id, format, title ->
            exports += Triple(id, format, title)
            exportGate?.await()
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

    /** 한 구간을 [points]·[totals]로 저장하고 닫는다. 구간 id를 돌려준다. */
    private suspend fun ride(startedAt: Long, day: LocalDate, points: List<TrackPoint>, totals: SegmentTotals): Long {
        val id = rides.openSegment(TRIP, startedAt, day)
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, points.last().timeMillis, totals)
        return id
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
    fun failingProfileComputationStillEndsLoading() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000)
        ride(1_000_000L, LocalDate.of(2026, 10, 24), track(1_000_000L, 37.5, 31) { 50.0 + it }, t)
        val vm = detail(profileOf = { error("boom") })
        advanceUntilIdle()

        // 계산이 실패해도 불러오기는 끝나고(그래서 "고도 정보가 없습니다"가 보인다), 나머지 화면은 그대로다.
        assertEquals(true, vm.profileLoaded.value)
        assertNull(vm.profile.value)
        assertEquals(1, vm.segmentsByDate.value.values.sumOf { it.size })
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

    /** [segment]의 점마다 [bpm] 표본을 두고, 저장된 평균·최고를 [avg]·[max]로 READY 처리한다. */
    private suspend fun heart(segment: Long, points: List<TrackPoint>, bpm: (Int) -> Int, avg: Int?, max: Int?): List<HeartRateSample> {
        val samples = points.mapIndexed { i, p -> HeartRateSample(p.timeMillis, bpm(i)) }
        store.replaceSamples(segment, samples)
        store.saveExtras(
            SegmentExtras(segment, hrStatus = HrStatus.READY, hrLastTriedAt = 1L, hrDetailed = true, avgBpm = avg, maxBpm = max),
        )
        return samples
    }

    private fun assertSameSeries(expected: MetricSeries, actual: MetricSeries?) {
        assertNotNull(actual)
        assertEquals(expected.kind, actual!!.kind)
        assertEquals(expected.distancesM.toList(), actual.distancesM.toList())
        assertEquals(expected.values, actual.values)
        assertEquals(expected.boundariesM, actual.boundariesM)
    }

    /** V1.8.0 §2.2: 속도·심박 계열은 고도 단면처럼 시작 시각 순 구간들의 한 축 위에 만들고, 표본은 구간마다 읽어 잇는다. */
    @Test
    fun speedAndHeartSeries() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 300.0, movingTimeMs = 60_000)
        val later = track(90_000_000L, 37.6, 31) { null }
        val earlier = track(1_000_000L, 37.5, 31) { null }
        // 늦은 구간을 먼저 저장해도 시작 시각 순으로 이어 붙인다.
        val laterId = ride(90_000_000L, LocalDate.of(2026, 10, 25), later, t)
        val earlierId = ride(1_000_000L, LocalDate.of(2026, 10, 24), earlier, t)
        val first = heart(earlierId, earlier, { 120 + it }, avg = 135, max = 150)
        val second = heart(laterId, later, { 160 - it }, avg = 145, max = 160)
        val vm = detail()
        advanceUntilIdle()

        val segments = listOf(earlier, later)
        val axis = TrackAxis.of(segments)!!
        assertSameSeries(MetricSeries.speed(axis, segments)!!, vm.speedSeries.value)
        assertSameSeries(MetricSeries.heartRate(axis, segments, first + second, listOf(true, true))!!, vm.heartSeries.value)
        assertEquals(1, vm.speedSeries.value!!.boundariesM.size)
        assertEquals(true, vm.profileLoaded.value)
    }

    /** 심박 표본이 없으면(저장된 평균만 있어도) 심박 계열은 null이고, 속도 계열은 그대로 있다. */
    @Test
    fun noHeartRateGivesNullSeries() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 300.0, movingTimeMs = 60_000)
        val points = track(1_000_000L, 37.5, 31) { null }
        val id = ride(1_000_000L, LocalDate.of(2026, 10, 24), points, t)
        store.saveExtras(SegmentExtras(id, hrStatus = HrStatus.READY, hrLastTriedAt = 1L, hrDetailed = true, avgBpm = 130, maxBpm = 150))
        val vm = detail()
        advanceUntilIdle()

        assertNull(vm.heartSeries.value)
        assertNotNull(vm.speedSeries.value)
        assertEquals(true, vm.profileLoaded.value)
    }

    /** 합계 카드의 평균·최고와 그래프 낭독은 저장된 값이다. 다듬은 계열(18 km/h, 140 bpm 근처)에서 구하지 않는다. */
    @Test
    fun storedAveragesUnchanged() = runTest(dispatcher) {
        openTrip()
        // 저장된 합계: 300 m를 30초에 = 평균 10 m/s(36 km/h), 최고 15 m/s(54 km/h). 점은 모두 5 m/s(18 km/h)다.
        val t = SegmentTotals.ZERO.copy(distanceM = 300.0, movingTimeMs = 30_000, maxSpeedMps = 15.0)
        val points = track(1_000_000L, 37.5, 31) { null }
        val id = ride(1_000_000L, LocalDate.of(2026, 10, 24), points, t)
        heart(id, points, { 140 }, avg = 120, max = 175)
        val vm = detail()
        advanceUntilIdle()

        assertEquals(10.0, vm.total.value.avgSpeedMps)
        assertEquals(15.0, vm.total.value.maxSpeedMps)
        assertEquals(120, vm.heartRate.value.avgBpm)
        assertEquals(175, vm.heartRate.value.maxBpm)
        assertEquals(140.0, vm.heartSeries.value!!.maxValue, 1e-9)
        assertEquals("심박 그래프, 평균 120bpm, 최고 175bpm", vm.heartDescription.value)
    }

    /** 저장된 평균·최고가 없으면 표본에서 구한 평균·최고를 읽는다. */
    @Test
    fun heartReadOutFallsBackToSamples() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 300.0, movingTimeMs = 60_000)
        val points = track(1_000_000L, 37.5, 31) { null }
        val id = ride(1_000_000L, LocalDate.of(2026, 10, 24), points, t)
        // 130 … 160: 평균 145, 최고 160.
        heart(id, points, { 130 + it }, avg = null, max = null)
        val vm = detail()
        advanceUntilIdle()

        assertNull(vm.heartRate.value.avgBpm)
        assertNotNull(vm.heartSeries.value)
        assertEquals("심박 그래프, 평균 145bpm, 최고 160bpm", vm.heartDescription.value)
    }

    /** 화면을 연 뒤에 표본이 들어와도(후처리, [다시 가져오기]) 심박 계열이 따라 생기고, 다시 들어오면 바뀐다. */
    @Test
    fun heartSeriesFollowsNewSamples() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 300.0, movingTimeMs = 60_000)
        val points = track(1_000_000L, 37.5, 31) { null }
        val id = ride(1_000_000L, LocalDate.of(2026, 10, 24), points, t)
        val vm = detail()
        advanceUntilIdle()
        assertNull(vm.heartSeries.value)
        assertEquals(true, vm.profileLoaded.value)

        val first = heart(id, points, { 120 }, avg = 120, max = 120)
        advanceUntilIdle()
        val axis = TrackAxis.of(listOf(points))!!
        assertSameSeries(MetricSeries.heartRate(axis, listOf(points), first, listOf(true))!!, vm.heartSeries.value)

        // 다시 가져와 더 많은 표본이 저장되었다(hrLastTriedAt이 바뀐다).
        val second = points.mapIndexed { i, p -> HeartRateSample(p.timeMillis, 150 + i) }
        store.replaceSamples(id, second)
        store.saveExtras(
            SegmentExtras(id, hrStatus = HrStatus.READY, hrLastTriedAt = 2L, hrDetailed = true, avgBpm = 165, maxBpm = 180),
        )
        advanceUntilIdle()
        assertSameSeries(MetricSeries.heartRate(axis, listOf(points), second, listOf(true))!!, vm.heartSeries.value)
    }

    /** 속도 계열 계산이 실패해도 불러오기는 끝나고(그래프 자리는 비지 않는다), 고도 단면과 심박 계열은 그대로다. */
    @Test
    fun seriesFailureStillLoaded() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 600.0, movingTimeMs = 60_000)
        val points = track(1_000_000L, 37.5, 61) { 100.0 + it }
        val id = ride(1_000_000L, LocalDate.of(2026, 10, 24), points, t)
        heart(id, points, { 140 }, avg = 140, max = 140)
        val vm = detail(speedOf = { _, _ -> error("boom") })
        advanceUntilIdle()

        assertEquals(true, vm.profileLoaded.value)
        assertNull(vm.speedSeries.value)
        assertEquals(140.0, vm.heartSeries.value!!.maxValue, 1e-9) // 축은 있으니 심박은 그려진다
        assertNotNull(vm.profile.value)
    }

    /** 표본을 읽지 못하면 심박 없이 보인다(로그만 남긴다). 속도 계열은 있다. */
    @Test
    fun unreadableSamplesDropOnlyHeartRate() = runTest(dispatcher) {
        openTrip()
        val t = SegmentTotals.ZERO.copy(distanceM = 300.0, movingTimeMs = 60_000)
        val points = track(1_000_000L, 37.5, 31) { null }
        val id = ride(1_000_000L, LocalDate.of(2026, 10, 24), points, t)
        heart(id, points, { 140 }, avg = 140, max = 140)
        val failing = object : PostStore by store {
            override suspend fun samples(segmentId: Long): List<HeartRateSample> = throw IllegalStateException("database busy")
        }
        val vm = detail(postStore = failing)
        advanceUntilIdle()

        assertNull(vm.heartSeries.value)
        assertNotNull(vm.speedSeries.value)
        assertEquals(true, vm.profileLoaded.value)
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

    /** A 5 m trip has no title yet (no ride of 10 m); it is still exported, under the title rule without the 10 m. */
    @Test
    fun shortTripExportsWithAFallbackTitle() = runTest(dispatcher) {
        openTrip()
        val day = LocalDate.of(2026, 10, 25)
        val startedAt = day.atTime(19, 0).atZone(SEOUL).toInstant().toEpochMilli()
        ride(startedAt, day, track(startedAt, 37.5, 2) { null }, SegmentTotals.ZERO.copy(distanceM = 5.0, movingTimeMs = 2_000))
        val vm = detail()
        advanceUntilIdle()
        assertNull(vm.title.value)

        vm.export(ExportFormat.GPX)
        advanceUntilIdle()

        assertEquals(listOf(Triple(TRIP, ExportFormat.GPX, "저녁 라이딩")), exports)
    }

    /** A second tap while the first export runs starts nothing. */
    @Test
    fun exportTwiceWhileRunningExportsOnce() = runTest(dispatcher) {
        evening()
        val gate = CompletableDeferred<Unit>()
        exportGate = gate
        val vm = detail()
        advanceUntilIdle()

        vm.export(ExportFormat.GPX)
        advanceUntilIdle()
        vm.export(ExportFormat.KML)
        advanceUntilIdle()
        assertEquals(ExportState.Running, vm.exportState.value)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, exports.size)
        assertEquals(ExportFormat.GPX, exports.single().second)
        assertEquals(ExportState.Done(exportResult as ExportResult.Saved), vm.exportState.value)
    }

    private companion object {
        const val TRIP = 1L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val FIRST_POINT = GeoPoint(37.5, 127.0)
    }
}

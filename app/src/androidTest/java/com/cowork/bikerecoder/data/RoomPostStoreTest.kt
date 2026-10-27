package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRetryPolicy
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.post.RidePhoto
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.post.SegmentGone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomPostStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var trips: RoomTripStore
    private lateinit var rides: RoomRideStore
    private lateinit var store: RoomPostStore

    private val day = LocalDate.of(2026, 10, 18)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trips = RoomTripStore(db)
        rides = RoomRideStore(db)
        store = RoomPostStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newTrip(): Long = trips.insertTrip(
        Trip(
            id = 0,
            type = TripType.MULTI_DAY,
            status = TripStatus.ACTIVE,
            profile = RouteProfile.BALANCED,
            createdAt = 1_000L,
            lastActiveAt = 2_000L,
            completedAt = null,
        ),
    )

    private val totals = SegmentTotals(
        distanceM = 1_000.0,
        movingTimeMs = 60_000L,
        elapsedMs = 70_000L,
        maxSpeedMps = 8.0,
        ascentM = 4.0,
        descentM = 2.0,
        minAltitudeM = 10.0,
        maxAltitudeM = 20.0,
    )

    /** [endedAt]가 null이면 열린(기록 중인) 구간. */
    private suspend fun segment(tripId: Long, startedAt: Long, endedAt: Long?): Long {
        val id = rides.openSegment(tripId, startedAt, day)
        if (endedAt != null) rides.closeSegment(id, endedAt, totals)
        return id
    }

    /** 할 일이 끝난 extras. READY는 자세한 심박(자세하지 않은 READY는 하루 동안 다시 읽을 일이 남는다 — 스펙 §6.1). */
    private fun done(segmentId: Long, status: HrStatus = HrStatus.READY) = SegmentExtras(
        segmentId,
        photosScannedAt = 1L,
        photoScanDone = true,
        hrStatus = status,
        hrDetailed = status == HrStatus.READY,
        simplifiedDone = true,
    )

    private fun photo(mediaId: Long, takenAt: Long, point: GeoPoint = GeoPoint(37.5, 127.0)) =
        PlacedPhoto(mediaId, takenAt, point, PhotoPositionSource.TRACK)

    private suspend fun assertSegmentGone(segmentId: Long, block: suspend () -> Unit) {
        try {
            block()
            fail("expected SegmentGone for $segmentId")
        } catch (e: SegmentGone) {
            assertEquals(segmentId, e.segmentId)
        }
    }

    @Test
    fun upsertPhotosKeepsHiddenAndSkipsDuplicates() = runTest {
        val seg = segment(newTrip(), 1_000L, 9_000L)
        store.upsertPhotos(seg, listOf(photo(11, 3_000L), photo(12, 2_000L)))
        val first = store.observePhotos(listOf(seg)).first()
        assertEquals(listOf(12L, 11L), first.map { it.mediaId }) // 찍은 순
        val hiddenId = first.first { it.mediaId == 11L }.id
        store.setHidden(hiddenId, true)

        // 다시 찾기: 같은 사진(다른 위치로 계산됨) + 새 사진.
        store.upsertPhotos(seg, listOf(photo(11, 3_000L, GeoPoint(1.0, 1.0)), photo(13, 4_000L)))

        val after = store.observePhotos(listOf(seg)).first()
        assertEquals(listOf(12L, 11L, 13L), after.map { it.mediaId })
        assertEquals(
            RidePhoto(hiddenId, seg, 11, 3_000L, GeoPoint(37.5, 127.0), PhotoPositionSource.TRACK, hidden = true),
            after.first { it.mediaId == 11L },
        )
        assertEquals(listOf(false, true, false), after.map { it.hidden })

        store.setHidden(hiddenId, false)
        assertEquals(listOf(false, false, false), store.observePhotos(listOf(seg)).first().map { it.hidden })
    }

    @Test
    fun observePhotosCoversRequestedSegmentsAndDeleteByMediaIdRemovesEverywhere() = runTest {
        val trip = newTrip()
        val a = segment(trip, 1_000L, 2_000L)
        val b = segment(trip, 3_000L, 4_000L)
        val c = segment(trip, 5_000L, 6_000L)
        store.upsertPhotos(a, listOf(photo(1, 1_500L), photo(2, 1_600L)))
        store.upsertPhotos(b, listOf(photo(2, 3_500L), photo(3, 3_600L)))
        store.upsertPhotos(c, listOf(photo(4, 5_500L)))

        assertEquals(listOf(1L, 2L, 2L, 3L), store.observePhotos(listOf(a, b)).first().map { it.mediaId })
        assertEquals(emptyList<RidePhoto>(), store.observePhotos(emptyList()).first())

        // 바인드 변수 한도(999)를 넘는 목록도 지운다.
        store.deletePhotosByMediaId((100L..2_100L).toList() + 2L)
        assertEquals(listOf(1L, 3L, 4L), store.observePhotos(listOf(a, b, c)).first().map { it.mediaId })
        store.deletePhotosByMediaId(emptyList())
        assertEquals(3, store.observePhotos(listOf(a, b, c)).first().size)
    }

    @Test
    fun segmentsNeedingWorkSkipsOpenAndDone() = runTest {
        val trip = newTrip()
        val endedAt = 10_000L
        val open = segment(trip, 1_000L, null)
        val noExtras = segment(trip, 2_000L, endedAt)
        val allDone = segment(trip, 3_000L, endedAt)
        val none = segment(trip, 4_000L, endedAt)
        val photosLeft = segment(trip, 5_000L, endedAt)
        val simplifyLeft = segment(trip, 6_000L, endedAt)
        val pending = segment(trip, 7_000L, endedAt)
        val failed = segment(trip, 8_000L, endedAt)
        store.saveExtras(SegmentExtras(open)) // 열린 구간은 extras가 있어도 제외
        store.saveExtras(done(allDone))
        store.saveExtras(done(none, HrStatus.NONE))
        store.saveExtras(done(photosLeft).copy(photoScanDone = false))
        store.saveExtras(done(simplifyLeft).copy(simplifiedDone = false))
        store.saveExtras(done(pending, HrStatus.PENDING))
        store.saveExtras(done(failed, HrStatus.FAILED))

        val limit = endedAt + HrRetryPolicy.GIVE_UP_MS
        // 최근 시작 순
        val within = listOf(failed, pending, simplifyLeft, photosLeft, noExtras)
        assertEquals(within, store.segmentsNeedingWork(now = 11_000L).map { it.id })
        assertEquals(within, store.segmentsNeedingWork(now = limit).map { it.id }) // 한도 포함
        // 한도를 넘긴 FAILED는 [다시 가져오기]로만 다시 읽는다. PENDING은 늘 남는다.
        assertEquals(
            listOf(pending, simplifyLeft, photosLeft, noExtras),
            store.segmentsNeedingWork(now = limit + 1).map { it.id },
        )
        assertEquals(endedAt, store.segmentsNeedingWork(now = 11_000L).first().endedAt)
    }

    /** 판정 R14: FAILED는 max(구간 끝, 처음 읽은 시각) + 24시간까지 목록에 남는다. */
    @Test
    fun failedIsListedForADayFromItsFirstRead() = runTest {
        val trip = newTrip()
        val endedAt = 10_000L
        val day = HrRetryPolicy.GIVE_UP_MS
        val backlog = segment(trip, 1_000L, endedAt) // 업그레이드 뒤 처음 읽다 실패한 오래된 구간
        val recent = segment(trip, 2_000L, endedAt) // 끝나고 1분 뒤 처음 읽다 실패
        val early = segment(trip, 3_000L, endedAt) // 처음 읽은 시각이 끝보다 앞(있을 수 없지만 끝이 기준)
        val firstTry = endedAt + 3 * day
        store.saveExtras(done(backlog, HrStatus.FAILED).copy(hrFirstTriedAt = firstTry, hrLastTriedAt = firstTry))
        store.saveExtras(done(recent, HrStatus.FAILED).copy(hrFirstTriedAt = endedAt + 60_000L))
        store.saveExtras(done(early, HrStatus.FAILED).copy(hrFirstTriedAt = endedAt - 5_000L))

        assertEquals(listOf(early, recent, backlog), store.segmentsNeedingWork(now = endedAt + day).map { it.id })
        assertEquals(listOf(recent, backlog), store.segmentsNeedingWork(now = endedAt + day + 1).map { it.id })
        assertEquals(listOf(recent, backlog), store.segmentsNeedingWork(now = endedAt + 60_000L + day).map { it.id })
        assertEquals(listOf(backlog), store.segmentsNeedingWork(now = endedAt + 60_000L + day + 1).map { it.id })
        assertEquals(listOf(backlog), store.segmentsNeedingWork(now = firstTry + day).map { it.id }) // 한도 포함
        assertEquals(emptyList<Long>(), store.segmentsNeedingWork(now = firstTry + day + 1).map { it.id })
    }

    /** 스펙 §6.1: 자세하지 않은 READY는 구간 끝 + 24시간(포함)까지 할 일이 남은 구간이다. */
    @Test
    fun sparseReadyIsWorkWithinTwentyFourHours() = runTest {
        val trip = newTrip()
        val endedAt = 10_000L
        val sparse = segment(trip, 1_000L, endedAt)
        store.saveExtras(done(sparse).copy(hrDetailed = false, hrFirstTriedAt = endedAt + 60_000L))

        assertEquals(listOf(sparse), store.segmentsNeedingWork(now = endedAt + 60 * 60_000L).map { it.id })
        assertEquals(listOf(sparse), store.segmentsNeedingWork(now = endedAt + HrRetryPolicy.GIVE_UP_MS).map { it.id })
    }

    @Test
    fun sparseReadyLeavesWorkListAfterTwentyFourHours() = runTest {
        val trip = newTrip()
        val endedAt = 10_000L
        val sparse = segment(trip, 1_000L, endedAt)
        // 처음 읽은 시각이 늦어도 한도는 구간 끝 + 24시간
        store.saveExtras(done(sparse).copy(hrDetailed = false, hrFirstTriedAt = endedAt + 3 * HrRetryPolicy.GIVE_UP_MS))

        assertEquals(emptyList<Long>(), store.segmentsNeedingWork(now = endedAt + HrRetryPolicy.GIVE_UP_MS + 1).map { it.id })
    }

    @Test
    fun detailedReadyIsNotWork() = runTest {
        val trip = newTrip()
        val endedAt = 10_000L
        val detailed = segment(trip, 1_000L, endedAt)
        store.saveExtras(done(detailed).copy(hrDetailed = true))

        assertEquals(emptyList<Long>(), store.segmentsNeedingWork(now = endedAt + 60_000L).map { it.id })
    }

    /** 판정 R13 정리: 한 구간의 사진만 지우고, 다른 구간의 같은 사진은 남긴다. */
    @Test
    fun deletePhotosRemovesOnlyThatSegmentsRows() = runTest {
        val trip = newTrip()
        val a = segment(trip, 1_000L, 2_000L)
        val b = segment(trip, 2_000L, 3_000L)
        store.upsertPhotos(a, listOf(photo(1, 1_900L), photo(2, 2_100L)))
        store.upsertPhotos(b, listOf(photo(1, 1_900L), photo(2, 2_100L), photo(3, 2_500L)))

        store.deletePhotos(b, listOf(1L))
        store.deletePhotos(a, listOf(2L))
        store.deletePhotos(a, emptyList())
        // 바인드 변수 한도(999)를 넘는 목록도
        store.deletePhotos(b, (100L..2_100L).toList())

        assertEquals(listOf(1L), store.observePhotos(listOf(a)).first().map { it.mediaId })
        assertEquals(listOf(2L, 3L), store.observePhotos(listOf(b)).first().map { it.mediaId })
    }

    @Test
    fun extrasSaveReplacesAndObserves() = runTest {
        val trip = newTrip()
        val a = segment(trip, 1_000L, 2_000L)
        val b = segment(trip, 3_000L, 4_000L)
        assertNull(store.extras(a))

        store.saveExtras(SegmentExtras(a))
        val ready = SegmentExtras(
            a, photosScannedAt = 5L, photoScanDone = true, hrStatus = HrStatus.READY, hrFirstTriedAt = 6L,
            hrLastTriedAt = 7L, hrDetailed = true, avgBpm = 120, maxBpm = 160, calories = 300.5, simplifiedDone = true,
        )
        store.saveExtras(ready)
        store.saveExtras(SegmentExtras(b, hrStatus = HrStatus.NONE))

        assertEquals(ready, store.extras(a))
        assertEquals(
            setOf(ready, SegmentExtras(b, hrStatus = HrStatus.NONE)),
            store.observeExtras(listOf(a, b)).first().toSet(),
        )
        assertEquals(listOf(ready), store.observeExtras(listOf(a)).first())
    }

    @Test
    fun replaceSamplesReplacesAndReadsInTimeOrder() = runTest {
        val trip = newTrip()
        val a = segment(trip, 1_000L, 2_000L)
        val b = segment(trip, 3_000L, 4_000L)
        store.replaceSamples(a, listOf(HeartRateSample(1_200L, 100), HeartRateSample(1_100L, 95)))
        store.replaceSamples(b, listOf(HeartRateSample(3_100L, 140)))
        assertEquals(listOf(HeartRateSample(1_100L, 95), HeartRateSample(1_200L, 100)), store.samples(a))

        store.replaceSamples(a, listOf(HeartRateSample(1_500L, 130)))
        assertEquals(listOf(HeartRateSample(1_500L, 130)), store.samples(a))
        assertEquals(listOf(HeartRateSample(3_100L, 140)), store.samples(b))

        store.replaceSamples(a, emptyList())
        assertEquals(emptyList<HeartRateSample>(), store.samples(a))
    }

    @Test
    fun deletingSegmentCascades() = runTest {
        val trip = newTrip()
        val gone = segment(trip, 1_000L, 2_000L)
        val kept = segment(trip, 3_000L, 4_000L)
        for (seg in listOf(gone, kept)) {
            store.saveExtras(done(seg))
            store.upsertPhotos(seg, listOf(photo(seg * 10, 1_500L)))
            store.replaceSamples(seg, listOf(HeartRateSample(1_500L, 120)))
            store.saveSimplified(seg, CumulativeEntry(seg, day, 1_000.0, 60_000L, listOf(GeoPoint(37.5, 127.0))))
        }

        rides.deleteSegment(gone)

        assertNull(store.extras(gone))
        assertEquals(emptyList<RidePhoto>(), store.observePhotos(listOf(gone)).first())
        assertEquals(emptyList<HeartRateSample>(), store.samples(gone))
        assertEquals(listOf(kept), store.cumulativeEntries().map { it.segmentId })
        assertEquals(done(kept), store.extras(kept))
        assertEquals(1, store.observePhotos(listOf(kept)).first().size)
        assertEquals(1, store.samples(kept).size)

        // 여행을 지우면 구간을 거쳐 후처리 표도 함께 지워진다.
        rides.deleteTrip(trip)
        assertNull(store.extras(kept))
        assertEquals(emptyList<RidePhoto>(), store.observePhotos(listOf(kept)).first())
        assertEquals(emptyList<HeartRateSample>(), store.samples(kept))
        assertEquals(emptyList<CumulativeEntry>(), store.cumulativeEntries())
    }

    @Test
    fun cumulativeEntriesDecodePoints() = runTest {
        val trip = newTrip()
        val later = segment(trip, 1_000L, 2_000L)
        val earlier = segment(trip, 3_000L, 4_000L)
        val laterEntry = CumulativeEntry(
            later, LocalDate.of(2026, 10, 18), 15_000.0, 3_600_000L,
            listOf(GeoPoint(37.56651, 126.97801), GeoPoint(37.57, 126.98), GeoPoint(37.55123, 127.00456)),
        )
        val earlierEntry = CumulativeEntry(earlier, LocalDate.of(2025, 5, 1), 800.0, 120_000L, listOf(GeoPoint(35.1, 129.04)))
        store.saveSimplified(later, laterEntry)
        // 다시 저장하면 덮어쓴다. entry의 segmentId가 아니라 인자의 segmentId로 저장한다.
        store.saveSimplified(earlier, earlierEntry.copy(segmentId = 999, distanceM = 1.0))
        store.saveSimplified(earlier, earlierEntry.copy(segmentId = 999))

        assertEquals(listOf(earlierEntry, laterEntry), store.cumulativeEntries()) // 날짜순

        val row = db.postDao().simplifiedRoutes().first { it.segmentId == later }
        assertEquals(37.55123, row.minLat!!, 0.0)
        assertEquals(126.97801, row.minLon!!, 0.0)
        assertEquals(37.57, row.maxLat!!, 0.0)
        assertEquals(127.00456, row.maxLon!!, 0.0)
    }

    @Test
    fun anUnreadableRouteIsLeftOutNotTheList() = runTest {
        val trip = newTrip()
        val good = segment(trip, 1_000L, 2_000L)
        val broken = segment(trip, 3_000L, 4_000L)
        val goodEntry = CumulativeEntry(good, day, 1_000.0, 60_000L, listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.0)))
        store.saveSimplified(good, goodEntry)
        store.saveSimplified(broken, goodEntry)
        val row = db.postDao().simplifiedRoutes().first { it.segmentId == broken }
        db.postDao().saveSimplified(row.copy(points = "_p~iF~ps|U_")) // cut off inside a value
        val warnings = mutableListOf<String>()
        val logging = RoomPostStore(db, log = { message, _ -> warnings += message })

        assertEquals(listOf(goodEntry), logging.cumulativeEntries())
        assertEquals(1, warnings.size)
    }

    @Test
    fun writeForMissingSegmentThrowsSegmentGone() = runTest {
        val missing = 999L
        assertSegmentGone(missing) { store.saveExtras(SegmentExtras(missing)) }
        assertSegmentGone(missing) { store.upsertPhotos(missing, listOf(photo(1, 1_000L))) }
        assertSegmentGone(missing) { store.replaceSamples(missing, listOf(HeartRateSample(1_000L, 100))) }
        assertSegmentGone(missing) {
            store.saveSimplified(missing, CumulativeEntry(missing, day, 1.0, 1L, listOf(GeoPoint(37.5, 127.0))))
        }

        // 처리 도중 지워진 구간도 같다.
        val seg = segment(newTrip(), 1_000L, 2_000L)
        rides.deleteSegment(seg)
        assertSegmentGone(seg) { store.saveExtras(done(seg)) }
        assertSegmentGone(seg) { store.replaceSamples(seg, listOf(HeartRateSample(1_000L, 100))) }

        // 실패한 쓰기는 아무것도 남기지 않는다.
        assertNull(store.extras(missing))
        assertEquals(emptyList<HeartRateSample>(), store.samples(missing))
        assertEquals(emptyList<CumulativeEntry>(), store.cumulativeEntries())
    }
}

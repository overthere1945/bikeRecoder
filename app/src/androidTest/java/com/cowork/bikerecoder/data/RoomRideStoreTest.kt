package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.BestEffort
import com.cowork.bikerecoder.core.activity.BestEffortDistance
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TripDeleteCondition
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.StoredBestEffort
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomRideStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var trips: RoomTripStore
    private lateinit var store: RoomRideStore

    private val day1 = LocalDate.of(2026, 10, 11)
    private val day2 = LocalDate.of(2026, 10, 12)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trips = RoomTripStore(db)
        store = RoomRideStore(db)
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

    private fun totals(distanceM: Double, movingTimeMs: Long = 1_000L) = SegmentTotals(
        distanceM = distanceM,
        movingTimeMs = movingTimeMs,
        elapsedMs = movingTimeMs + 500L,
        maxSpeedMps = 8.0,
        ascentM = 4.0,
        descentM = 2.0,
        minAltitudeM = 10.0,
        maxAltitudeM = 20.0,
    )

    private fun point(timeMillis: Long) =
        TrackPoint(timeMillis, GeoPoint(37.5, 127.0), altitudeM = 12.0, speedMps = 5f, accuracyM = 4f)

    @Test
    fun recentSegmentsNewestFirstOverOneKm() = runTest {
        val tripId = newTrip()
        suspend fun closed(startedAt: Long, distanceM: Double, movingTimeMs: Long = 400_000L): Long {
            val id = store.openSegment(tripId, startedAt, day1)
            store.closeSegment(id, startedAt + 500L, totals(distanceM, movingTimeMs))
            return id
        }
        val oldest = closed(1_000L, 2_000.0)
        closed(4_000L, 3_000.0, movingTimeMs = 0L) // 이동 시간 0
        closed(3_000L, 999.0) // 1 km 미만
        val middle = closed(2_000L, 1_500.0)
        val newest = closed(5_000L, 1_000.0) // 정확히 1 km는 들어간다
        store.openSegment(tripId, 6_000L, day1).also { // 열린 구간은 빠진다
            store.appendPoints(it, listOf(point(6_100L)), totals(3_000.0))
        }

        assertEquals(listOf(newest, middle, oldest), store.recentSegments(10).map { it.id })
        assertEquals(listOf(newest, middle), store.recentSegments(2).map { it.id })
        assertEquals(1_500.0, store.recentSegments(10)[1].totals.distanceM, 0.0)
    }

    private suspend fun closedSegment(
        tripId: Long,
        startedAt: Long,
        activity: ActivityType,
        distanceM: Double = 2_000.0,
        movingTimeMs: Long = 400_000L,
        date: LocalDate = day1,
    ): Long {
        val id = store.openSegment(tripId, startedAt, date, activity)
        store.closeSegment(id, startedAt + 500L, totals(distanceM, movingTimeMs))
        return id
    }

    /** The raw best-effort rows of one segment as (distanceM, timeMs, startIndex), whether or not it has ended. */
    private fun effortRows(segmentId: Long): List<Triple<Int, Long, Int>> =
        db.openHelper.readableDatabase.query(
            "SELECT distanceM, timeMs, startIndex FROM segment_best_effort WHERE segmentId = $segmentId ORDER BY distanceM",
        ).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getInt(0), c.getLong(1), c.getInt(2))) }
        }

    private fun effort(distance: BestEffortDistance, timeMs: Long, startIndex: Int = 0) =
        BestEffort(distance, timeMs, startIndex)

    @Test
    fun openSegmentStoresActivity() = runTest {
        val tripId = newTrip()
        val run = store.openSegment(tripId, 1_000L, day1, ActivityType.RUN)
        val default = store.openSegment(tripId, 2_000L, day1)

        assertEquals(ActivityType.RUN, store.segment(run)!!.activity)
        assertEquals(ActivityType.BIKE, store.segment(default)!!.activity)
        assertEquals(listOf(ActivityType.RUN, ActivityType.BIKE), store.segments(tripId).map { it.activity })
        assertEquals(setOf(run, default), store.openSegments().map { it.id }.toSet())
        assertEquals(ActivityType.RUN, store.openSegments().single { it.id == run }.activity)
    }

    @Test
    fun dayTotalsCountOnlyBike() = runTest {
        val tripId = newTrip()
        closedSegment(tripId, 1_000L, ActivityType.BIKE, distanceM = 3_000.0, movingTimeMs = 600_000L)
        closedSegment(tripId, 2_000L, ActivityType.WALK, distanceM = 5_000.0, movingTimeMs = 900_000L)
        closedSegment(tripId, 3_000L, ActivityType.RUN, distanceM = 7_000.0, movingTimeMs = 800_000L)
        closedSegment(tripId, 4_000L, ActivityType.HIKE, distanceM = 9_000.0, movingTimeMs = 700_000L)
        closedSegment(tripId, 5_000L, ActivityType.BIKE, distanceM = 1_000.0, movingTimeMs = 100_000L)

        val day = store.dayTotals(tripId, day1)
        assertEquals(4_000.0, day.distanceM, 0.0)
        assertEquals(700_000L, day.movingTimeMs)
        assertEquals(0.0, store.dayTotals(tripId, day2).distanceM, 0.0)
    }

    @Test
    fun recentSegmentsOnlyBike() = runTest {
        val tripId = newTrip()
        val bike = closedSegment(tripId, 1_000L, ActivityType.BIKE)
        closedSegment(tripId, 2_000L, ActivityType.RUN)
        closedSegment(tripId, 3_000L, ActivityType.WALK)
        closedSegment(tripId, 4_000L, ActivityType.HIKE)
        val bike2 = closedSegment(tripId, 5_000L, ActivityType.BIKE)

        assertEquals(listOf(bike2, bike), store.recentSegments(10).map { it.id })
    }

    @Test
    fun saveBestEffortsReplacesRows() = runTest {
        val id = closedSegment(newTrip(), 1_000L, ActivityType.RUN)

        store.saveBestEfforts(id, listOf(effort(BestEffortDistance.ONE_K, 250_000L, 3), effort(BestEffortDistance.FIVE_K, 1_400_000L, 7)))
        assertEquals(listOf(Triple(1000, 250_000L, 3), Triple(5000, 1_400_000L, 7)), effortRows(id))

        store.saveBestEfforts(id, listOf(effort(BestEffortDistance.ONE_K, 240_000L, 5)))
        assertEquals(listOf(Triple(1000, 240_000L, 5)), effortRows(id))

        store.saveBestEfforts(id, emptyList())
        assertEquals(emptyList<Triple<Int, Long, Int>>(), effortRows(id))
    }

    @Test
    fun changeActivityReplacesTotalsAndEfforts() = runTest {
        val id = closedSegment(newTrip(), 1_000L, ActivityType.RUN, distanceM = 6_000.0)
        store.saveBestEfforts(id, listOf(effort(BestEffortDistance.ONE_K, 250_000L)))
        val recomputed = totals(5_500.0, movingTimeMs = 1_500_000L)

        store.changeActivity(id, ActivityType.WALK, recomputed, emptyList())
        val walk = store.segment(id)!!
        assertEquals(ActivityType.WALK, walk.activity)
        assertEquals(recomputed, walk.totals)
        assertEquals(emptyList<Triple<Int, Long, Int>>(), effortRows(id))

        val efforts = listOf(effort(BestEffortDistance.ONE_K, 230_000L, 2), effort(BestEffortDistance.FIVE_K, 1_300_000L, 4))
        store.changeActivity(id, ActivityType.RUN, totals(6_000.0), efforts)
        assertEquals(ActivityType.RUN, store.segment(id)!!.activity)
        assertEquals(totals(6_000.0), store.segment(id)!!.totals)
        assertEquals(listOf(Triple(1000, 230_000L, 2), Triple(5000, 1_300_000L, 4)), effortRows(id))
    }

    @Test
    fun changeActivityNullTotalsKeepsTotals() = runTest {
        val id = closedSegment(newTrip(), 1_000L, ActivityType.BIKE, distanceM = 6_000.0)
        val before = store.segment(id)!!.totals

        store.changeActivity(id, ActivityType.HIKE, null, emptyList())

        val after = store.segment(id)!!
        assertEquals(ActivityType.HIKE, after.activity)
        assertEquals(before, after.totals)
        assertEquals(1_500L, after.endedAt) // the closing time is untouched
    }

    @Test
    fun deletingSegmentDeletesEfforts() = runTest {
        val tripId = newTrip()
        val gone = closedSegment(tripId, 1_000L, ActivityType.RUN)
        val kept = closedSegment(tripId, 2_000L, ActivityType.RUN)
        store.saveBestEfforts(gone, listOf(effort(BestEffortDistance.ONE_K, 250_000L)))
        store.saveBestEfforts(kept, listOf(effort(BestEffortDistance.ONE_K, 260_000L)))

        store.deleteSegment(gone)

        assertEquals(emptyList<Triple<Int, Long, Int>>(), effortRows(gone))
        assertEquals(listOf(Triple(1000, 260_000L, 0)), effortRows(kept))
    }

    @Test
    fun observeBestEffortsEmitsEndedSegmentsAndSkipsUnknownDistances() = runTest {
        val stats = RoomRideStatsSource(db)
        val tripId = newTrip()
        val ended = closedSegment(tripId, 1_000L, ActivityType.RUN)
        val open = store.openSegment(tripId, 5_000L, day1, ActivityType.RUN)
        store.saveBestEfforts(ended, listOf(effort(BestEffortDistance.ONE_K, 250_000L), effort(BestEffortDistance.HALF, 6_000_000L)))
        store.saveBestEfforts(open, listOf(effort(BestEffortDistance.ONE_K, 240_000L)))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO segment_best_effort (segmentId, distanceM, timeMs, startIndex) VALUES ($ended, 3000, 1, 0)",
        )

        assertEquals(
            listOf(
                StoredBestEffort(ended, BestEffortDistance.ONE_K, 250_000L),
                StoredBestEffort(ended, BestEffortDistance.HALF, 6_000_000L),
            ),
            stats.observeBestEfforts().first(),
        )
    }

    @Test
    fun observeRidesCarriesActivity() = runTest {
        val tripId = newTrip()
        val id = closedSegment(tripId, 1_000L, ActivityType.HIKE)

        val stat = RoomRideStatsSource(db).observeRides().first().single()
        assertEquals(id, stat.segmentId)
        assertEquals(ActivityType.HIKE, stat.activity)
        assertEquals(20.0, stat.maxAltitudeM!!, 0.0)
    }

    @Test
    fun appendUpdatesPointsAndTotalsTogether() = runTest {
        val segmentId = store.openSegment(newTrip(), startedAt = 10_000L, localDate = day2)

        store.appendPoints(segmentId, listOf(point(11_000L), point(12_000L)), totals(100.0))
        store.appendPoints(segmentId, listOf(point(13_000L)), totals(250.0, movingTimeMs = 3_000L))

        assertEquals(listOf(11_000L, 12_000L, 13_000L), store.points(segmentId).map { it.timeMillis })
        assertEquals(totals(250.0, movingTimeMs = 3_000L), store.segment(segmentId)!!.totals)
        assertNull(store.segment(segmentId)!!.endedAt)
    }

    @Test
    fun observeRidesEmitsEndedSegmentsOnly() = runTest {
        val stats = RoomRideStatsSource(db)
        val tripId = newTrip()
        val done = store.openSegment(tripId, startedAt = 10_000L, localDate = day1)
        store.closeSegment(done, 11_000L, totals(1_200.0, 60_000L))
        val open = store.openSegment(tripId, startedAt = 20_000L, localDate = day2)

        assertEquals(
            listOf(RideStat(done, tripId, 10_000L, day1, 1_200.0, 60_000L, 4.0, 11_000L, ActivityType.BIKE, 60_500L, 20.0)),
            stats.observeRides().first(),
        )

        store.closeSegment(open, 21_000L, totals(5.0, 2_000L)) // 짧은 구간도 거르지 않고 내보낸다
        assertEquals(
            listOf(
                RideStat(done, tripId, 10_000L, day1, 1_200.0, 60_000L, 4.0, 11_000L, ActivityType.BIKE, 60_500L, 20.0),
                RideStat(open, tripId, 20_000L, day2, 5.0, 2_000L, 4.0, 21_000L, ActivityType.BIKE, 2_500L, 20.0),
            ),
            stats.observeRides().first(),
        )
    }

    /**
     * Review M4: a recording saves its open segment every 5 s; the list of ended rides is the same, so it is not sent again
     * (the stats and the feed do not rebuild for the whole ride). Closing the segment does send it.
     */
    @Test
    fun observeRidesIgnoresAGrowingOpenSegment() = runTest {
        val stats = RoomRideStatsSource(db)
        val tripId = newTrip()
        val done = store.openSegment(tripId, startedAt = 10_000L, localDate = day1)
        store.closeSegment(done, 11_000L, totals(1_200.0, 60_000L))
        val open = store.openSegment(tripId, startedAt = 20_000L, localDate = day2)

        val seen = withContext(Dispatchers.Default) {
            val seen = mutableListOf<List<RideStat>>()
            withTimeoutOrNull(3_000) {
                stats.observeRides().collect { rides ->
                    seen += rides
                    if (seen.size == 1) {
                        store.appendPoints(open, listOf(point(21_000L)), totals(30.0))
                        store.appendPoints(open, listOf(point(26_000L)), totals(60.0))
                        delay(1_000)
                        store.closeSegment(open, 27_000L, totals(60.0))
                    }
                }
            }
            seen
        }

        assertEquals(listOf(listOf(done), listOf(done, open)), seen.map { list -> list.map { it.segmentId } })
    }

    @Test
    fun closeAndOpenSegments() = runTest {
        val tripId = newTrip()
        val first = store.openSegment(tripId, startedAt = 10_000L, localDate = day2)
        val second = store.openSegment(tripId, startedAt = 20_000L, localDate = day2)
        assertEquals(listOf(second, first), store.openSegments().map { it.id })

        store.closeSegment(second, endedAt = 30_000L, totals = totals(500.0))

        assertEquals(listOf(first), store.openSegments().map { it.id })
        val closed = store.segment(second)!!
        assertEquals(30_000L, closed.endedAt)
        assertEquals(totals(500.0), closed.totals)
    }

    @Test
    fun dayTotalsSumOnlyThatTripAndDate() = runTest {
        val tripId = newTrip()
        val otherTrip = newTrip()
        val a = store.openSegment(tripId, 10_000L, day2)
        val b = store.openSegment(tripId, 20_000L, day2)
        val otherDay = store.openSegment(tripId, 30_000L, day1)
        val otherTripSegment = store.openSegment(otherTrip, 40_000L, day2)
        store.closeSegment(a, 11_000L, totals(100.0, 1_000L))
        store.closeSegment(b, 21_000L, totals(200.0, 2_000L))
        store.closeSegment(otherDay, 31_000L, totals(400.0, 4_000L))
        store.closeSegment(otherTripSegment, 41_000L, totals(800.0, 8_000L))

        val day = store.dayTotals(tripId, day2)

        assertEquals(300.0, day.distanceM, 0.0001)
        assertEquals(3_000L, day.movingTimeMs)
        assertEquals(0.0, store.dayTotals(tripId, LocalDate.of(2026, 1, 1)).distanceM, 0.0)
    }

    @Test
    fun tripSummariesNewestFirstWithDayCount() = runTest {
        val tripA = newTrip()
        val tripB = newTrip()
        val a1 = store.openSegment(tripA, 10_000L, day1)
        val a2 = store.openSegment(tripA, 20_000L, day2)
        val a3 = store.openSegment(tripA, 25_000L, day2)
        val b1 = store.openSegment(tripB, 90_000L, day2)
        store.closeSegment(a1, 11_000L, totals(1_000.0, 10_000L))
        store.closeSegment(a2, 21_000L, totals(2_000.0, 20_000L))
        store.closeSegment(a3, 26_000L, totals(500.0, 5_000L))
        store.closeSegment(b1, 91_000L, totals(300.0, 3_000L))
        newTrip() // 구간이 없는 여행은 목록에 나오지 않는다

        val summaries = store.tripSummaries()

        assertEquals(listOf(tripB, tripA), summaries.map { it.trip.id })
        val a = summaries[1]
        assertEquals(2, a.dayCount)
        assertEquals(day1, a.firstDate)
        assertEquals(3_500.0, a.distanceM, 0.0001)
        assertEquals(35_000L, a.movingTimeMs)
        assertEquals(25_000L, a.lastStartedAt)
        assertEquals(1, summaries[0].dayCount)
    }

    @Test
    fun tripSummariesSkipEmptySegments() = runTest {
        val tripA = newTrip()
        val tripB = newTrip()
        val empty = store.openSegment(tripA, 10_000L, day1)
        store.closeSegment(empty, 40_000L, totals(0.0, 0L))
        val a2 = store.openSegment(tripA, 50_000L, day2)
        store.closeSegment(a2, 60_000L, totals(800.0, 10_000L))
        val onlyEmpty = store.openSegment(tripB, 90_000L, day2)
        store.closeSegment(onlyEmpty, 95_000L, totals(0.0, 0L))

        val summaries = store.tripSummaries()

        assertEquals(listOf(tripA), summaries.map { it.trip.id })
        assertEquals(day2, summaries.single().firstDate)
        assertEquals(1, summaries.single().dayCount)
        assertEquals("kept in the store", 2, store.segments(tripA).size)
    }

    @Test
    fun deletingATripCascades() = runTest {
        val tripId = newTrip()
        trips.replaceStops(
            tripId,
            listOf(TripStop(0, tripId, 0, "도착", GeoPoint(37.5, 127.0), isDestination = true, visitedAt = null)),
        )
        val segmentId = store.openSegment(tripId, 10_000L, day2)
        store.appendPoints(segmentId, listOf(point(11_000L)), totals(10.0))

        store.deleteTrip(tripId)

        assertEquals(0, store.segments(tripId).size)
        assertEquals(0, store.points(segmentId).size)
        assertEquals(0, trips.stops(tripId).size)
        assertNull(trips.trip(tripId))
    }

    @Test
    fun deletingASegmentCascadesPoints() = runTest {
        val tripId = newTrip()
        val keep = store.openSegment(tripId, 10_000L, day2)
        val drop = store.openSegment(tripId, 20_000L, day2)
        store.appendPoints(keep, listOf(point(11_000L)), totals(10.0))
        store.appendPoints(drop, listOf(point(21_000L), point(22_000L)), totals(20.0))

        store.deleteSegment(drop)

        assertNull(store.segment(drop))
        assertEquals(0, store.points(drop).size)
        assertEquals(1, store.points(keep).size)
    }

    private suspend fun completedTrip(): Long = trips.insertTrip(
        Trip(0, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.BALANCED, 1_000L, 2_000L, 3_000L),
    )

    /** Ruling R20: each condition is checked in the deleting statement; only COMPLETED trips are ever deleted. */
    @Test
    fun conditionalTripDeleteChecksStatusAndSegments() = runTest {
        val active = newTrip()
        assertFalse(store.deleteTripIf(active, TripDeleteCondition.NO_SEGMENTS))
        assertFalse(store.deleteTripIf(active, TripDeleteCondition.NOT_RECORDING))
        assertFalse(store.deleteTripIf(active, TripDeleteCondition.SHORT_NOT_RECORDING))

        val short = completedTrip()
        val first = store.openSegment(short, startedAt = 10_000L, localDate = day1)
        // Recording (open segment): no condition holds.
        TripDeleteCondition.entries.forEach { assertFalse(it.name, store.deleteTripIf(short, it)) }
        store.closeSegment(first, 11_000L, totals(4.0))
        val second = store.openSegment(short, startedAt = 12_000L, localDate = day1)
        store.closeSegment(second, 13_000L, totals(6.0))
        assertFalse("a closed segment is left", store.deleteTripIf(short, TripDeleteCondition.NO_SEGMENTS))
        assertFalse("10 m in all is a ride", store.deleteTripIf(short, TripDeleteCondition.SHORT_NOT_RECORDING))
        store.deleteSegment(second)
        assertTrue(store.deleteTripIf(short, TripDeleteCondition.SHORT_NOT_RECORDING))
        assertNull(trips.trip(short))
        assertEquals(emptyList<Any>(), store.segments(short))

        val long = completedTrip()
        val ride = store.openSegment(long, startedAt = 20_000L, localDate = day2)
        store.closeSegment(ride, 21_000L, totals(500.0))
        assertTrue(store.deleteTripIf(long, TripDeleteCondition.NOT_RECORDING))
        assertNull(trips.trip(long))

        val emptied = completedTrip()
        val refused = store.deleteTripIf(emptied, TripDeleteCondition.SHORT_NOT_RECORDING)
        assertFalse("no segment: not a short ride", refused)
        assertTrue(store.deleteTripIf(emptied, TripDeleteCondition.NO_SEGMENTS))
        assertNull(trips.trip(emptied))
        assertFalse("already gone", store.deleteTripIf(emptied, TripDeleteCondition.NO_SEGMENTS))
    }

    /** Ruling R21: trips with segments that are all 0 m (not in the trip summaries). */
    @Test
    fun emptyTripsHaveOnlyZeroMetreSegments() = runTest {
        val empty = completedTrip()
        store.closeSegment(store.openSegment(empty, 1_000L, day1), 2_000L, totals(0.0))
        store.openSegment(empty, 3_000L, day1) // open, still 0 m
        val mixed = completedTrip()
        store.closeSegment(store.openSegment(mixed, 4_000L, day1), 5_000L, totals(0.0))
        store.closeSegment(store.openSegment(mixed, 6_000L, day1), 7_000L, totals(0.3))
        completedTrip() // no segment: never counted

        assertEquals(listOf(empty), store.emptyTrips().map { it.id })
        assertEquals(listOf(mixed), store.tripSummaries().map { it.trip.id })
    }
}

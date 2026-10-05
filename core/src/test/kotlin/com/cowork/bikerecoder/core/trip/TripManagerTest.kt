package com.cowork.bikerecoder.core.trip

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli()

/** 메모리 기반 [TripStore] 테스트 더블. id=0으로 들어오면 새 id를 발급한다. */
private class FakeTripStore : TripStore {
    val trips = mutableMapOf<Long, Trip>()
    val stopsByTrip = mutableMapOf<Long, MutableList<TripStop>>()

    override suspend fun activeTrip(): Trip? = trips.values.firstOrNull { it.status == TripStatus.ACTIVE }

    override suspend fun trip(id: Long): Trip? = trips[id]

    override suspend fun insertTrip(trip: Trip): Long {
        val id = if (trip.id == 0L) (trips.keys.maxOrNull() ?: 0L) + 1 else trip.id
        trips[id] = trip.copy(id = id)
        return id
    }

    override suspend fun updateTrip(trip: Trip) {
        trips[trip.id] = trip
    }

    override suspend fun stops(tripId: Long): List<TripStop> =
        stopsByTrip[tripId].orEmpty().sortedBy { it.order }

    override suspend fun replaceStops(tripId: Long, stops: List<TripStop>) {
        var nextId = (stopsByTrip.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1
        stopsByTrip[tripId] = stops.map { stop ->
            if (stop.id == 0L) stop.copy(id = nextId++) else stop
        }.toMutableList()
    }

    override suspend fun markVisited(stopId: Long, at: Long) {
        for (list in stopsByTrip.values) {
            val idx = list.indexOfFirst { it.id == stopId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(visitedAt = at)
                return
            }
        }
    }
}

/** 메모리 기반 [OfflineMapController] 테스트 더블. 호출된 tripId를 기록한다. */
private class FakeOfflineMapController : OfflineMapController {
    val deleted = mutableListOf<Long>()
    val downloaded = mutableListOf<Long>()

    override suspend fun deleteForTrip(tripId: Long) {
        deleted.add(tripId)
    }

    override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) {
        downloaded.add(tripId)
    }
}

private fun stop(name: String, order: Int = 0, isDestination: Boolean = false, id: Long = 0L, tripId: Long = 0L) =
    TripStop(
        id = id,
        tripId = tripId,
        order = order,
        name = name,
        point = GeoPoint(37.5, 127.0),
        isDestination = isDestination,
        visitedAt = null,
    )

class TripManagerTest {

    @Test
    fun `no active trip asks type`() = runTest {
        val manager = TripManager(FakeTripStore(), FakeOfflineMapController(), clock = { 0L })

        assertEquals(StartPrompt.AskType, manager.startPrompt())
    }

    @Test
    fun `active multi-day trip asks continue`() = runTest {
        val store = FakeTripStore()
        val active = Trip(
            id = 1, type = TripType.MULTI_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        store.trips[1] = active
        val manager = TripManager(store, FakeOfflineMapController(), clock = { 0L })

        val prompt = manager.startPrompt()

        assertEquals(StartPrompt.AskContinue(active), prompt)
    }

    @Test
    fun `active single day trip asks type`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        val manager = TripManager(store, FakeOfflineMapController(), clock = { 0L })

        assertEquals(StartPrompt.AskType, manager.startPrompt())
    }

    @Test
    fun `starting new trip completes previous`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 100L, lastActiveAt = 100L, completedAt = null,
        )
        val offline = FakeOfflineMapController()
        val now = 5_000L
        val manager = TripManager(store, offline, clock = { now })

        val newTrip = manager.startTrip(TripType.MULTI_DAY, RouteProfile.CYCLEWAY_FIRST, listOf(stop("목적지", isDestination = true)))

        val previous = store.trip(1)
        assertTrue(previous != null)
        assertEquals(TripStatus.COMPLETED, previous.status)
        assertEquals(now, previous.completedAt)
        assertTrue(offline.deleted.contains(1L))

        assertTrue(newTrip.id != 1L)
        assertEquals(TripType.MULTI_DAY, newTrip.type)
        assertEquals(TripStatus.ACTIVE, newTrip.status)
        assertEquals(RouteProfile.CYCLEWAY_FIRST, newTrip.profile)
        assertEquals(now, newTrip.createdAt)
        assertEquals(now, newTrip.lastActiveAt)
        assertNull(newTrip.completedAt)

        val stops = store.stops(newTrip.id)
        assertEquals(1, stops.size)
        assertEquals("목적지", stops[0].name)
        assertEquals(newTrip.id, stops[0].tripId)
        assertEquals(0, stops[0].order)
    }

    @Test
    fun `single day complete deletes offline maps`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        val offline = FakeOfflineMapController()
        val now = 10_000L
        val manager = TripManager(store, offline, clock = { now })

        manager.complete(1)

        val trip = store.trip(1)
        assertTrue(trip != null)
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertEquals(now, trip.completedAt)
        assertEquals(listOf(1L), offline.deleted)
    }

    @Test
    fun `end today keeps multi-day trip active`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.MULTI_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        val offline = FakeOfflineMapController()
        val now = 20_000L
        val manager = TripManager(store, offline, clock = { now })

        manager.endToday(1)

        val trip = store.trip(1)
        assertTrue(trip != null)
        assertEquals(TripStatus.ACTIVE, trip.status)
        assertEquals(now, trip.lastActiveAt)
        assertTrue(offline.deleted.isEmpty())
    }

    @Test
    fun `end today on single day trip throws`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        val manager = TripManager(store, FakeOfflineMapController(), clock = { 0L })

        kotlin.test.assertFailsWith<IllegalStateException> {
            manager.endToday(1)
        }
    }

    @Test
    fun `convert completed single day to multi day`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.COMPLETED, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = 500L,
        )
        val manager = TripManager(store, FakeOfflineMapController(), clock = { 999L })

        manager.convertToMultiDay(1)

        val trip = store.trip(1)
        assertTrue(trip != null)
        assertEquals(TripType.MULTI_DAY, trip.type)
        assertEquals(TripStatus.ACTIVE, trip.status)
        assertNull(trip.completedAt)
    }

    @Test
    fun `convert active trip to multi day throws`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        val manager = TripManager(store, FakeOfflineMapController(), clock = { 999L })

        kotlin.test.assertFailsWith<IllegalStateException> {
            manager.convertToMultiDay(1)
        }
    }

    @Test
    fun `change stops deletes offline maps`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        store.replaceStops(1, listOf(stop("old", order = 0, tripId = 1)))
        val offline = FakeOfflineMapController()
        val now = 30_000L
        val manager = TripManager(store, offline, clock = { now })

        manager.changeStops(1, listOf(stop("new1"), stop("new2", isDestination = true)))

        assertTrue(offline.deleted.contains(1L))
        val stops = store.stops(1)
        assertEquals(listOf("new1", "new2"), stops.map { it.name })
        assertEquals(listOf(0, 1), stops.map { it.order })
        assertTrue(stops.all { it.tripId == 1L })

        val trip = store.trip(1)
        assertTrue(trip != null)
        assertEquals(now, trip.lastActiveAt)
    }

    @Test
    fun `change stops on unknown trip throws and leaves store and offline untouched`() = runTest {
        val store = FakeTripStore()
        val offline = FakeOfflineMapController()
        val manager = TripManager(store, offline, clock = { 0L })

        kotlin.test.assertFailsWith<IllegalStateException> {
            manager.changeStops(999, listOf(stop("new")))
        }

        assertTrue(offline.deleted.isEmpty())
        assertTrue(store.stopsByTrip[999].orEmpty().isEmpty())
    }

    @Test
    fun `stale after 3 days`() = runTest {
        val store = FakeTripStore()
        val threeDaysMs = 3L * 24 * 60 * 60 * 1000
        store.trips[1] = Trip(
            id = 1, type = TripType.MULTI_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )

        val exactlyStale = TripManager(store, FakeOfflineMapController(), clock = { threeDaysMs })
        assertTrue(exactlyStale.stalePrompt() != null)

        val almostStale = TripManager(store, FakeOfflineMapController(), clock = { threeDaysMs - 60 * 60 * 1000 })
        assertNull(almostStale.stalePrompt())

        store.trips[1] = store.trips[1]!!.copy(type = TripType.SINGLE_DAY)
        val singleDay = TripManager(store, FakeOfflineMapController(), clock = { threeDaysMs })
        assertNull(singleDay.stalePrompt())
    }

    @Test
    fun `day number counts calendar days`() = runTest {
        val createdAt = epochMillis(2026, 10, 4, 23, 0)
        val now = epochMillis(2026, 10, 6, 1, 0)
        val trip = Trip(
            id = 1, type = TripType.MULTI_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = createdAt, lastActiveAt = createdAt, completedAt = null,
        )
        val manager = TripManager(FakeTripStore(), FakeOfflineMapController(), clock = { now }, zone = ZONE)

        assertEquals(3, manager.dayNumber(trip))
    }

    @Test
    fun `remaining stops excludes visited`() = runTest {
        val store = FakeTripStore()
        store.trips[1] = Trip(
            id = 1, type = TripType.SINGLE_DAY, status = TripStatus.ACTIVE, profile = RouteProfile.BALANCED,
            createdAt = 0L, lastActiveAt = 0L, completedAt = null,
        )
        store.replaceStops(
            1,
            listOf(
                stop("경유지1", order = 0, tripId = 1, id = 11),
                stop("목적지", order = 1, isDestination = true, tripId = 1, id = 12),
            ),
        )
        val manager = TripManager(store, FakeOfflineMapController(), clock = { 1_000L })

        manager.markVisited(11)

        val remaining = manager.remainingStops(1)

        assertEquals(listOf("목적지"), remaining.map { it.name })
    }
}

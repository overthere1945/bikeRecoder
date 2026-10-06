package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTripStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomTripStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomTripStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun trip(status: TripStatus = TripStatus.ACTIVE, completedAt: Long? = null) = Trip(
        id = 0,
        type = TripType.MULTI_DAY,
        status = status,
        profile = RouteProfile.BALANCED,
        createdAt = 1_000L,
        lastActiveAt = 2_000L,
        completedAt = completedAt,
    )

    private fun stop(tripId: Long, order: Int) = TripStop(
        id = 0,
        tripId = tripId,
        order = order,
        name = "stop-$order",
        point = GeoPoint(37.5 + order, 127.0 + order),
        isDestination = order == 2,
        visitedAt = null,
    )

    @Test
    fun insertAndReadActiveTrip() = runTest {
        val id = store.insertTrip(trip())
        assertNotEquals(0L, id)

        val active = store.activeTrip()
        assertNotNull(active)
        assertEquals(id, active!!.id)
        assertEquals(TripType.MULTI_DAY, active.type)
        assertEquals(TripStatus.ACTIVE, active.status)
        assertEquals(RouteProfile.BALANCED, active.profile)
        assertEquals(1_000L, active.createdAt)
        assertEquals(2_000L, active.lastActiveAt)
        assertNull(active.completedAt)
        assertEquals(active, store.trip(id))
    }

    @Test
    fun replaceStopsKeepsOrder() = runTest {
        val id = store.insertTrip(trip())
        store.replaceStops(id, listOf(stop(id, 2), stop(id, 0), stop(id, 1)))

        val stops = store.stops(id)
        assertEquals(listOf(0, 1, 2), stops.map { it.order })
        assertEquals(listOf("stop-0", "stop-1", "stop-2"), stops.map { it.name })
        assertEquals(GeoPoint(38.5, 128.0), stops[1].point)
        assertEquals(listOf(false, false, true), stops.map { it.isDestination })

        // replacing discards the previous stops
        store.replaceStops(id, listOf(stop(id, 0)))
        assertEquals(1, store.stops(id).size)
    }

    @Test
    fun markVisitedPersists() = runTest {
        val id = store.insertTrip(trip())
        store.replaceStops(id, listOf(stop(id, 0), stop(id, 1)))
        val first = store.stops(id).first()

        store.markVisited(first.id, 5_000L)

        val stops = store.stops(id)
        assertEquals(5_000L, stops[0].visitedAt)
        assertNull(stops[1].visitedAt)
    }

    @Test
    fun completedTripIsNotActive() = runTest {
        val id = store.insertTrip(trip())
        store.updateTrip(trip().copy(id = id, status = TripStatus.COMPLETED, completedAt = 3_000L))

        assertNull(store.activeTrip())
        val saved = store.trip(id)
        assertEquals(TripStatus.COMPLETED, saved!!.status)
        assertEquals(3_000L, saved.completedAt)
    }

    @Test
    fun deletingTripCascadesStops() = runTest {
        val id = store.insertTrip(trip())
        store.replaceStops(id, listOf(stop(id, 0), stop(id, 1)))
        assertEquals(2, store.stops(id).size)

        db.tripDao().deleteTrip(id)

        assertNull(store.trip(id))
        assertEquals(0, store.stops(id).size)
    }

    @Test
    fun offlineRegionRefsRoundTrip() = runTest {
        val dao = db.offlineRegionRefDao()
        val tripId = store.insertTrip(trip())
        dao.insert(OfflineRegionRefEntity(0, tripId, 11L, "CORRIDOR", 1L))
        dao.insert(OfflineRegionRefEntity(0, tripId, 12L, "OVERVIEW", 2L))
        dao.insert(OfflineRegionRefEntity(0, tripId + 1, 13L, "CORRIDOR", 3L))

        assertEquals(listOf(11L, 12L), dao.forTrip(tripId).map { it.mapLibreRegionId })
        assertEquals(3, dao.all().size)

        dao.deleteForTrip(tripId)
        assertEquals(0, dao.forTrip(tripId).size)
        assertEquals(listOf(13L), dao.all().map { it.mapLibreRegionId })
    }
}

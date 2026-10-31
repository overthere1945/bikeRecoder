package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTripLabelStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var trips: RoomTripStore
    private lateinit var store: RoomTripLabelStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trips = RoomTripStore(db)
        store = RoomTripLabelStore(db)
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

    @Test
    fun missingRowIsEmptyLabel() = runTest {
        assertEquals(TripLabel(), store.label(newTrip()))
    }

    @Test
    fun setTitleKeepsRegion() = runTest {
        val id = newTrip()
        store.setRegionName(id, "양평")

        store.setCustomTitle(id, "가을 라이딩")

        assertEquals(TripLabel("가을 라이딩", "양평"), store.label(id))
    }

    @Test
    fun setRegionKeepsTitle() = runTest {
        val id = newTrip()
        store.setCustomTitle(id, "가을 라이딩")

        store.setRegionName(id, "양평")

        assertEquals(TripLabel("가을 라이딩", "양평"), store.label(id))
    }

    @Test
    fun nullTitleClears() = runTest {
        val id = newTrip()
        store.setCustomTitle(id, "가을 라이딩")
        store.setRegionName(id, "양평")

        store.setCustomTitle(id, null)

        assertEquals(TripLabel(null, "양평"), store.label(id))
    }

    @Test
    fun deletingTripDeletesLabel() = runTest {
        val id = newTrip()
        val other = newTrip()
        store.setCustomTitle(id, "가을 라이딩")
        store.setCustomTitle(other, "남는 것")

        RoomRideStore(db).deleteTrip(id)

        assertEquals(TripLabel(), store.label(id))
        assertEquals(mapOf(other to TripLabel("남는 것", null)), store.observeLabels().first())
    }

    @Test
    fun observeLabelsEmitsOnChange() = runTest {
        val id = newTrip()
        assertEquals(emptyMap<Long, TripLabel>(), store.observeLabels().first())

        store.setCustomTitle(id, "가을 라이딩")
        assertEquals(mapOf(id to TripLabel("가을 라이딩", null)), store.observeLabels().first())

        store.setRegionName(id, "양평")
        assertEquals(mapOf(id to TripLabel("가을 라이딩", "양평")), store.observeLabels().first())
    }
}

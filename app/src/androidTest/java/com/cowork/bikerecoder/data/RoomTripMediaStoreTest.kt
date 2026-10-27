package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTripMediaStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomTripMediaStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomTripMediaStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun trip(): Long =
        db.tripDao().insertTrip(TripEntity(0, "DAY", "COMPLETED", "BALANCED", 1_000L, 2_000L, 3_000L))

    private fun media(tripId: Long, kind: MediaKind, createdAt: Long) =
        TripMedia(0, tripId, kind, "content://media/external/test/$createdAt", createdAt, "{\"t\":$createdAt}")

    @Test
    fun addObserveNewestFirstPerTripAndRemove() = runTest {
        val tripA = trip()
        val tripB = trip()
        val video = store.add(media(tripA, MediaKind.VIDEO, 10_000L))
        val image = store.add(media(tripA, MediaKind.IMAGE, 20_000L))
        val other = store.add(media(tripB, MediaKind.VIDEO, 15_000L))

        assertEquals(
            listOf(media(tripA, MediaKind.IMAGE, 20_000L).copy(id = image), media(tripA, MediaKind.VIDEO, 10_000L).copy(id = video)),
            store.observe(tripA).first(),
        )

        store.remove(listOf(image))
        assertEquals(listOf(video), store.observe(tripA).first().map { it.id })
        store.remove(emptyList())
        assertEquals(listOf(video), store.observe(tripA).first().map { it.id })
        assertEquals(listOf(other), store.observe(tripB).first().map { it.id })
    }

    @Test
    fun deletingTheTripDeletesItsRecordsOnly() = runTest {
        val tripA = trip()
        val tripB = trip()
        store.add(media(tripA, MediaKind.VIDEO, 10_000L))
        store.add(media(tripA, MediaKind.IMAGE, 11_000L))
        val kept = store.add(media(tripB, MediaKind.IMAGE, 12_000L))

        db.tripDao().deleteTrip(tripA)

        assertEquals(emptyList<TripMedia>(), store.observe(tripA).first())
        assertEquals(listOf(kept), store.observe(tripB).first().map { it.id })
    }
}

package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomFavoriteStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomFavoriteStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomFavoriteStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertObserveUpdateDelete() = runTest {
        val homeId = store.insert(Favorite(0, FavoriteKind.HOME, "집", GeoPoint(37.5, 127.0), "서울", 1_000L))
        val customId = store.insert(Favorite(0, FavoriteKind.CUSTOM, "카페", GeoPoint(37.6, 127.1), null, 2_000L))
        assertNotEquals(homeId, customId)

        val inserted = store.observeAll().first()
        assertEquals(setOf(homeId, customId), inserted.map { it.id }.toSet())
        assertEquals(2, inserted.size)

        val home = inserted.first { it.id == homeId }
        store.update(home.copy(point = GeoPoint(37.7, 127.2), address = "부산"))
        val moved = store.observeAll().first().first { it.id == homeId }
        assertEquals(GeoPoint(37.7, 127.2), moved.point)
        assertEquals("부산", moved.address)
        assertEquals(FavoriteKind.HOME, moved.kind)

        store.delete(homeId)
        assertEquals(listOf(customId), store.all().map { it.id })
        assertEquals(listOf(customId), store.observeAll().first().map { it.id })
    }
}

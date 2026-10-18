package com.cowork.bikerecoder.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class DataStoreRecentPlacesTest {

    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()

    private fun dataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it },
        produceFile = { File(dir, "recent.preferences_pb") },
    )

    @AfterEach
    fun tearDown() = scopes.forEach { it.cancel() }

    private val station = Place("부산역", "부산 동구", GeoPoint(35.115, 129.041), 3_400)
    private val beach = Place("해운대해수욕장", "", GeoPoint(35.158, 129.160), null)

    @Test
    fun addedPlacesSurviveANewInstanceNewestFirstWithoutDistance() = runTest {
        val store = DataStoreRecentPlaces(dataStore())
        store.add(station)
        store.add(beach)
        store.add(station)
        scopes.forEach { it.cancel() } // the first DataStore must be closed before another opens the file

        assertEquals(listOf(station.copy(distanceM = null), beach), DataStoreRecentPlaces(dataStore()).places.first())
    }

    @Test
    fun removeAndClear() = runTest {
        val store = DataStoreRecentPlaces(dataStore())
        store.add(station)
        store.add(beach)

        store.remove(beach)
        assertEquals(listOf(station.copy(distanceM = null)), store.places.first())

        store.clear()
        assertTrue(store.places.first().isEmpty())
    }

    @Test
    fun brokenJsonReadsAsEmptyAndTheNextAddStartsOver() = runTest {
        val ds = dataStore()
        ds.edit { it[stringPreferencesKey("recent_places")] = "{not json" }
        val store = DataStoreRecentPlaces(ds)

        assertTrue(store.places.first().isEmpty())
        store.add(beach)
        assertEquals(listOf(beach), store.places.first())
    }

    /** [real], except that the first read of [data] fails with an IOException. */
    private class FirstReadFails(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        private var failed = false
        override val data = flow {
            if (!failed) {
                failed = true
                throw IOException("read failed")
            }
            emitAll(real.data)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = real.updateData(transform)
    }

    @Test
    fun aFailedReadShowsEmptyAndTheListKeepsUpdatingAfterwards() = runTest {
        val store = DataStoreRecentPlaces(FirstReadFails(dataStore()))
        val seen = Channel<List<Place>>(Channel.UNLIMITED)
        backgroundScope.launch { store.places.onCompletion { seen.close() }.collect { seen.send(it) } }

        assertEquals(emptyList<Place>(), seen.receive(), "the failed read")
        assertEquals(emptyList<Place>(), seen.receive(), "the retried read of the empty file")
        store.add(beach)
        assertEquals(listOf(beach), seen.receive(), "a later write still reaches the list")
    }
}

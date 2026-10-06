package com.cowork.bikerecoder.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class DataStoreDayDistancesTest {

    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()

    /** A fresh DataStore on the same file each call, like a new process. */
    private fun store(): DataStoreDayDistances {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return DataStoreDayDistances(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "day.preferences_pb") }),
        )
    }

    @AfterEach
    fun tearDown() = scopes.forEach { it.cancel() }

    private val today = LocalDate.of(2026, 10, 6)

    @Test
    fun savedDistanceIsReadBackForTheSameTripAndDate() = runTest {
        val store = store()
        store.save(tripId = 7, date = today, distanceM = 3_400.0)

        assertEquals(3_400.0, store.distanceOn(7, today))
        assertEquals(0.0, store.distanceOn(8, today), "per trip")
    }

    @Test
    fun anotherDateStartsAtZero() = runTest {
        val store = store()
        store.save(7, today, 3_400.0)

        assertEquals(0.0, store.distanceOn(7, today.plusDays(1)))

        store.save(7, today.plusDays(1), 500.0)
        assertEquals(500.0, store.distanceOn(7, today.plusDays(1)))
        assertEquals(0.0, store.distanceOn(7, today), "only the latest date is kept")
    }

    @Test
    fun survivesANewInstance() = runTest {
        store().save(7, today, 1_250.0)
        scopes.forEach { it.cancel() } // the first DataStore must be closed before another opens the file

        assertEquals(1_250.0, store().distanceOn(7, today))
    }
}

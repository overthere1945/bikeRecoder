package com.cowork.bikerecoder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Per trip, the distance ridden on one local date ("오늘 이동 거리", spec §6.6): guidance resumed on the same
 * day continues the kilometre count from it; a new date starts at 0. Only the latest date is kept per trip.
 */
interface DayDistances {
    /** The distance saved for [tripId] on [date]; 0 when nothing (or another date) is saved. */
    suspend fun distanceOn(tripId: Long, date: LocalDate): Double

    suspend fun save(tripId: Long, date: LocalDate, distanceM: Double)
}

/** Not persisted: the default for controllers built without a store (tests). */
class MemoryDayDistances : DayDistances {
    private val saved = mutableMapOf<Long, Pair<LocalDate, Double>>()

    override suspend fun distanceOn(tripId: Long, date: LocalDate): Double =
        saved[tripId]?.takeIf { it.first == date }?.second ?: 0.0

    override suspend fun save(tripId: Long, date: LocalDate, distanceM: Double) {
        saved[tripId] = date to distanceM
    }
}

/** Keys `day_distance_<tripId>` (metres) and `day_date_<tripId>` (ISO date). */
class DataStoreDayDistances(private val dataStore: DataStore<Preferences>) : DayDistances {

    constructor(context: Context) : this(context.applicationContext.dayDistanceDataStore)

    override suspend fun distanceOn(tripId: Long, date: LocalDate): Double {
        val prefs = dataStore.data.first()
        if (prefs[dateKey(tripId)] != date.toString()) return 0.0
        return prefs[distanceKey(tripId)] ?: 0.0
    }

    override suspend fun save(tripId: Long, date: LocalDate, distanceM: Double) {
        dataStore.edit { prefs ->
            prefs[dateKey(tripId)] = date.toString()
            prefs[distanceKey(tripId)] = distanceM
        }
    }

    private fun distanceKey(tripId: Long) = doublePreferencesKey("day_distance_$tripId")
    private fun dateKey(tripId: Long) = stringPreferencesKey("day_date_$tripId")
}

/** A corrupt file is replaced by an empty one (the day's count restarts) instead of failing every read. */
private val Context.dayDistanceDataStore by preferencesDataStore(
    name = "trip_day_distance",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

package com.cowork.bikerecoder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.RecentPlaceRules
import com.cowork.bikerecoder.search.RecentPlaceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * "최근 검색" on the phone only (spec §7.3): one string key holding a JSON list, newest first. A failed read or a
 * broken list reads as empty, so it never blocks searching.
 */
class DataStoreRecentPlaces(private val dataStore: DataStore<Preferences>) : RecentPlaceStore {

    constructor(context: Context) : this(context.applicationContext.recentPlacesDataStore)

    @Serializable
    private class Entry(val name: String, val address: String, val lat: Double, val lon: Double)

    override val places: Flow<List<Place>> = dataStore.data
        .map { decode(it[KEY]) }
        .catch { emit(emptyList()) }

    override suspend fun add(place: Place) = update { RecentPlaceRules.add(it, place) }

    override suspend fun remove(place: Place) = update { RecentPlaceRules.remove(it, place) }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private suspend fun update(transform: (List<Place>) -> List<Place>) {
        dataStore.edit { prefs -> prefs[KEY] = encode(transform(decode(prefs[KEY]))) }
    }

    private fun encode(places: List<Place>): String =
        json.encodeToString(places.map { Entry(it.name, it.address, it.point.lat, it.point.lon) })

    private fun decode(text: String?): List<Place> {
        if (text == null) return emptyList()
        return try {
            json.decodeFromString<List<Entry>>(text).map { Place(it.name, it.address, GeoPoint(it.lat, it.lon), null) }
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("recent_places")
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A corrupt file is replaced by an empty one (the list starts over) instead of failing every read. */
private val Context.recentPlacesDataStore by preferencesDataStore(
    name = "recent_places",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

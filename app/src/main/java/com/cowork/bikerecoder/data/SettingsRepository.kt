package com.cowork.bikerecoder.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cowork.bikerecoder.core.model.RouteProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val defaultProfile: RouteProfile = RouteProfile.CYCLEWAY_FIRST,
    val voiceEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val wifiOnlyOfflineMaps: Boolean = false,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[PROFILE] = next.defaultProfile.name
            prefs[VOICE] = next.voiceEnabled
            prefs[KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[WIFI_ONLY] = next.wifiOnlyOfflineMaps
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            defaultProfile = this[PROFILE]
                ?.let { name -> RouteProfile.entries.firstOrNull { it.name == name } }
                ?: defaults.defaultProfile,
            voiceEnabled = this[VOICE] ?: defaults.voiceEnabled,
            keepScreenOn = this[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            wifiOnlyOfflineMaps = this[WIFI_ONLY] ?: defaults.wifiOnlyOfflineMaps,
        )
    }

    private companion object {
        val PROFILE = stringPreferencesKey("default_profile")
        val VOICE = booleanPreferencesKey("voice_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_offline_maps")
    }
}

package com.cowork.bikerecoder.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val defaultProfile: RouteProfile = RouteProfile.CYCLEWAY_FIRST,
    val voiceEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val wifiOnlyOfflineMaps: Boolean = false,
    val skippedOnboardingSteps: Set<OnboardingStep> = emptySet(),
    /** The "Korean TTS missing" hint was shown (it is shown once ever, spec §6.9). */
    val ttsNoticeShown: Boolean = false,
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
            prefs[SKIPPED_ONBOARDING] = next.skippedOnboardingSteps.map { it.name }.toSet()
            prefs[TTS_NOTICE_SHOWN] = next.ttsNoticeShown
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
            skippedOnboardingSteps = this[SKIPPED_ONBOARDING]
                ?.mapNotNull { name -> OnboardingStep.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: defaults.skippedOnboardingSteps,
            ttsNoticeShown = this[TTS_NOTICE_SHOWN] ?: defaults.ttsNoticeShown,
        )
    }

    private companion object {
        val PROFILE = stringPreferencesKey("default_profile")
        val VOICE = booleanPreferencesKey("voice_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_offline_maps")
        val SKIPPED_ONBOARDING = stringSetPreferencesKey("skipped_onboarding_steps")
        val TTS_NOTICE_SHOWN = booleanPreferencesKey("tts_notice_shown")
    }
}

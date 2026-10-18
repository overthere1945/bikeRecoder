package com.cowork.bikerecoder.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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
    /** 안내를 시작하면 기록도 함께 시작한다. */
    val autoStartRecordingWithNavigation: Boolean = true,
    /** 멈춰 있으면 기록을 자동으로 일시정지한다(기본값; 기록마다 따로 바꿀 수 있다). */
    val autoPauseEnabled: Boolean = true,
    /** 기록 중에 안내를 새로 시작할 때 기록을 어떻게 할지. */
    val recordingOnNewGuidance: GuidanceRecording = GuidanceRecording.ASK,
)

/** 기록 중에 안내를 새로 시작할 때 기록을 어떻게 할지(스펙 §4.3). */
enum class GuidanceRecording(val label: String) {
    CONTINUE("자동으로 이어서 기록"),
    SPLIT("자동으로 새 구간 나누기"),
    ASK("매번 물어보기"),
}

/** A corrupt settings file is replaced by the defaults instead of crashing every start-up. */
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
            prefs[AUTO_START_RECORDING] = next.autoStartRecordingWithNavigation
            prefs[AUTO_PAUSE] = next.autoPauseEnabled
            prefs[RECORDING_ON_NEW_GUIDANCE] = next.recordingOnNewGuidance.name
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
            autoStartRecordingWithNavigation = this[AUTO_START_RECORDING] ?: defaults.autoStartRecordingWithNavigation,
            autoPauseEnabled = this[AUTO_PAUSE] ?: defaults.autoPauseEnabled,
            recordingOnNewGuidance = this[RECORDING_ON_NEW_GUIDANCE]
                ?.let { name -> GuidanceRecording.entries.firstOrNull { it.name == name } }
                ?: defaults.recordingOnNewGuidance,
        )
    }

    private companion object {
        val PROFILE = stringPreferencesKey("default_profile")
        val VOICE = booleanPreferencesKey("voice_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_offline_maps")
        val SKIPPED_ONBOARDING = stringSetPreferencesKey("skipped_onboarding_steps")
        val TTS_NOTICE_SHOWN = booleanPreferencesKey("tts_notice_shown")
        val AUTO_START_RECORDING = booleanPreferencesKey("auto_start_recording_with_navigation")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause_enabled")
        val RECORDING_ON_NEW_GUIDANCE = stringPreferencesKey("recording_on_new_guidance")
    }
}

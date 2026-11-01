package com.cowork.bikerecoder.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.share.defaultShareHashtags
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep
import com.cowork.bikerecoder.ui.stats.GoalKm
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
    /** 사진 권한 카드를 [나중에]/[닫기]했거나 거절했다(다시 띄우지 않는다; 설정 "사진·심박 연결"에서 다시). */
    val photoPromptDismissed: Boolean = false,
    /** 심박(Health Connect) 권한 카드를 [나중에]했거나 거절했다. */
    val heartRatePromptDismissed: Boolean = false,
    /** 주간 목표(km). 없으면 null. */
    val weeklyGoalKm: Int? = null,
    /** 연간 목표(km). 없으면 null. */
    val yearlyGoalKm: Int? = null,
    /** Default hashtags of the share text (spec §3.5). null = never saved (the default applies), "" = none. */
    val shareHashtags: String? = null,
    /** The walk, run and hike default hashtags (V1.9.0 spec §5.2), the same way: null = never saved, "" = none. */
    val shareHashtagsWalk: String? = null,
    val shareHashtagsRun: String? = null,
    val shareHashtagsHike: String? = null,
    /**
     * The walk, run and hike goals (V1.9.0 spec §4.2; an activity without a goal is absent). The bike goal is
     * [weeklyGoalKm] and [yearlyGoalKm]: the keys that were always there, so an existing goal is the bike goal.
     */
    val otherGoals: Map<ActivityType, GoalKm> = emptyMap(),
    /** The stats tab's activity chip (`stats_activity`); null = [전체] (also when the stored text is unknown). */
    val statsActivity: ActivityType? = null,
    /** The map screen's activity chip: what [● 기록 시작] records (V1.9.0 spec §1.4). Guided recordings never change it. */
    val lastActivity: ActivityType = ActivityType.BIKE,
)

/**
 * The default hashtags of the share text for [type] (spec §5.2): the saved value (an empty one means "none"), or, when it
 * has never been saved, the first value of that activity ([defaultShareHashtags]). The bike's is the `share_hashtags`
 * key that was always there.
 */
fun AppSettings.hashtagsFor(type: ActivityType): String = when (type) {
    ActivityType.BIKE -> shareHashtags
    ActivityType.WALK -> shareHashtagsWalk
    ActivityType.RUN -> shareHashtagsRun
    ActivityType.HIKE -> shareHashtagsHike
} ?: defaultShareHashtags(type)

/** [AppSettings] with [type]'s saved default hashtags set to [value] (null = never saved). */
fun AppSettings.withHashtags(type: ActivityType, value: String?): AppSettings = when (type) {
    ActivityType.BIKE -> copy(shareHashtags = value)
    ActivityType.WALK -> copy(shareHashtagsWalk = value)
    ActivityType.RUN -> copy(shareHashtagsRun = value)
    ActivityType.HIKE -> copy(shareHashtagsHike = value)
}

/** The goals of every activity that has one; the bike's are [AppSettings.weeklyGoalKm] and [AppSettings.yearlyGoalKm]. */
val AppSettings.goals: Map<ActivityType, GoalKm>
    get() = buildMap {
        if (weeklyGoalKm != null || yearlyGoalKm != null) put(ActivityType.BIKE, GoalKm(weeklyGoalKm, yearlyGoalKm))
        putAll(otherGoals)
    }

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
            prefs[PHOTO_PROMPT_DISMISSED] = next.photoPromptDismissed
            prefs[HEART_RATE_PROMPT_DISMISSED] = next.heartRatePromptDismissed
            prefs.setOrRemove(WEEKLY_GOAL_KM, next.weeklyGoalKm)
            prefs.setOrRemove(YEARLY_GOAL_KM, next.yearlyGoalKm)
            for (type in EXTRA_GOAL_TYPES) {
                val goal = next.otherGoals[type]
                prefs.setOrRemove(weeklyKey(type), goal?.weekly)
                prefs.setOrRemove(yearlyKey(type), goal?.yearly)
            }
            val chip = next.statsActivity
            if (chip == null) prefs.remove(STATS_ACTIVITY) else prefs[STATS_ACTIVITY] = chip.stored
            for (type in ActivityType.entries) {
                val hashtags = next.hashtagsSaved(type)
                if (hashtags == null) prefs.remove(hashtagsKey(type)) else prefs[hashtagsKey(type)] = hashtags
            }
            prefs[LAST_ACTIVITY] = next.lastActivity.stored
        }
    }

    /** Saves the activity chip's choice. The other settings are left alone. */
    suspend fun setLastActivity(type: ActivityType) {
        dataStore.edit { prefs -> prefs[LAST_ACTIVITY] = type.stored }
    }

    /** Saves the bike's default hashtags as given ("" = none). The other settings are left alone. */
    suspend fun setShareHashtags(value: String) = setShareHashtags(ActivityType.BIKE, value)

    /** Saves [type]'s default hashtags as given ("" = none). The other settings are left alone. */
    suspend fun setShareHashtags(type: ActivityType, value: String) {
        dataStore.edit { prefs -> prefs[hashtagsKey(type)] = value }
    }

    private fun AppSettings.hashtagsSaved(type: ActivityType): String? = when (type) {
        ActivityType.BIKE -> shareHashtags
        ActivityType.WALK -> shareHashtagsWalk
        ActivityType.RUN -> shareHashtagsRun
        ActivityType.HIKE -> shareHashtagsHike
    }

    private fun hashtagsKey(type: ActivityType): Preferences.Key<String> = when (type) {
        ActivityType.BIKE -> SHARE_HASHTAGS
        ActivityType.WALK -> SHARE_HASHTAGS_WALK
        ActivityType.RUN -> SHARE_HASHTAGS_RUN
        ActivityType.HIKE -> SHARE_HASHTAGS_HIKE
    }

    /** 자전거 목표를 저장한다([setGoals] with [ActivityType.BIKE]). null이면 그 키를 지운다. 다른 설정은 건드리지 않는다. */
    suspend fun setGoals(weeklyKm: Int?, yearlyKm: Int?) = setGoals(ActivityType.BIKE, weeklyKm, yearlyKm)

    /**
     * Saves [activity]'s goals; null removes that key. The bike's go to the keys that were always there
     * (`weekly_goal_km`, `yearly_goal_km`), the others to `weekly_goal_km_walk` and so on. Other settings stay.
     */
    suspend fun setGoals(activity: ActivityType, weeklyKm: Int?, yearlyKm: Int?) {
        dataStore.edit { prefs ->
            prefs.setOrRemove(weeklyKey(activity), weeklyKm)
            prefs.setOrRemove(yearlyKey(activity), yearlyKm)
        }
    }

    /** Saves the stats tab's activity chip; null = [전체] (the key is removed). Other settings stay. */
    suspend fun setStatsActivity(type: ActivityType?) {
        dataStore.edit { prefs -> if (type == null) prefs.remove(STATS_ACTIVITY) else prefs[STATS_ACTIVITY] = type.stored }
    }

    private fun weeklyKey(type: ActivityType): Preferences.Key<Int> =
        if (type == ActivityType.BIKE) WEEKLY_GOAL_KM else intPreferencesKey("weekly_goal_km_${type.name.lowercase()}")

    private fun yearlyKey(type: ActivityType): Preferences.Key<Int> =
        if (type == ActivityType.BIKE) YEARLY_GOAL_KM else intPreferencesKey("yearly_goal_km_${type.name.lowercase()}")

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<Int>, value: Int?) {
        if (value == null) remove(key) else this[key] = value
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
            photoPromptDismissed = this[PHOTO_PROMPT_DISMISSED] ?: defaults.photoPromptDismissed,
            heartRatePromptDismissed = this[HEART_RATE_PROMPT_DISMISSED] ?: defaults.heartRatePromptDismissed,
            weeklyGoalKm = this[WEEKLY_GOAL_KM],
            yearlyGoalKm = this[YEARLY_GOAL_KM],
            otherGoals = EXTRA_GOAL_TYPES.mapNotNull { type ->
                val weekly = this[weeklyKey(type)]
                val yearly = this[yearlyKey(type)]
                if (weekly == null && yearly == null) null else type to GoalKm(weekly, yearly)
            }.toMap(),
            statsActivity = this[STATS_ACTIVITY]?.let { stored -> ActivityType.entries.firstOrNull { it.stored == stored } },
            shareHashtags = this[SHARE_HASHTAGS],
            shareHashtagsWalk = this[SHARE_HASHTAGS_WALK],
            shareHashtagsRun = this[SHARE_HASHTAGS_RUN],
            shareHashtagsHike = this[SHARE_HASHTAGS_HIKE],
            lastActivity = this[LAST_ACTIVITY]?.let(ActivityType::fromStored) ?: defaults.lastActivity,
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
        val PHOTO_PROMPT_DISMISSED = booleanPreferencesKey("photo_prompt_dismissed")
        val HEART_RATE_PROMPT_DISMISSED = booleanPreferencesKey("heart_rate_prompt_dismissed")
        val WEEKLY_GOAL_KM = intPreferencesKey("weekly_goal_km")
        val YEARLY_GOAL_KM = intPreferencesKey("yearly_goal_km")
        val SHARE_HASHTAGS = stringPreferencesKey("share_hashtags")
        val SHARE_HASHTAGS_WALK = stringPreferencesKey("share_hashtags_walk")
        val SHARE_HASHTAGS_RUN = stringPreferencesKey("share_hashtags_run")
        val SHARE_HASHTAGS_HIKE = stringPreferencesKey("share_hashtags_hike")
        val LAST_ACTIVITY = stringPreferencesKey("last_activity")
        val STATS_ACTIVITY = stringPreferencesKey("stats_activity")
        val EXTRA_GOAL_TYPES = listOf(ActivityType.WALK, ActivityType.RUN, ActivityType.HIKE)
    }
}

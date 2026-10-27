package com.cowork.bikerecoder.video

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/** 끝나지 못한 작업(스펙 §3.3, §5.1): 앱이 강제 종료되면 다음 실행 때 같은 옵션으로 다시 만들기를 제안한다. */
data class PendingJob(val tripId: Long, val kind: MediaKind, val optionsJson: String, val startedAt: Long)

/**
 * 영상 작업이 휴대폰에 남기는 것(DataStore): 마지막으로 고른 옵션(스펙 §1.2, 음악 제외), 이 휴대폰에서 마지막으로 만든 영상의
 * 장당 시간(§3.6), 진행 중 작업(§5.1). 깨진 값은 없는 것으로 읽고, 읽기가 실패해도 영상 만들기를 막지 않는다.
 */
class VideoJobStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.videoJobDataStore)

    @Serializable
    private class PendingEntry(val tripId: Long, val kind: String, val optionsJson: String, val startedAt: Long)

    val lastOptions: Flow<VideoOptions> = dataStore.data
        .map { prefs -> prefs[LAST_OPTIONS]?.let(VideoOptionsJson::decode) ?: VideoOptions() }
        .catch { if (it is IOException) emit(VideoOptions()) else throw it }

    /** [VideoOptions.musicUri]는 저장하지 않는다. */
    suspend fun saveOptions(o: VideoOptions) {
        dataStore.edit { it[LAST_OPTIONS] = VideoOptionsJson.encode(o) }
    }

    /** 마지막으로 만든 영상의 실제 장당 시간(초), 아직 없으면 null. */
    suspend fun secondsPerFrame(): Double? = read()?.get(SECONDS_PER_FRAME)

    /** 0보다 큰 유한한 값만 기록한다. */
    suspend fun recordSpeed(s: Double) {
        if (!s.isFinite() || s <= 0.0) return
        dataStore.edit { it[SECONDS_PER_FRAME] = s }
    }

    suspend fun setPending(p: PendingJob?) {
        dataStore.edit { prefs ->
            if (p == null) {
                prefs.remove(PENDING_JOB)
            } else {
                prefs[PENDING_JOB] = json.encodeToString(PendingEntry(p.tripId, p.kind.name, p.optionsJson, p.startedAt))
            }
        }
    }

    suspend fun pending(): PendingJob? {
        val text = read()?.get(PENDING_JOB) ?: return null
        return try {
            val e = json.decodeFromString<PendingEntry>(text)
            val kind = MediaKind.entries.firstOrNull { it.name == e.kind } ?: return null
            PendingJob(e.tripId, kind, e.optionsJson, e.startedAt)
        } catch (_: SerializationException) {
            null
        }
    }

    private suspend fun read(): Preferences? = try {
        dataStore.data.first()
    } catch (_: IOException) {
        null
    }

    private companion object {
        val LAST_OPTIONS = stringPreferencesKey("last_options")
        val SECONDS_PER_FRAME = doublePreferencesKey("seconds_per_frame")
        val PENDING_JOB = stringPreferencesKey("pending_job")
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * [VideoOptions] ↔ JSON(kotlinx.serialization). [VideoOptions.musicUri]는 쓰지 않고, 읽으면 늘 null이다. 모르는 이름이나
 * 빠진 값은 그 항목만 기본값으로, 깨진 JSON은 기본 옵션으로 읽는다. [TripMedia.optionsJson]·[PendingJob.optionsJson]도 이 형식.
 */
object VideoOptionsJson {

    @Serializable
    private class Entry(
        val aspect: String = DEFAULTS.aspect.name,
        val length: String = DEFAULTS.length.name,
        val photos: Boolean = DEFAULTS.photos,
        val hud: Boolean = DEFAULTS.hud,
        val coloring: String = DEFAULTS.coloring.name,
    )

    private val DEFAULTS = VideoOptions()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(o: VideoOptions): String =
        json.encodeToString(Entry(o.aspect.name, o.length.name, o.photos, o.hud, o.coloring.name))

    fun decode(text: String): VideoOptions {
        val e = try {
            json.decodeFromString<Entry>(text)
        } catch (_: SerializationException) {
            return DEFAULTS
        }
        return VideoOptions(
            aspect = Aspect.entries.firstOrNull { it.name == e.aspect } ?: DEFAULTS.aspect,
            length = VideoLength.entries.firstOrNull { it.name == e.length } ?: DEFAULTS.length,
            photos = e.photos,
            hud = e.hud,
            coloring = RouteColoring.entries.firstOrNull { it.name == e.coloring } ?: DEFAULTS.coloring,
            musicUri = null,
        )
    }
}

/** A corrupt file is replaced by an empty one (defaults, no pending job) instead of failing every read. */
private val Context.videoJobDataStore by preferencesDataStore(
    name = "video_job",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

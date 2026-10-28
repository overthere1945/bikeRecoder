package com.cowork.bikerecoder.video

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VideoJobStoreTest {

    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()

    private fun dataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it },
        produceFile = { File(dir, "video_job.preferences_pb") },
    )

    /** The first DataStore must be closed before another opens the same file. */
    private fun reopen(): VideoJobStore {
        scopes.forEach { it.cancel() }
        return VideoJobStore(dataStore())
    }

    @AfterEach
    fun tearDown() = scopes.forEach { it.cancel() }

    private val chosen = VideoOptions(
        aspect = Aspect.LANDSCAPE,
        length = VideoLength.LONG,
        photos = false,
        hud = false,
        coloring = RouteColoring.HEART_RATE,
        musicUri = "content://com.android.providers.media.documents/document/audio%3A12",
    )

    @Test
    fun `nothing saved reads as the default options`() = runTest {
        assertEquals(VideoOptions(), VideoJobStore(dataStore()).lastOptions.first())
    }

    @Test
    fun `saved options survive a new instance without the music`() = runTest {
        VideoJobStore(dataStore()).saveOptions(chosen)

        assertEquals(chosen.copy(musicUri = null), reopen().lastOptions.first())
    }

    @Test
    fun `the music uri is never written to the file`() = runTest {
        VideoJobStore(dataStore()).saveOptions(chosen)
        scopes.forEach { it.cancel() }

        val bytes = File(dir, "video_job.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("audio%3A12"), bytes)
        assertFalse(bytes.contains("music", ignoreCase = true), bytes)
    }

    @Test
    fun `options json round trip drops the music and broken json reads as defaults`() {
        val json = VideoOptionsJson.encode(chosen)

        assertFalse(json.contains("music", ignoreCase = true), json)
        assertEquals(chosen.copy(musicUri = null), VideoOptionsJson.decode(json))
        assertEquals(VideoOptions(), VideoOptionsJson.decode("{not json"))
    }

    @Test
    fun `unknown enum names fall back to the defaults field by field`() {
        val json = """{"aspect":"SQUARE","length":"LONG","photos":false,"hud":true,"coloring":"RAINBOW"}"""

        assertEquals(VideoOptions(length = VideoLength.LONG, photos = false), VideoOptionsJson.decode(json))
    }

    @Test
    fun `broken stored options read as the defaults`() = runTest {
        val ds = dataStore()
        ds.edit { it[stringPreferencesKey("last_options")] = "{not json" }

        assertEquals(VideoOptions(), VideoJobStore(ds).lastOptions.first())
    }

    @Test
    fun `seconds per frame is null until recorded and keeps the last value`() = runTest {
        val store = VideoJobStore(dataStore())
        assertNull(store.secondsPerFrame())

        store.recordSpeed(0.31)
        store.recordSpeed(0.22)

        assertEquals(0.22, reopen().secondsPerFrame())
    }

    @Test
    fun `a speed that is not a positive finite number is ignored`() = runTest {
        val store = VideoJobStore(dataStore())
        store.recordSpeed(0.4)

        store.recordSpeed(0.0)
        store.recordSpeed(-1.0)
        store.recordSpeed(Double.NaN)
        store.recordSpeed(Double.POSITIVE_INFINITY)

        assertEquals(0.4, store.secondsPerFrame())
    }

    /** Spec §1.4: the satellite bytes per frame of the last satellite video, kept like the speed. */
    @Test
    fun bytesPerFrameRoundTrip() = runTest {
        val store = VideoJobStore(dataStore())
        assertNull(store.bytesPerFrame())

        store.recordBytesPerFrame(80_000.0)
        store.recordBytesPerFrame(50_000.0)
        store.recordBytesPerFrame(0.0)
        store.recordBytesPerFrame(-1.0)
        store.recordBytesPerFrame(Double.NaN)
        store.recordBytesPerFrame(Double.POSITIVE_INFINITY)
        // Ruling J3: a video made again from cached tiles measures a few hundred bytes a frame; not an estimate.
        store.recordBytesPerFrame(500.0)
        store.recordBytesPerFrame(VideoJobStore.MIN_BYTES_PER_FRAME - 1)

        val reopened = reopen()
        assertEquals(50_000.0, reopened.bytesPerFrame())
        reopened.recordBytesPerFrame(VideoJobStore.MIN_BYTES_PER_FRAME)
        assertEquals(VideoJobStore.MIN_BYTES_PER_FRAME, reopened.bytesPerFrame())
    }

    @Test
    fun `pending job round trips and clears`() = runTest {
        val store = VideoJobStore(dataStore())
        assertNull(store.pending())
        val job = PendingJob(tripId = 12, kind = MediaKind.VIDEO, optionsJson = VideoOptionsJson.encode(chosen), startedAt = 1_700_000_000_000L)

        store.setPending(job)
        val reopened = reopen()
        assertEquals(job, reopened.pending())

        reopened.setPending(null)
        assertNull(reopened.pending())
        assertNull(reopen().pending())
    }

    @Test
    fun `an image pending job keeps its kind`() = runTest {
        val store = VideoJobStore(dataStore())
        val job = PendingJob(3, MediaKind.IMAGE, "{}", 5_000L)

        store.setPending(job)

        assertEquals(job, store.pending())
    }

    @Test
    fun `the half-written gallery item of a pending job survives reopening`() = runTest {
        val store = VideoJobStore(dataStore())
        val job = PendingJob(4, MediaKind.VIDEO, "{}", 6_000L, outputUri = "content://media/external_primary/video/media/77")

        store.setPending(job)

        assertEquals(job, reopen().pending())
    }

    @Test
    fun `a pending job saved before the gallery item was recorded reads without one`() = runTest {
        val ds = dataStore()
        ds.edit {
            it[stringPreferencesKey("pending_job")] = "{\"tripId\":8,\"kind\":\"VIDEO\",\"optionsJson\":\"{}\",\"startedAt\":9}"
        }

        assertEquals(PendingJob(8, MediaKind.VIDEO, "{}", 9L, outputUri = null), VideoJobStore(ds).pending())
    }

    @Test
    fun `a broken pending job reads as none`() = runTest {
        val ds = dataStore()
        ds.edit { it[stringPreferencesKey("pending_job")] = "{\"tripId\":1,\"kind\":\"GIF\"}" }

        assertNull(VideoJobStore(ds).pending())
    }
}

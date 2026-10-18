package com.cowork.bikerecoder.ui.settings

import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.offline.OfflineMapStorage
import com.cowork.bikerecoder.offline.SegmentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer
    private lateinit var repo: SegmentRepository
    private lateinit var viewModel: SettingsViewModel
    private val settings = MutableStateFlow(AppSettings())
    private val storage = FakeStorage()

    private class FakeStorage : OfflineMapStorage {
        var bytes = 3_000_000L
        var deleteCalls = 0
        var failDelete = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun totalBytes(): Long = bytes
        override suspend fun deleteAll() {
            deleteCalls++
            gate?.await()
            if (failDelete) throw IllegalStateException("boom")
            bytes = 0
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        repo = SegmentRepository(dir, OkHttpClient(), server.url("/segments4/"))
        viewModel = SettingsViewModel(
            settings = settings,
            updateSettings = { transform -> settings.value = transform(settings.value) },
            segments = repo,
            mapStorage = storage,
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun ok(bytes: Int = 2048) =
        MockResponse.Builder().body(Buffer().write(ByteArray(bytes) { it.toByte() })).build()

    private fun awaitState(predicate: (SettingsUiState) -> Boolean) = runBlocking {
        withTimeout(10_000) { viewModel.state.first(predicate) }
    }

    @Test
    fun `toggles and profile are written to the settings`() {
        viewModel.setVoiceEnabled(false)
        viewModel.setKeepScreenOn(false)
        viewModel.setWifiOnlyOfflineMaps(true)
        viewModel.setProfile(RouteProfile.SHORTEST)

        assertEquals(AppSettings(RouteProfile.SHORTEST, voiceEnabled = false, keepScreenOn = false, wifiOnlyOfflineMaps = true), settings.value)
        assertEquals(settings.value, viewModel.state.value.settings)

        viewModel.setVoiceEnabled(true)
        assertTrue(settings.value.voiceEnabled)
    }

    @Test
    fun `lists the four segments with required first and none installed`() {
        val segments = viewModel.state.value.segments
        assertEquals(listOf("E125_N35", "E125_N30", "E130_N35", "E120_N35"), segments.map { it.name })
        assertEquals(listOf(true, true, false, false), segments.map { it.required })
        assertTrue(segments.none { it.installed })
    }

    @Test
    fun `download installs the file and shows its size`() {
        server.enqueue(ok(4096))

        viewModel.download("E130_N35")

        val state = awaitState { s -> s.segments.first { it.name == "E130_N35" }.installed }
        assertEquals(4096L, state.segments.first { it.name == "E130_N35" }.sizeBytes)
        assertEquals(SegmentStatus.Idle, state.statusOf("E130_N35"))
    }

    @Test
    fun `a failed download is reported and nothing is installed`() {
        server.enqueue(MockResponse.Builder().code(500).build())

        viewModel.download("E130_N35")

        val state = awaitState { it.statusOf("E130_N35") == SegmentStatus.Failed }
        assertFalse(state.segments.first { it.name == "E130_N35" }.installed)
    }

    @Test
    fun `a second download of the same file while one runs is ignored`() {
        // The first response is held back so the first download is still running when the second call arrives.
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(ByteArray(2048))).bodyDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )
        server.enqueue(ok())

        viewModel.download("E130_N35")
        awaitState { it.statusOf("E130_N35") is SegmentStatus.Downloading }
        viewModel.download("E130_N35")
        viewModel.delete("E130_N35") // also ignored while the download runs

        awaitState { s -> s.segments.first { it.name == "E130_N35" }.installed }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `update check reports a newer remote file and a download then replaces it`() {
        File(dir, "E125_N35.rd5").apply { writeBytes(ByteArray(10)); setLastModified(1_000_000_000_000L) }
        server.enqueue(
            MockResponse.Builder().addHeader("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.ofInstant(Date(1_700_000_000_000L).toInstant(), java.time.ZoneOffset.UTC))).build(),
        )
        server.enqueue(ok(100))

        viewModel.checkUpdate("E125_N35")
        awaitState { it.statusOf("E125_N35") == SegmentStatus.UpdateAvailable }

        viewModel.download("E125_N35")
        val state = awaitState { s -> s.segments.first { it.name == "E125_N35" }.sizeBytes == 100L }
        assertEquals(SegmentStatus.Idle, state.statusOf("E125_N35"))
    }

    @Test
    fun `update check that cannot reach the server is a failure, not up to date`() {
        File(dir, "E125_N35.rd5").writeBytes(ByteArray(10))
        server.enqueue(MockResponse.Builder().code(500).build())

        viewModel.checkUpdate("E125_N35")

        awaitState { it.statusOf("E125_N35") == SegmentStatus.CheckFailed }
    }

    @Test
    fun `update check with the same remote date is up to date`() {
        File(dir, "E125_N35.rd5").apply { writeBytes(ByteArray(10)); setLastModified(1_700_000_000_000L) }
        server.enqueue(
            MockResponse.Builder().addHeader("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.ofInstant(Date(1_700_000_000_000L).toInstant(), java.time.ZoneOffset.UTC))).build(),
        )

        viewModel.checkUpdate("E125_N35")

        awaitState { it.statusOf("E125_N35") == SegmentStatus.UpToDate }
    }

    @Test
    fun `delete removes the file`() {
        File(dir, "E125_N30.rd5").writeBytes(ByteArray(10))
        viewModel.delete("E125_N30")

        assertFalse(File(dir, "E125_N30.rd5").exists())
        assertFalse(viewModel.state.value.segments.first { it.name == "E125_N30" }.installed)
    }

    @Test
    fun `map size is read at start and delete all empties it`() {
        assertEquals(MapStorageState.Size(3_000_000L), viewModel.state.value.mapStorage)

        viewModel.deleteAllMaps()

        assertEquals(1, storage.deleteCalls)
        assertEquals(MapStorageState.Size(0L), viewModel.state.value.mapStorage)
        assertFalse(viewModel.state.value.deletingMaps)
        assertFalse(viewModel.state.value.deleteMapsFailed)
    }

    @Test
    fun `delete all failure is reported and a second tap during deletion is ignored`() {
        storage.gate = CompletableDeferred()
        storage.failDelete = true

        viewModel.deleteAllMaps()
        viewModel.deleteAllMaps()
        assertTrue(viewModel.state.value.deletingMaps)
        assertEquals(1, storage.deleteCalls)

        storage.gate!!.complete(Unit)
        val state = awaitState { !it.deletingMaps }
        assertTrue(state.deleteMapsFailed)
        assertEquals(MapStorageState.Size(3_000_000L), state.mapStorage)
    }
}

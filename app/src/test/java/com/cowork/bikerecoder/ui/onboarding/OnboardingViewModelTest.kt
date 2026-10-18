package com.cowork.bikerecoder.ui.onboarding

import com.cowork.bikerecoder.offline.SegmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer
    private lateinit var repo: SegmentRepository
    private lateinit var viewModel: OnboardingViewModel
    private val skipStore = InMemorySkipStore()

    private class InMemorySkipStore : OnboardingSkipStore {
        val state = MutableStateFlow<Set<OnboardingStep>>(emptySet())
        override val skipped: Flow<Set<OnboardingStep>> = state
        override suspend fun update(transform: (Set<OnboardingStep>) -> Set<OnboardingStep>) {
            state.value = transform(state.value)
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        repo = SegmentRepository(dir, OkHttpClient(), server.url("/segments4/"))
        viewModel = OnboardingViewModel(
            readState = { PermissionState(true, true, true, true, repo.hasRequired()) },
            segments = repo,
            skipStore = skipStore,
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun ok() = MockResponse.Builder().body(Buffer().write(ByteArray(2048) { it.toByte() })).build()

    private fun awaitState(predicate: (OnboardingUiState) -> Boolean) = runBlocking {
        withTimeout(10_000) { viewModel.state.first(predicate) }
    }

    @Test
    fun `required segments download one after another and a repeated tap does not start a duplicate`() {
        server.enqueue(ok())
        server.enqueue(ok())
        assertEquals(OnboardingStep.SEGMENTS, viewModel.state.value.step)

        viewModel.downloadSegments()
        viewModel.downloadSegments() // ignored while the first run is active

        val done = awaitState { it.permissions.segmentsReady }
        assertEquals(OnboardingStep.DONE, done.step)
        assertEquals(SegmentDownload.Idle, awaitState { it.download == SegmentDownload.Idle }.download)
        assertEquals(2, server.requestCount)
        val paths = List(2) { server.takeRequest().url.encodedPath }
        assertEquals(SegmentRepository.REQUIRED.map { "/segments4/$it.rd5" }, paths)
    }

    @Test
    fun `failure is reported and retry downloads the missing files`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        viewModel.downloadSegments()
        awaitState { it.download == SegmentDownload.Failed }
        assertFalse(viewModel.state.value.permissions.segmentsReady)

        server.enqueue(ok())
        server.enqueue(ok())
        viewModel.downloadSegments()
        awaitState { it.permissions.segmentsReady }
        assertTrue(repo.hasRequired())
    }

    @Test
    fun `skipping the segment step finishes onboarding without route data`() {
        viewModel.skipStep(OnboardingStep.SEGMENTS)
        assertEquals(OnboardingStep.DONE, viewModel.state.value.step)
        assertFalse(viewModel.state.value.permissions.segmentsReady)
    }

    @Test
    fun `skip persists across a new view model over the same store`() {
        viewModel.skipStep(OnboardingStep.SEGMENTS)
        val second = OnboardingViewModel(
            readState = { PermissionState(true, true, true, true, repo.hasRequired()) },
            segments = repo,
            skipStore = skipStore,
        )
        assertEquals(OnboardingStep.DONE, second.state.value.step)
        assertEquals(setOf(OnboardingStep.SEGMENTS), skipStore.state.value)
    }

    @Test
    fun `fine location cannot be skipped`() {
        val vm = OnboardingViewModel(
            readState = { PermissionState(false, false, false, false, false) },
            segments = repo,
            skipStore = skipStore,
        )
        vm.skipStep(OnboardingStep.FINE_LOCATION)
        assertEquals(OnboardingStep.FINE_LOCATION, vm.state.value.step)
        assertTrue(skipStore.state.value.isEmpty())
    }

    @Test
    fun `successful download removes the segments skip`() {
        viewModel.skipStep(OnboardingStep.SEGMENTS)
        server.enqueue(ok())
        server.enqueue(ok())
        viewModel.downloadSegments()
        awaitState { it.permissions.segmentsReady }
        runBlocking { withTimeout(10_000) { skipStore.skipped.first { OnboardingStep.SEGMENTS !in it } } }
        assertTrue(skipStore.state.value.isEmpty())
    }

    @Test
    fun `progress is formatted in megabytes`() {
        assertEquals("1.0 MB / 2.0 MB", formatProgress(1_048_576, 2_097_152))
        assertEquals("0.5 MB", formatProgress(524_288, -1))
    }
}

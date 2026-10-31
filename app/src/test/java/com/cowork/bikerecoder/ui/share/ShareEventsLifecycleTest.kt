package com.cowork.bikerecoder.ui.share

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.video.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The sheet's events are taken only while the screen is started: a [ShareEvent.Send] of an image made while the app was
 * away waits for it to come back (Android would not open the other app from the background).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareEventsLifecycleTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val image = ShareCandidate("content://media/external/images/media/9", MediaKind.IMAGE, Aspect.PORTRAIT, 1L, null)

    @Test
    fun aSendWhileStoppedIsHandledOnceStarted() = runTest(dispatcher) {
        // The view model's channel, as [ShareViewModel.events] builds it.
        val events = Channel<ShareEvent>(Channel.BUFFERED)
        val handled = mutableListOf<ShareEvent>()
        owner.registry.currentState = Lifecycle.State.RESUMED
        backgroundScope.launch { handleWhileStarted(owner.lifecycle, events.receiveAsFlow()) { handled += it } }
        runCurrent()

        // Away: the image is made and its send comes.
        owner.registry.currentState = Lifecycle.State.CREATED
        val send = ShareEvent.Send(ShareTarget.FACEBOOK, image, "글", made = true)
        events.send(send)
        runCurrent()
        assertEquals(emptyList<ShareEvent>(), handled)

        // Back: handled once.
        owner.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(listOf<ShareEvent>(send), handled)

        // On screen, events go through at once.
        events.send(ShareEvent.Copy("글"))
        runCurrent()
        assertEquals(listOf(send, ShareEvent.Copy("글")), handled)
    }
}

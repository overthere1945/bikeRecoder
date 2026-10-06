package com.cowork.bikerecoder.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtsEngineSelectorTest {
    private enum class Init { SYNC_OK, SYNC_ERROR, ASYNC_OK, ASYNC_ERROR }

    private class FakeHandle(val engine: String, private val korean: Boolean) : TtsEngineHandle {
        var shutdownCount = 0
        override fun configureKorean() = korean
        override fun speak(text: String, utteranceId: String) = true
        override fun stop() = Unit
        override fun shutdown() { shutdownCount++ }
    }

    private class Harness(
        behaviour: Map<String, Init>,
        korean: Map<String, Boolean> = emptyMap(),
    ) {
        val handles = mutableMapOf<String, FakeHandle>()
        val pendingCallbacks = mutableListOf<() -> Unit>()
        val results = mutableListOf<TtsEngineHandle?>()
        private val selector = TtsEngineSelector(
            engines = listOf("samsung", "google"),
            factory = { name, onInit ->
                when (behaviour.getValue(name)) {
                    Init.SYNC_OK -> onInit(true)
                    Init.SYNC_ERROR -> onInit(false)
                    Init.ASYNC_OK -> pendingCallbacks += { onInit(true) }
                    Init.ASYNC_ERROR -> pendingCallbacks += { onInit(false) }
                }
                FakeHandle(name, korean[name] ?: true).also { handles[name] = it }
            },
            onSelected = { results += it },
        )

        fun start() = selector.start()
        fun runPending() {
            while (pendingCallbacks.isNotEmpty()) pendingCallbacks.removeAt(0)()
        }
    }

    @Test
    fun `samsung wins when it initialises with korean`() {
        val h = Harness(mapOf("samsung" to Init.ASYNC_OK, "google" to Init.ASYNC_OK))
        h.start()
        h.runPending()
        assertEquals(1, h.results.size)
        assertSame(h.handles["samsung"], h.results.single())
        assertFalse(h.handles.containsKey("google"))
        assertEquals(0, h.handles["samsung"]!!.shutdownCount)
    }

    @Test
    fun `synchronous init error falls back to google and keeps the google instance`() {
        val h = Harness(mapOf("samsung" to Init.SYNC_ERROR, "google" to Init.ASYNC_OK))
        h.start()
        h.runPending()
        assertEquals(1, h.results.size)
        assertSame(h.handles["google"], h.results.single())
        assertEquals(1, h.handles["samsung"]!!.shutdownCount)
        assertEquals(0, h.handles["google"]!!.shutdownCount)
    }

    @Test
    fun `synchronous error on samsung and synchronous success on google`() {
        val h = Harness(mapOf("samsung" to Init.SYNC_ERROR, "google" to Init.SYNC_OK))
        h.start()
        assertSame(h.handles["google"], h.results.single())
        assertEquals(1, h.handles["samsung"]!!.shutdownCount)
    }

    @Test
    fun `asynchronous init error falls back to google`() {
        val h = Harness(mapOf("samsung" to Init.ASYNC_ERROR, "google" to Init.ASYNC_OK))
        h.start()
        assertTrue(h.results.isEmpty())
        h.runPending()
        assertSame(h.handles["google"], h.results.single())
        assertEquals(1, h.handles["samsung"]!!.shutdownCount)
    }

    @Test
    fun `korean missing on samsung falls back to google`() {
        val h = Harness(
            mapOf("samsung" to Init.ASYNC_OK, "google" to Init.ASYNC_OK),
            korean = mapOf("samsung" to false),
        )
        h.start()
        h.runPending()
        assertSame(h.handles["google"], h.results.single())
        assertEquals(1, h.handles["samsung"]!!.shutdownCount)
    }

    @Test
    fun `both engines failing reports null and shuts both down`() {
        val h = Harness(
            mapOf("samsung" to Init.SYNC_ERROR, "google" to Init.ASYNC_OK),
            korean = mapOf("google" to false),
        )
        h.start()
        h.runPending()
        assertEquals(1, h.results.size)
        assertNull(h.results.single())
        assertEquals(1, h.handles["samsung"]!!.shutdownCount)
        assertEquals(1, h.handles["google"]!!.shutdownCount)
    }

    @Test
    fun `a duplicate init callback does not select twice`() {
        val h = Harness(mapOf("samsung" to Init.ASYNC_OK, "google" to Init.ASYNC_OK))
        h.start()
        h.runPending()
        h.pendingCallbacks += { }
        h.runPending()
        assertEquals(1, h.results.size)
    }
}

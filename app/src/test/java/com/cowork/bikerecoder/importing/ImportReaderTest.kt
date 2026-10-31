package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.core.importing.ImportItem
import com.cowork.bikerecoder.core.importing.ImportLimits
import com.cowork.bikerecoder.core.importing.ImportProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ImportReaderTest {

    @Test
    fun holdCapsAt128() {
        val taken = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val grants = UriGrants(
            take = { if (it.endsWith("/shared")) throw SecurityException("not persistable") else taken += it },
            drop = { dropped += it },
        )
        val uris = listOf("content://x/shared") + (0 until 200).map { "content://x/$it" }

        grants.hold(uris)

        assertEquals(128, UriGrants.MAX_PERSISTED_GRANTS)
        assertEquals((0 until 128).map { "content://x/$it" }, taken)
        grants.release(uris)
        assertEquals(taken, dropped, "only what was taken is released")
        grants.release(uris)
        assertEquals(128, dropped.size, "released once")
    }

    /** A reader of one file whose stream records that it was closed; [failAfter] bytes then an IOException. */
    private class ClosingReader(private val bytes: ByteArray, private val failAfter: Int? = null) : ImportReader {
        var closed = 0
        override fun displayName(uri: String) = "a.zip"
        override fun open(uri: String): InputStream = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            private var read = 0
            override fun read(): Int {
                if (failAfter != null && read >= failAfter) throw java.io.IOException("gone")
                read++
                return inner.read()
            }
            override fun close() {
                closed++
            }
        }
        override fun hold(uris: List<String>) = Unit
        override fun release(uris: List<String>) = Unit
    }

    @Test
    fun walkStopsAndClosesTheStream() {
        val reader = ClosingReader(zip("1.gpx" to gpx(T0), "2.gpx" to gpx(T0 + 1), "3.gpx" to gpx(T0 + 2)))
        val seen = mutableListOf<Int>()

        runBlocking {
            reader.walk("content://x/a.zip", "a.zip", ImportLimits(), Dispatchers.IO) {
                seen += it.index
                false
            }
        }

        assertEquals(listOf(0), seen)
        assertEquals(1, reader.closed)
    }

    @Test
    fun walkOpenFailureIsOneUnreadableItem() {
        val reader = FakeImportReader()
        val items = mutableListOf<ImportItem>()

        runBlocking {
            reader.walk("content://x/gone.gpx", "gone.gpx", ImportLimits(), Dispatchers.IO) {
                items += it
                true
            }
        }

        assertEquals(listOf<ImportItem>(ImportItem.Unreadable("gone.gpx", 0, ImportProblem.UNREADABLE)), items)
    }

    @Test
    fun walkVisitorFailurePropagatesAndCloses() {
        val reader = ClosingReader(zip("1.gpx" to gpx(T0)))
        var caught: Throwable? = null

        runBlocking {
            try {
                reader.walk("content://x/a.zip", "a.zip", ImportLimits(), Dispatchers.IO) { error("visitor bug") }
            } catch (e: IllegalStateException) {
                caught = e
            }
        }

        assertEquals("visitor bug", caught?.message)
        assertTrue(reader.closed == 1)
    }
}

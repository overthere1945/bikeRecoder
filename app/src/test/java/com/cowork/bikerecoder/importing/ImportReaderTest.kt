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

    /** A kept descriptor: [closed] once closed. */
    private class Kept(val uri: String) : java.io.Closeable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    @Test
    fun holdKeepsDescriptorsOfWhatCannotBePersisted() {
        val opened = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val kept = mutableMapOf<String, Kept>()
        val grants = UriGrants(
            take = { if ("shared" in it) throw SecurityException("not persistable") },
            drop = {},
            keep = { uri ->
                opened += uri
                if (uri.endsWith("/shared-gone")) throw java.io.FileNotFoundException(uri)
                Kept(uri).also { kept[uri] = it }
            },
            limit = 2,
            keepLimit = 3,
            onKeepFailed = { uri, _ -> warnings += uri },
        )
        // 2 persisted (the limit), then descriptors for the rest up to 3; a failing open is skipped and logged.
        val uris = listOf("content://x/a", "content://x/shared-1", "content://x/b", "content://x/c", "content://x/shared-gone",
            "content://x/shared-2", "content://x/shared-3")

        grants.hold(uris)

        assertEquals(listOf("content://x/shared-1", "content://x/c", "content://x/shared-gone", "content://x/shared-2"), opened)
        assertEquals(listOf("content://x/shared-gone"), warnings)
        assertEquals(setOf("content://x/shared-1", "content://x/c", "content://x/shared-2"), kept.keys)
        assertTrue(grants.kept("content://x/shared-1") === kept["content://x/shared-1"])
        assertEquals(null, grants.kept("content://x/a"), "a persisted file needs no descriptor")
        assertEquals(null, grants.kept("content://x/shared-3"), "past the descriptor cap")

        // Holding again keeps no second descriptor.
        grants.hold(listOf("content://x/shared-1"))
        assertEquals(4, opened.size)

        grants.release(uris)
        assertTrue(kept.values.all { it.closed == 1 }, "every kept descriptor closed once")
        assertEquals(null, grants.kept("content://x/shared-1"))
        grants.release(uris)
        assertTrue(kept.values.all { it.closed == 1 }, "closed once")
    }

    @Test
    fun forgetClosesOneDescriptor() {
        val k = Kept("content://x/shared")
        val grants = UriGrants(take = { throw SecurityException() }, drop = {}, keep = { k })
        grants.hold(listOf("content://x/shared"))
        assertTrue(grants.kept("content://x/shared") === k)

        grants.forget("content://x/shared")

        assertEquals(1, k.closed)
        assertEquals(null, grants.kept("content://x/shared"))
        assertEquals(256, UriGrants.MAX_KEPT_DESCRIPTORS)
    }

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

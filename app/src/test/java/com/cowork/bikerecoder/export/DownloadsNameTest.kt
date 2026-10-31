package com.cowork.bikerecoder.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The name an export is saved under when it is taken (spec §4.2): "x (1).gpx", never the "x.gpx (1)" some phones make
 * (the S25 did on publishing; no app then opened it as GPX).
 */
class DownloadsNameTest {

    @Test
    fun numberGoesBeforeTheExtension() {
        assertEquals("bikeRecoder_20261018_1026.gpx", DownloadsWriter.numberedName("bikeRecoder_20261018_1026.gpx", 0))
        assertEquals("bikeRecoder_20261018_1026 (1).gpx", DownloadsWriter.numberedName("bikeRecoder_20261018_1026.gpx", 1))
        assertEquals("bikeRecoder_route_20261101_0304 (12).kml", DownloadsWriter.numberedName("bikeRecoder_route_20261101_0304.kml", 12))
        assertEquals("noext (2)", DownloadsWriter.numberedName("noext", 2))
    }

    @Test
    fun firstFreeNameSkipsTheFolderNames() {
        // What the S25 held after two exports of the same ride: the phone's broken names do not block "a (1).gpx".
        val taken = setOf("a.gpx", "a.gpx (1)", "a.gpx (2)", "a.kml")

        assertEquals(1, DownloadsWriter.freeIndex("a.gpx", taken, 0))
        assertEquals(0, DownloadsWriter.freeIndex("b.gpx", taken, 0))
        assertEquals(3, DownloadsWriter.freeIndex("a.gpx", taken + setOf("a (1).gpx", "a (2).gpx"), 0))
    }

    /** A saved entry whose name the phone changes the S25's way when it is held by a file the app cannot see. */
    private class Entry(var name: String, val hidden: Set<String>) {
        val renames = mutableListOf<String>()

        fun rename(to: String) {
            renames += to
            name = if (to in hidden) "$to (1)" else to
        }
    }

    @Test
    fun keptNameNeedsNoRename() {
        val entry = Entry("a (1).gpx", hidden = emptySet())

        assertEquals("a (1).gpx", DownloadsWriter.settleName("a.gpx", setOf("a.gpx"), 1, { entry.name }, entry::rename))
        assertEquals(emptyList<String>(), entry.renames)
    }

    @Test
    fun phoneRenameIsSettledToTheNextNumber() {
        // "a.gpx" and "a (1).gpx" are held by files of an earlier install: the phone published "a.gpx (1)".
        val entry = Entry("a.gpx (1)", hidden = setOf("a.gpx", "a (1).gpx"))

        assertEquals("a (2).gpx", DownloadsWriter.settleName("a.gpx", emptySet(), 0, { entry.name }, entry::rename))
        assertEquals(listOf("a (1).gpx", "a (2).gpx"), entry.renames)
        assertEquals("a (2).gpx", entry.name)
    }

    @Test
    fun renamesSkipTheFolderNames() {
        val entry = Entry("a.gpx (1)", hidden = setOf("a.gpx"))

        assertEquals("a (3).gpx", DownloadsWriter.settleName("a.gpx", setOf("a (1).gpx", "a (2).gpx"), 0, { entry.name }, entry::rename))
        assertEquals(listOf("a (3).gpx"), entry.renames)
    }

    @Test
    fun goneEntryIsNull() {
        assertNull(DownloadsWriter.settleName("a.gpx", emptySet(), 0, { null }, { error("no rename") }))
    }

    @Test
    fun stopsAfterTheLimit() {
        var renames = 0
        val shown = DownloadsWriter.settleName("a.gpx", emptySet(), 0, { "phone name" }, { renames++ })

        assertEquals(DownloadsWriter.MAX_NAME_TRIES, renames)
        assertEquals("phone name", shown)
    }
}

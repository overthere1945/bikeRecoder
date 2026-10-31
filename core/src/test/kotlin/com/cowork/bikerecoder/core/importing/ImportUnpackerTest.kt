package com.cowork.bikerecoder.core.importing

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportUnpackerTest {

    private val gpx = """<?xml version="1.0"?><gpx version="1.1"><trk><trkseg></trkseg></trk></gpx>""".toByteArray()

    private fun collect(name: String, bytes: ByteArray, limits: ImportLimits = ImportLimits()): List<ImportItem> {
        val items = mutableListOf<ImportItem>()
        ImportUnpacker.visit(name, ByteArrayInputStream(bytes), limits) { items += it }
        return items
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** null 내용은 폴더 항목(이름 끝에 `/`을 붙여 넣는다). */
    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                if (content != null) z.write(content)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun binNamedFileIsSniffedAsGpx() {
        val items = collect("track.bin", gpx)

        val item = assertIs<ImportItem.Gpx>(items.single())
        assertEquals("track.bin", item.name)
        assertEquals(0, item.index)
        assertContentEquals(gpx, item.bytes)
    }

    @Test
    fun bomAndWhitespaceBeforeGpx() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "  \r\n\t".toByteArray() + gpx

        assertIs<ImportItem.Gpx>(collect("a.gpx", bytes).single())
    }

    @Test
    fun prefixedRootIsGpx() {
        val bytes = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"/>""".toByteArray()

        assertIs<ImportItem.Gpx>(collect("a.gpx", bytes).single())
    }

    @Test
    fun htmlIsNotGpx() {
        val item = collect("page.gpx", "<html><body>hi</body></html>".toByteArray()).single()

        assertEquals(ImportItem.Unreadable("page.gpx", 0, ImportProblem.NOT_GPX), item)
    }

    @Test
    fun emptyFileIsNotGpx() {
        assertEquals(ImportItem.Unreadable("e.gpx", 0, ImportProblem.NOT_GPX), collect("e.gpx", ByteArray(0)).single())
    }

    @Test
    fun gzipGpxIsUnpacked() {
        val item = assertIs<ImportItem.Gpx>(collect("a.gpx.gz", gzip(gpx)).single())

        assertEquals("a.gpx.gz", item.name)
        assertContentEquals(gpx, item.bytes)
    }

    @Test
    fun gzipIsUnpackedOnlyOnce() {
        val item = collect("a.gz", gzip(gzip(gpx))).single()

        assertEquals(ImportItem.Unreadable("a.gz", 0, ImportProblem.NOT_GPX), item)
    }

    @Test
    fun brokenGzipIsUnreadable() {
        val broken = gzip(gpx).copyOf(12)

        assertEquals(ImportItem.Unreadable("a.gz", 0, ImportProblem.UNREADABLE), collect("a.gz", broken).single())
    }

    @Test
    fun zipYieldsEachGpxWithIndexAndName() {
        val second = "<gpx/>".toByteArray()
        val items = collect("a.zip", zip("activities/1.gpx" to gpx, "activities/2.gpx" to second))

        assertEquals(2, items.size)
        val first = assertIs<ImportItem.Gpx>(items[0])
        assertEquals("a.zip / activities/1.gpx", first.name)
        assertEquals(0, first.index)
        val next = assertIs<ImportItem.Gpx>(items[1])
        assertEquals("a.zip / activities/2.gpx", next.name)
        assertEquals(1, next.index)
        assertContentEquals(second, next.bytes)
    }

    @Test
    fun zipSkipsFolders() {
        val items = collect("a.zip", zip("activities/" to null, "activities/1.gpx" to gpx))

        val item = assertIs<ImportItem.Gpx>(items.single())
        assertEquals(0, item.index)
    }

    @Test
    fun nestedZipIsNotGpx() {
        val inner = zip("x.gpx" to gpx)
        val items = collect("a.zip", zip("inner.zip" to inner))

        assertEquals(ImportItem.Unreadable("a.zip / inner.zip", 0, ImportProblem.NOT_GPX), items.single())
    }

    @Test
    fun oversizeItemIsTooLarge() {
        val big = ByteArray(5_000) { 'a'.code.toByte() }
        val limits = ImportLimits(maxItemBytes = 1_000)

        assertEquals(ImportItem.Unreadable("big.gpx", 0, ImportProblem.TOO_LARGE), collect("big.gpx", big, limits).single())
        val inZip = collect("a.zip", zip("big.gpx" to big, "ok.gpx" to gpx), limits)
        assertEquals(ImportItem.Unreadable("a.zip / big.gpx", 0, ImportProblem.TOO_LARGE), inZip[0])
        assertIs<ImportItem.Gpx>(inZip[1])
    }

    @Test
    fun oversizeAfterGunzipIsTooLarge() {
        val big = gpx + ByteArray(5_000) { ' '.code.toByte() }
        val limits = ImportLimits(maxItemBytes = 1_000)
        val packed = gzip(big)
        assertTrue(packed.size < 1_000, "압축본은 한도 안이어야 한다: ${packed.size}")

        assertEquals(ImportItem.Unreadable("a.gz", 0, ImportProblem.TOO_LARGE), collect("a.gz", packed, limits).single())
    }

    @Test
    fun zipOverEntryLimitStopsWithNotice() {
        val entries = (1..5).map { "$it.gpx" to gpx }.toTypedArray()
        val items = collect("a.zip", zip(*entries), ImportLimits(maxZipEntries = 3))

        assertEquals(4, items.size)
        assertEquals(3, items.count { it is ImportItem.Gpx })
        assertEquals(ImportItem.TooManyEntries("a.zip"), items.last())
        assertEquals(-1, items.last().index)
    }

    @Test
    fun zipAtEntryLimitHasNoNotice() {
        val entries = (1..3).map { "$it.gpx" to gpx }.toTypedArray()
        val items = collect("a.zip", zip(*entries), ImportLimits(maxZipEntries = 3))

        assertEquals(3, items.size)
        assertTrue(items.all { it is ImportItem.Gpx })
    }

    @Test
    fun stravaLikeZipYieldsOnlyGpxAndNotices() {
        val items = collect(
            "export.zip",
            zip(
                "activities/" to null,
                "activities/1.gpx" to gpx,
                "activities/2.gpx.gz" to gzip(gpx),
                "activities/3.fit.gz" to gzip(byteArrayOf(0x0E, 0x10, 0x43, 0x08, 0x00, 0x01)),
                "activities.csv" to "id,name\n1,Ride\n".toByteArray(),
            ),
        )

        assertEquals(4, items.size)
        assertEquals(2, items.count { it is ImportItem.Gpx })
        val problems = items.filterIsInstance<ImportItem.Unreadable>()
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.problem == ImportProblem.NOT_GPX })
        assertEquals(listOf(0, 1, 2, 3), items.map { it.index })
    }

    @Test
    fun visitorExceptionPropagates() {
        var calls = 0

        assertFailsWith<java.io.IOException> {
            ImportUnpacker.visit("a.gpx", ByteArrayInputStream(gpx)) {
                calls++
                throw java.io.IOException("visitor failed")
            }
        }
        assertEquals(1, calls)
        calls = 0
        assertFailsWith<java.io.IOException> {
            ImportUnpacker.visit("a.zip", ByteArrayInputStream(zip("1.gpx" to gpx))) {
                calls++
                throw java.io.IOException("visitor failed")
            }
        }
        assertEquals(1, calls)
    }
}

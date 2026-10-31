package com.cowork.bikerecoder.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes real files into Download/bikeRecoder (spec §4.2). Only the entries this test made are deleted, each by its own
 * URI, when it ends.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsWriterTest {
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val writer = DownloadsWriter(resolver)
    private val created = mutableListOf<Uri>()
    private val stamp = System.nanoTime()

    @After
    fun tearDown() {
        created.forEach { resolver.delete(it, null, null) }
        created.forEach { assertFalse("left behind: $it", exists(it)) }
    }

    @Test
    fun writesUnderDownloadBikeRecoder() {
        val name = "bikeRecoder_test_$stamp.gpx"
        val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx><metadata><name>저녁 라이딩 · 서울</name></metadata></gpx>\n"

        val uri = writer.write(name, ExportFormat.GPX.mime, content).also { created += it }

        val row = row(uri)
        assertEquals(name, row.name)
        assertEquals("Download/bikeRecoder/", row.relativePath)
        assertEquals("published", 0, row.pending)
        // MediaStore keeps its own type for the extension (octet-stream for .gpx on the S22); [공유] and [파일 열기]
        // carry the export's MIME themselves. The name is not changed to suit it.
        assertTrue(row.mime, row.mime.isNotEmpty())
        assertEquals(content, read(uri))
        assertEquals(name, writer.displayName(uri))
    }

    @Test
    fun sameNameGetsSuffix() {
        val base = "bikeRecoder_test_${stamp}_twice"
        val name = "$base.kml"

        val first = writer.save(name, ExportFormat.KML.mime, "<kml>1</kml>").also { created += it.uri.toUri() }
        val second = writer.save(name, ExportFormat.KML.mime, "<kml>2</kml>").also { created += it.uri.toUri() }

        assertEquals(name, first.displayName)
        assertNotEquals(name, second.displayName)
        assertTrue(second.displayName, second.displayName.startsWith(base) && second.displayName.endsWith(".kml"))
        assertEquals(second.displayName, row(second.uri.toUri()).name)
        assertEquals(ExportFormat.KML.mime, second.mime)
        assertEquals("<kml>1</kml>", read(first.uri.toUri()))
        assertEquals("<kml>2</kml>", read(second.uri.toUri()))
    }

    private data class Row(val name: String, val relativePath: String, val pending: Int, val mime: String)

    private fun args() = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }

    private fun row(uri: Uri): Row {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        resolver.query(uri, projection, args(), null)!!.use { c ->
            assertTrue("no row for $uri", c.moveToFirst())
            return Row(c.getString(0), c.getString(1), c.getInt(2), c.getString(3))
        }
    }

    private fun exists(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), args(), null)?.use { it.moveToFirst() } ?: false

    private fun read(uri: Uri): String = resolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
}

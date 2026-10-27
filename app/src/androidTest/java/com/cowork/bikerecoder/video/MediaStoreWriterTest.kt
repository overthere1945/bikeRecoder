package com.cowork.bikerecoder.video

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 갤러리에 실제로 쓴다. 테스트가 만든 항목만, 테스트가 끝나면 모두 지운다. */
@RunWith(AndroidJUnit4::class)
class MediaStoreWriterTest {
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val writer = MediaStoreWriter(resolver)
    private val created = mutableListOf<Uri>()
    private val stamp = System.nanoTime()

    @After
    fun tearDown() {
        created.forEach { writer.delete(it) }
        created.forEach { assertFalse("left behind: $it", writer.exists(it)) }
    }

    private fun create(kind: MediaKind, name: String): Uri = writer.createPending(kind, name).also { created += it }

    @Test
    fun videoIsPendingInMoviesUntilPublishedThenDeleted() {
        val name = "bikeRecoder_test_$stamp.mp4"
        val uri = create(MediaKind.VIDEO, name)
        resolver.openOutputStream(uri)!!.use { it.write(ByteArray(16)) }

        assertTrue(writer.exists(uri))
        assertEquals(Row(name, "Movies/bikeRecoder/", 1, "video/mp4"), row(uri))
        assertTrue(name in writer.existingNames(MediaKind.VIDEO))
        assertFalse(name in writer.existingNames(MediaKind.IMAGE))

        writer.publish(uri)
        assertTrue(writer.exists(uri))
        assertEquals(0, row(uri).pending)
        assertTrue(name in writer.existingNames(MediaKind.VIDEO))

        writer.delete(uri)
        assertFalse(writer.exists(uri))
        assertFalse(name in writer.existingNames(MediaKind.VIDEO))
    }

    @Test
    fun imageGoesToPicturesAndAPendingOneCanBeDeleted() {
        val name = "bikeRecoder_test_$stamp.png"
        val uri = create(MediaKind.IMAGE, name)

        assertEquals(Row(name, "Pictures/bikeRecoder/", 1, "image/png"), row(uri))
        assertTrue(name in writer.existingNames(MediaKind.IMAGE))

        writer.delete(uri)
        assertFalse(writer.exists(uri))
        assertFalse(name in writer.existingNames(MediaKind.IMAGE))
    }

    @Test
    fun deletingAnItemThatIsAlreadyGoneDoesNothing() {
        val uri = create(MediaKind.IMAGE, "bikeRecoder_test_${stamp}_gone.png")
        writer.delete(uri)

        writer.delete(uri)

        assertFalse(writer.exists(uri))
    }

    private data class Row(val name: String, val relativePath: String, val pending: Int, val mime: String)

    private fun row(uri: Uri): Row {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        resolver.query(uri, projection, args, null)!!.use { c ->
            assertTrue("no row for $uri", c.moveToFirst())
            return Row(c.getString(0), c.getString(1), c.getInt(2), c.getString(3))
        }
    }
}

package com.cowork.bikerecoder.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.MediaStoreWriter
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.VideoOptionsJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Writes real gallery items; every item the test made is deleted when it ends. */
@RunWith(AndroidJUnit4::class)
class ShareCandidatesTest {
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val writer = MediaStoreWriter(resolver)
    private val candidates = ShareCandidates(writer)
    private val created = mutableListOf<Uri>()
    private val stamp = System.nanoTime()

    @After
    fun tearDown() {
        created.forEach { writer.delete(it) }
        created.forEach { assertFalse("left behind: $it", writer.exists(it)) }
    }

    private fun makeImage(name: String, bytes: Int): Uri {
        val uri = writer.createPending(MediaKind.IMAGE, name).also { created += it }
        resolver.openOutputStream(uri)!!.use { it.write(ByteArray(bytes)) }
        writer.publish(uri)
        return uri
    }

    private fun media(uri: Uri, optionsJson: String, id: Long = 1L) =
        TripMedia(id, tripId = 1L, kind = MediaKind.IMAGE, uri = uri.toString(), createdAt = 5L, optionsJson = optionsJson)

    @Test
    fun deletedGalleryFileIsNotACandidate() = runBlocking {
        val kept = makeImage("bikeRecoder_test_${stamp}_kept.png", 64)
        val gone = makeImage("bikeRecoder_test_${stamp}_gone.png", 64)
        val json = VideoOptionsJson.encode(VideoOptions())
        writer.delete(gone)

        val result = candidates.of(listOf(media(gone, json, 1L), media(kept, json, 2L)))

        assertEquals(listOf(kept.toString()), result.map { it.uri })
    }

    @Test
    fun sizeAndAspectRead() = runBlocking {
        val uri = makeImage("bikeRecoder_test_${stamp}_size.png", 64)
        val portrait = VideoOptionsJson.encode(VideoOptions(aspect = Aspect.PORTRAIT))
        val landscape = VideoOptionsJson.encode(VideoOptions(aspect = Aspect.LANDSCAPE))

        val p = candidates.of(listOf(media(uri, portrait))).single()
        val l = candidates.of(listOf(media(uri, landscape))).single()

        assertEquals(Aspect.PORTRAIT, p.aspect)
        assertEquals(Aspect.LANDSCAPE, l.aspect)
        assertEquals(MediaKind.IMAGE, p.kind)
        assertEquals(5L, p.createdAt)
        assertNotNull(p.sizeBytes)
        assertTrue(p.sizeBytes!! > 0)
    }

    @Test
    fun unreadableOptionsAreThePortraitDefault() = runBlocking {
        val uri = makeImage("bikeRecoder_test_${stamp}_bad.png", 64)

        val result = candidates.of(listOf(media(uri, "not json"))).single()

        assertEquals(Aspect.PORTRAIT, result.aspect)
    }

    @Test
    fun sizeOfIsNullForAGoneItemAndForAnEmptyOne() {
        val empty = writer.createPending(MediaKind.IMAGE, "bikeRecoder_test_${stamp}_empty.png").also { created += it }
        writer.publish(empty)
        val gone = makeImage("bikeRecoder_test_${stamp}_sizegone.png", 8)
        assertEquals(8L, writer.sizeOf(gone))
        writer.delete(gone)

        assertNull(writer.sizeOf(gone))
        assertNull(writer.sizeOf(empty))
    }
}

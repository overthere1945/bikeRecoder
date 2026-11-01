package com.cowork.bikerecoder.share

import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 공유 카드 파일 캐시(공유 카드 설계 §3.2): `cacheDir/share/<tripId>/`, 24시간 지난 파일 정리. */
class ShareCacheTest {

    @TempDir
    lateinit var dir: File

    private val now = 100L * HOUR

    private val root: File get() = File(dir, "share")

    private fun cache() = ShareCache(root, uriOf = { "content://test/${it.parentFile!!.name}/${it.name}" }, now = { now })

    private fun fileAt(tripId: Long, name: String, modifiedAt: Long): File =
        cache().fileFor(tripId, name).apply {
            writeText("x")
            setLastModified(modifiedAt)
        }

    @Test
    fun sweepDeletesOnlyOlderThan24h() {
        val old = fileAt(1, "feed-satellite.jpg", now - 25 * HOUR)
        val fresh = fileAt(1, "story-satellite.jpg", now - 23 * HOUR)

        cache().sweep()

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun sweepRemovesEmptyTripDirs() {
        val old = fileAt(1, "feed-satellite.jpg", now - 25 * HOUR)
        val fresh = fileAt(2, "sticker.png", now - HOUR)

        cache().sweep()

        assertFalse(old.parentFile!!.exists())
        assertTrue(fresh.parentFile!!.isDirectory)
        assertTrue(root.isDirectory)
    }

    @Test
    fun fileForMakesDirs() {
        val file = cache().fileFor(7, "feed-photo-12.jpg")

        assertEquals(File(File(root, "7"), "feed-photo-12.jpg"), file)
        assertTrue(file.parentFile!!.isDirectory)
        assertFalse(file.exists())
    }

    @Test
    fun shareCarriesSizeAndMime() {
        val file = cache().fileFor(3, "sticker.png").apply { writeBytes(ByteArray(1234)) }

        val shared = cache().share(file, "image/png")

        assertEquals(ShareFile("content://test/3/sticker.png", "image/png", 1234L), shared)
    }

    @Test
    fun shareOfMissingFileHasNoSize() {
        val file = cache().fileFor(3, "sticker.png")

        assertNull(cache().share(file, "image/png").sizeBytes)
    }

    @Test
    fun writeReplacesWholeFileAndLeavesNoTemp() {
        val file = cache().fileFor(4, "feed-satellite.jpg").apply { writeText("old card, longer") }

        cache().write(file, byteArrayOf(1, 2, 3))

        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
        assertEquals(listOf("feed-satellite.jpg"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun enospcBecomesNoSpace() {
        val direct = IOException("write failed: ENOSPC (No space left on device)")
        val wrapped = IOException("write failed", IOException("No space left on device"))

        assertTrue(noSpaceOr(direct) is NoSpaceException)
        assertTrue(noSpaceOr(wrapped) is NoSpaceException)
        val other = IOException("EIO")
        assertTrue(noSpaceOr(other) === other)
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}

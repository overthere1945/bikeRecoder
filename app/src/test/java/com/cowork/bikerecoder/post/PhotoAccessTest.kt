package com.cowork.bikerecoder.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PhotoAccessTest {
    private val images = "android.permission.READ_MEDIA_IMAGES"
    private val selected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private val storage = "android.permission.READ_EXTERNAL_STORAGE"

    private fun access(sdk: Int, vararg granted: String) = photoAccessOf(sdk) { it in granted }

    @Test
    fun `Android 13 and later need READ_MEDIA_IMAGES for all photos`() {
        assertEquals(PhotoAccess.FULL, access(33, images))
        assertEquals(PhotoAccess.FULL, access(36, images, selected))
        assertEquals(PhotoAccess.NONE, access(33))
        // READ_EXTERNAL_STORAGE is declared only up to API 32; it never means access on 33+
        assertEquals(PhotoAccess.NONE, access(33, storage))
    }

    @Test
    fun `Android 14 and later can allow selected photos only`() {
        assertEquals(PhotoAccess.PARTIAL, access(34, selected))
        assertEquals(PhotoAccess.PARTIAL, access(37, selected))
        assertEquals(PhotoAccess.NONE, access(33, selected))
    }

    @Test
    fun `Android 11 and 12 use READ_EXTERNAL_STORAGE`() {
        assertEquals(PhotoAccess.FULL, access(30, storage))
        assertEquals(PhotoAccess.FULL, access(31, storage))
        assertEquals(PhotoAccess.FULL, access(32, storage))
        assertEquals(PhotoAccess.NONE, access(30))
        assertEquals(PhotoAccess.NONE, access(32))
    }
}

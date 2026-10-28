package com.cowork.bikerecoder.ui.post

import android.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What [허용] on the photo card asks for, by Android version (ruling R5 for 11–12). */
class PhotoPermissionsTest {
    @Test
    fun android14AndLaterAsksForAllOrSelectedPhotos() {
        val expected = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(expected, photoPermissionsFor(34).toList())
        assertEquals(expected, photoPermissionsFor(36).toList())
    }

    @Test
    fun android13AsksForImages() {
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), photoPermissionsFor(33).toList())
    }

    @Test
    fun android11And12AskForExternalStorage() {
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), photoPermissionsFor(30).toList())
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), photoPermissionsFor(32).toList())
    }
}

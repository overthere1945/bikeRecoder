package com.cowork.bikerecoder.post

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Runs against the real MediaStore with images this test inserts itself (and deletes afterwards).
 * The window is in 2001 so the phone's own photos never fall inside it.
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePhotoSourceTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val inserted = mutableListOf<Uri>()
    private val source = MediaStorePhotoSource(context)

    private val t0 = 978_307_200_000L // 2001-01-01T00:00:00Z

    @After
    fun deleteInsertedImages() {
        inserted.forEach { resolver.delete(it, null, null) }
    }

    @Test
    fun findsOnlyCameraPhotosInsideTheWindow() = runBlocking {
        val camera = insert("DCIM/Camera/", t0)
        insert("Pictures/Screenshots/", t0 + 1_000L)
        insert("DCIM/Camera/", t0 + 10 * 60_000L)

        val found = source.photos(t0 - 60_000L, t0 + 60_000L)

        assertEquals(PhotoAccess.FULL, source.access())
        assertEquals(listOf(camera), found.map { it.mediaId })
        assertEquals(t0, found.single().takenAt)
        assertNull(found.single().exif)
    }

    @Test
    fun windowIsInclusiveAndInTimeOrder() = runBlocking {
        val last = insert("DCIM/Camera/", t0 + 2_000L)
        val first = insert("DCIM/Camera/", t0)
        insert("DCIM/Camera/", t0 + 3_000L)
        insert("DCIM/Camera/", t0 - 1_000L)

        val found = source.photos(t0, t0 + 2_000L)

        assertEquals(listOf(first, last), found.map { it.mediaId })
    }

    @Test
    fun readsExifLocationWithMediaLocationPermission() = runBlocking {
        val id = insert("DCIM/Camera/", t0, gps = GeoPoint(37.5665, 126.978))

        val exif = source.photos(t0, t0).single { it.mediaId == id }.exif!!

        assertEquals(37.5665, exif.lat, 1e-4)
        assertEquals(126.978, exif.lon, 1e-4)
    }

    @Test
    fun existingDropsPhotosDeletedFromTheGallery() = runBlocking {
        val kept = insert("DCIM/Camera/", t0)
        val gone = insert("DCIM/Camera/", t0 + 1_000L)
        val goneUri = ContentUris.withAppendedId(collection, gone)
        resolver.delete(goneUri, null, null)
        inserted.remove(goneUri)

        assertEquals(setOf(kept), source.existing(listOf(kept, gone)))
        assertEquals(emptySet<Long>(), source.existing(emptyList()))
    }

    /**
     * Inserts a tiny JPEG at [relativePath] taken at [takenAt] (whole seconds); returns its MediaStore id.
     * MediaProvider derives DATE_TAKEN from the EXIF date when it scans the published file (a DATE_TAKEN
     * value put in ContentValues is dropped), so the time goes into the JPEG.
     */
    private fun insert(relativePath: String, takenAt: Long, gps: GeoPoint? = null): Long {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "bikeRecoderTest_${System.nanoTime()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)!!
        inserted += uri
        resolver.openOutputStream(uri)!!.use { it.write(jpeg(takenAt, gps)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return ContentUris.parseId(uri)
    }

    private fun jpeg(takenAt: Long, gps: GeoPoint?): ByteArray {
        val file = File.createTempFile("photo", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            ExifInterface(file.path).apply {
                val utc = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, utc.format(Date(takenAt)))
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                if (gps != null) {
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(gps.lat))
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (gps.lat >= 0) "N" else "S")
                    setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(gps.lon))
                    setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (gps.lon >= 0) "E" else "W")
                }
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** Degrees as the EXIF rational triple "d/1,m/1,s/10000". */
    private fun dms(degrees: Double): String {
        val abs = Math.abs(degrees)
        val d = abs.toInt()
        val m = ((abs - d) * 60).toInt()
        val s = Math.round(((abs - d) * 60 - m) * 60 * 10_000)
        return "$d/1,$m/1,$s/10000"
    }
}

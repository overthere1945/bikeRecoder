package com.cowork.bikerecoder.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.Box
import com.cowork.bikerecoder.core.video.SummaryImageLayout
import com.cowork.bikerecoder.map.OpenFreeMapSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The summary image (spec §4) over a real MapLibre snapshot; the checks hold even when the tiles do not load. */
@RunWith(AndroidJUnit4::class)
class SummaryImageRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val renderer = SummaryImageRenderer(context, OpenFreeMapSource(OkHttpClient(), cacheFile = null))
    private val route = Color.parseColor(RideColors.colorAt(0))

    private fun render(inputs: VideoInputs, aspect: Aspect, photos: List<Bitmap>) = runBlocking { renderer.render(inputs, aspect, photos) }

    private fun Bitmap.routePixels(box: Box) = VideoTestTrip.count(
        this, box.x.toInt(), box.y.toInt(), (box.x + box.w).toInt(), (box.y + box.h).toInt(),
    ) { VideoTestTrip.near(it, route) }

    private fun Bitmap.isPhotoAt(box: Box) =
        VideoTestTrip.near(getPixel((box.x + box.w / 2).toInt(), (box.y + box.h / 2).toInt()), VideoTestTrip.PHOTO_COLOR, 12)

    private fun assertSoftware(image: Bitmap, aspect: Aspect) {
        assertEquals(aspect.width, image.width)
        assertEquals(aspect.height, image.height)
        assertEquals(Bitmap.Config.ARGB_8888, image.config)
    }

    @Test
    fun portraitImageHasMapAndThreePhotos() {
        val photo = VideoTestTrip.photo()
        val image = render(VideoTestTrip.inputs(), Aspect.PORTRAIT, listOf(photo, photo, photo))

        assertSoftware(image, Aspect.PORTRAIT)
        val layout = SummaryImageLayout.layout(Aspect.PORTRAIT, 3)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        assertTrue(layout.photos.all { image.isPhotoAt(it) })
    }

    @Test
    fun landscapeImageHasMapOnTheLeft() {
        val photo = VideoTestTrip.photo()
        val image = render(VideoTestTrip.inputs(), Aspect.LANDSCAPE, listOf(photo))

        assertSoftware(image, Aspect.LANDSCAPE)
        val layout = SummaryImageLayout.layout(Aspect.LANDSCAPE, 1)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        assertTrue(image.isPhotoAt(layout.photos.single()))
        assertEquals("nothing of the route right of the map", 0, image.routePixels(Box(layout.map.w + 1, 0f, 1920 - layout.map.w - 1, 1080f)))
    }

    @Test
    fun imageWithoutPhotosOrHeartRate() {
        val image = render(VideoTestTrip.inputs(withPhoto = false, withHeartRate = false), Aspect.PORTRAIT, emptyList())

        assertSoftware(image, Aspect.PORTRAIT)
        val layout = SummaryImageLayout.layout(Aspect.PORTRAIT, 0)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        // No photo row: the map takes its height, and only a thin plain band is left above "bikeRecoder".
        assertTrue(layout.map.h > SummaryImageLayout.layout(Aspect.PORTRAIT, 3).map.h)
        val below = layout.stats.last().let { (it.y + it.h + 2).toInt() }
        val above = (layout.footer.y - 2).toInt()
        assertTrue("band ${above - below}", above - below in 1..48)
        val plain = VideoTestTrip.count(image, 100, below, 980, above) { VideoTestTrip.near(it, Color.WHITE, 3) }
        assertEquals(880 * (above - below), plain)
        save(image, "image-nophoto-portrait")
    }

    @Test
    fun landscapeImageWithoutPhotos() {
        val image = render(VideoTestTrip.inputs(withPhoto = false, withHeartRate = false), Aspect.LANDSCAPE, emptyList())

        assertSoftware(image, Aspect.LANDSCAPE)
        val layout = SummaryImageLayout.layout(Aspect.LANDSCAPE, 0)
        assertTrue("route on the map", image.routePixels(layout.map) > 500)
        // The right column is centred above the footer: as much room above the header as below the last row (+ gap).
        val top = layout.header.y - 48f
        val bottom = layout.footer.y - layout.stats.maxOf { it.y + it.h }
        assertTrue("above $top below $bottom", kotlin.math.abs(bottom - top - 24f) <= 1f)
        save(image, "image-nophoto-landscape")
    }

    /** For looking at: Download/bikeRecoderTest/[name].png (pulled and deleted by hand). */
    private fun save(image: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/bikeRecoderTest/")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

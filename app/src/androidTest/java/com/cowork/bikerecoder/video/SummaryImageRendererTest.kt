package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
        // Where a photo row would be: plain background.
        val below = layout.stats.last().let { (it.y + it.h + 30).toInt() }
        val plain = VideoTestTrip.count(image, 100, below, 980, below + 200) { VideoTestTrip.near(it, Color.WHITE, 3) }
        assertEquals(880 * 200, plain)
    }
}

package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.BuildConfig
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.map.OpenFreeMapSource
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real VWorld satellite map (spec §1.1, §1.3) over the device's internet, with the build's key. The frames are
 * also written to the app's external files dir (sat-plain.png, sat-satellite.png) for a look.
 */
@RunWith(AndroidJUnit4::class)
class SatelliteMapTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val client = OkHttpClient()
    private val gangseo = GeoPoint(37.5585, 126.8280)
    private val camera = Camera(gangseo, zoom = 17.2, bearing = 0.0, tilt = 60.0)
    private val snapshots = mutableListOf<MapSnapshots>()

    @After
    fun tearDown() = snapshots.forEach { it.close() }

    private fun shot(source: TileSource): Bitmap = runBlocking {
        MapSnapshots(context, source, W, H).also { snapshots += it }.take(camera).bitmap
    }

    @Test
    fun satelliteFrameLooksLikeAPhoto() {
        assertTrue("secrets.json has a vworldApiKey", BuildConfig.VWORLD_API_KEY.isNotBlank())
        val base = OpenFreeMapSource(client, cacheFile = null)

        val plain = shot(base)
        val satellite = shot(SatelliteTileSource(base, BuildConfig.VWORLD_API_KEY))
        save(plain, "sat-plain.png")
        save(satellite, "sat-satellite.png")

        val plainGrey = share(plain, ::lightGrey)
        val satelliteGrey = share(satellite, ::lightGrey)
        // Not the satellite style's own dark background either (tiles that never came).
        val background = share(satellite) { near(it, Color.parseColor("#1A1F1A"), 12) }
        val message = "light grey: plain %.3f, satellite %.3f; background %.3f".format(plainGrey, satelliteGrey, background)
        android.util.Log.i(TAG, message)
        assertTrue(message, plainGrey - satelliteGrey >= 0.25)
        assertTrue(message, background < 0.10)
    }

    @Test
    fun realKeyProbeTrue() = runBlocking {
        assertTrue(SatelliteProbe(client, BuildConfig.VWORLD_API_KEY).check(gangseo))
    }

    @Test
    fun wrongKeyProbeFalse() = runBlocking {
        assertFalse(SatelliteProbe(client, "0000AAAA-0000-0000-0000-000000000000").check(gangseo))
    }

    private fun lightGrey(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 200 && g > 200 && b > 200 && maxOf(r, g, b) - minOf(r, g, b) < 20
    }

    private fun near(c: Int, target: Int, tolerance: Int) =
        kotlin.math.abs(Color.red(c) - Color.red(target)) <= tolerance &&
            kotlin.math.abs(Color.green(c) - Color.green(target)) <= tolerance &&
            kotlin.math.abs(Color.blue(c) - Color.blue(target)) <= tolerance

    private fun share(bitmap: Bitmap, test: (Int) -> Boolean): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count(test).toDouble() / pixels.size
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TAG = "SatelliteMapTest"
        const val W = 1080
        const val H = 1920
    }
}

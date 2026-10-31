package com.cowork.bikerecoder.importing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.importing.GpxReadException
import com.cowork.bikerecoder.core.importing.GpxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The core GPX reader runs on the device's own XML parser; the secure settings must hold there too. */
@RunWith(AndroidJUnit4::class)
class GpxReaderAndroidTest {

    private fun gpx(prolog: String, name: String = "") = (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$prolog" +
            "<gpx version=\"1.1\" creator=\"t\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>$name</name><trkseg>" +
            "<trkpt lat=\"37.5\" lon=\"127.0\"><ele>30</ele><time>2023-05-14T00:12:00Z</time></trkpt>" +
            "<trkpt lat=\"37.51\" lon=\"127.0\"><ele>31</ele><time>2023-05-14T00:12:30Z</time></trkpt>" +
            "</trkseg></trk></gpx>"
        ).toByteArray()

    @Test
    fun plainGpxReadsOnAndroid() {
        val track = GpxReader.read(gpx(""))

        assertEquals(2, track.fixes.size)
        assertEquals(2, track.totalPoints)
        assertEquals(37.5, track.fixes[0].point.lat, 0.0)
        assertEquals(30.0, track.fixes[0].altitudeM!!, 0.0)
    }

    @Test
    fun doctypeIsRejectedOnAndroid() {
        val secret = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "xxe-secret.txt")
        secret.writeText("SECRET")
        try {
            val internal = gpx("<!DOCTYPE gpx [<!ENTITY e \"boom\">]>\n", name = "&e;")
            assertThrows(GpxReadException::class.java) { GpxReader.read(internal) }

            val external = gpx("<!DOCTYPE gpx [<!ENTITY x SYSTEM \"file://${secret.absolutePath}\">]>\n", name = "&x;")
            assertThrows(GpxReadException::class.java) { GpxReader.read(external) }
        } finally {
            secret.delete()
        }
    }
}

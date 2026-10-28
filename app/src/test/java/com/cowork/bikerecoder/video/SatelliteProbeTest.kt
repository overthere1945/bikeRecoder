package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** 시작 전 위성 타일 한 조각 확인(스펙 §1.3): 200이고 이미지일 때만 위성 지도로 만든다. */
class SatelliteProbeTest {

    private lateinit var server: MockWebServer
    private val logged = mutableListOf<String>()
    private val log = WarnLog { message, error -> logged += "$message ${error?.message.orEmpty()}" }
    private val gangseo = GeoPoint(37.5585, 126.8280)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
        // The key never reaches the log.
        assertTrue(logged.none { "TESTKEY" in it }, "$logged")
    }

    private fun probe(timeoutMs: Long = SatelliteProbe.TIMEOUT_MS) =
        SatelliteProbe(OkHttpClient(), "TESTKEY", server.url("/"), timeoutMs, log)

    private fun response(code: Int, type: String, body: ByteArray) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", type)
        .body(Buffer().write(body))
        .build()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(200)
    private val html = "<html><body>error</body></html>".toByteArray()

    @Test
    fun jpeg200IsOk() = runTest {
        server.enqueue(response(200, "image/jpeg", jpeg))
        assertTrue(probe().check(gangseo))

        server.enqueue(response(200, "image/png", png))
        assertTrue(probe().check(gangseo))
    }

    @Test
    fun probeRejectsNonImage200() = runTest {
        // VWorld answers a bad key or a quota error with 200 and an XML message.
        server.enqueue(response(200, "text/xml", "<?xml version=\"1.0\"?><error/>".toByteArray()))
        assertFalse(probe().check(gangseo))

        // An image type with an HTML body.
        server.enqueue(response(200, "image/jpeg", html))
        assertFalse(probe().check(gangseo))

        // An empty image.
        server.enqueue(response(200, "image/jpeg", ByteArray(0)))
        assertFalse(probe().check(gangseo))
    }

    @Test
    fun serverErrorAndTimeoutAreFalse() = runTest {
        server.enqueue(response(500, "image/jpeg", jpeg))
        assertFalse(probe().check(gangseo))

        server.enqueue(response(401, "text/xml", html))
        assertFalse(probe().check(gangseo))

        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/jpeg")
                .body(Buffer().write(jpeg))
                .headersDelay(5, TimeUnit.SECONDS)
                .build(),
        )
        val started = System.nanoTime()
        assertFalse(probe(timeoutMs = 300).check(gangseo))
        assertTrue(System.nanoTime() - started < 3_000_000_000L, "gave up at the time limit")

        // Nothing listening at all.
        val dead = SatelliteProbe(OkHttpClient(), "TESTKEY", server.url("/"), SatelliteProbe.TIMEOUT_MS, log)
        server.close()
        assertFalse(dead.check(gangseo))
    }

    @Test
    fun timeLimitIsTenSeconds() {
        assertEquals(10_000L, SatelliteProbe.TIMEOUT_MS)
    }

    @Test
    fun requestPathUsesTileOfThePoint() = runTest {
        server.enqueue(response(200, "image/jpeg", jpeg))
        probe().check(gangseo)

        val n = 1 shl 17
        val x = floor((gangseo.lon + 180.0) / 360.0 * n).toInt()
        val latRad = gangseo.lat * PI / 180.0
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        assertEquals("/req/wmts/1.0.0/TESTKEY/Satellite/17/$y/$x.jpeg", server.takeRequest().url.encodedPath)
    }
}

package com.cowork.bikerecoder.offline

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SegmentRepositoryTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer
    private lateinit var repo: SegmentRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = SegmentRepository(dir, OkHttpClient(), server.url("/segments4/"))
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun httpDate(iso: String): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC))

    @Test
    fun `download renames part file on success`() = runTest {
        val body = ByteArray(1024) { it.toByte() }
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(body))
                .addHeader("Last-Modified", httpDate("2026-10-05T00:00:00Z"))
                .build(),
        )
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = repo.download("E125_N35") { read, total -> progress += read to total }

        assertTrue(result.isSuccess)
        val file = File(dir, "E125_N35.rd5")
        assertEquals(file, result.getOrNull())
        assertTrue(file.exists())
        assertEquals(1024L, file.length())
        assertFalse(File(dir, "E125_N35.rd5.part").exists())
        assertEquals(Instant.parse("2026-10-05T00:00:00Z").toEpochMilli(), file.lastModified())
        assertEquals(1024L to 1024L, progress.last())
        assertEquals("/segments4/E125_N35.rd5", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `truncated download leaves nothing`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(ByteArray(1024)))
                .addHeader("Content-Length", "2048")
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )

        val result = repo.download("E125_N35") { _, _ -> }

        assertTrue(result.isFailure)
        assertFalse(File(dir, "E125_N35.rd5").exists())
        assertFalse(File(dir, "E125_N35.rd5.part").exists())
    }

    @Test
    fun `http error is failure with code`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        val result = repo.download("E125_N35") { _, _ -> }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("404"))
        assertFalse(File(dir, "E125_N35.rd5").exists())
        assertFalse(File(dir, "E125_N35.rd5.part").exists())
    }

    @Test
    fun `failed download keeps existing file`() = runTest {
        val existing = File(dir, "E125_N35.rd5").apply { writeBytes(ByteArray(10)) }
        server.enqueue(MockResponse.Builder().code(500).build())

        assertTrue(repo.download("E125_N35") { _, _ -> }.isFailure)

        assertEquals(10L, existing.length())
    }

    @Test
    fun `download replaces existing file`() = runTest {
        File(dir, "E125_N35.rd5").writeBytes(ByteArray(10))
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(100))).build())

        assertTrue(repo.download("E125_N35") { _, _ -> }.isSuccess)

        assertEquals(100L, File(dir, "E125_N35.rd5").length())
    }

    @Test
    fun `has required only when both present`() {
        assertFalse(repo.hasRequired())
        File(dir, "E125_N35.rd5").writeBytes(ByteArray(1))
        assertFalse(repo.hasRequired())
        File(dir, "E125_N30.rd5").writeBytes(ByteArray(1))
        assertTrue(repo.hasRequired())
    }

    @Test
    fun `list reports required first then optional with sizes`() {
        File(dir, "E125_N35.rd5").writeBytes(ByteArray(7))
        File(dir, "E130_N35.rd5").writeBytes(ByteArray(3))

        val list = repo.list()

        assertEquals(listOf("E125_N35", "E125_N30", "E130_N35", "E120_N35"), list.map { it.name })
        assertEquals(listOf(true, true, false, false), list.map { it.required })
        assertEquals(listOf(true, false, true, false), list.map { it.installed })
        assertEquals(listOf(7L, null, 3L, null), list.map { it.sizeBytes })
    }

    @Test
    fun `delete removes file and part`() {
        File(dir, "E125_N35.rd5").writeBytes(ByteArray(1))
        File(dir, "E125_N35.rd5.part").writeBytes(ByteArray(1))

        repo.delete("E125_N35")

        assertFalse(File(dir, "E125_N35.rd5").exists())
        assertFalse(File(dir, "E125_N35.rd5.part").exists())
    }

    @Test
    fun `update detected from last-modified`() = runTest {
        val local = File(dir, "E125_N35.rd5").apply {
            writeBytes(ByteArray(1))
            setLastModified(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())
        }
        server.enqueue(MockResponse.Builder().addHeader("Last-Modified", httpDate("2026-10-05T00:00:00Z")).build())

        assertTrue(repo.hasUpdate("E125_N35"))
        assertEquals("HEAD", server.takeRequest().method)

        server.enqueue(MockResponse.Builder().addHeader("Last-Modified", httpDate("2026-10-01T00:00:00Z")).build())
        assertFalse(repo.hasUpdate("E125_N35"))
        assertTrue(local.exists())
    }

    @Test
    fun `update when missing locally and false on network failure`() = runTest {
        server.enqueue(MockResponse.Builder().build())
        assertTrue(repo.hasUpdate("E125_N35"))

        File(dir, "E125_N35.rd5").writeBytes(ByteArray(1))
        server.close()
        assertFalse(repo.hasUpdate("E125_N35"))
    }
}

package com.cowork.bikerecoder.map

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class OpenFreeMapSourceTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val style = """{"version":8,"layers":[{"id":"place","layout":{"text-field":["get","name:latin"]}}]}"""

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun cacheFile() = File(dir, "style/liberty.json")
    private fun source(url: String = server.url("/styles/liberty").toString()) = OpenFreeMapSource(client, cacheFile(), url, log = { _, _ -> })

    @Test
    fun aFetchedStyleIsCachedOnDiskAndServedWhenTheNetworkIsDown() = runTest {
        server.enqueue(MockResponse.Builder().body(style).build())
        val url = server.url("/styles/liberty").toString()
        val online = source(url).styleJson()
        assertEquals(KoreanLabelStyle.apply(style), online)
        assertEquals(online, cacheFile().readText(), "the transformed style is persisted")

        server.close() // A new process without network.
        val offline = source(url).styleJson()

        assertEquals(online, offline)
    }

    @Test
    fun anHttpErrorFallsBackToTheCachedCopy() = runTest {
        server.enqueue(MockResponse.Builder().body(style).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        val first = source().styleJson()

        assertEquals(first, source().styleJson())
        assertEquals(2, server.requestCount, "the network is still tried first")
    }

    @Test
    fun aNewerStyleReplacesTheCachedCopy() = runTest {
        val newer = style.replace("place", "city")
        server.enqueue(MockResponse.Builder().body(style).build())
        server.enqueue(MockResponse.Builder().body(newer).build())

        source().styleJson()
        source().styleJson()

        assertEquals(KoreanLabelStyle.apply(newer), cacheFile().readText())
        assertFalse(File(cacheFile().parentFile, "liberty.json.tmp").exists(), "no temp file left behind")
    }

    @Test
    fun withoutNetworkAndWithoutACachedCopyTheErrorPropagates() = runTest {
        server.close()
        assertThrows<IOException> { source().styleJson() }
        assertTrue(!cacheFile().exists())
    }
}

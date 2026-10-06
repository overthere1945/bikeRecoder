package com.cowork.bikerecoder.search

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KakaoLocalClientTest {

    private lateinit var server: MockWebServer
    private lateinit var kakao: KakaoLocalClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        kakao = client("test-key")
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun client(key: String) = KakaoLocalClient(OkHttpClient(), key, server.url("/"))

    private fun json(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private val twoDocuments = """
        {"meta":{"total_count":2},"documents":[
          {"place_name":"서울시청","road_address_name":"서울 중구 세종대로 110","address_name":"서울 중구 태평로1가 31",
           "x":"126.97796919","y":"37.56668","distance":"1234"},
          {"place_name":"시청역","road_address_name":"","address_name":"서울 중구 정동 5-1",
           "x":"126.9772","y":"37.5657","distance":""}
        ]}
    """.trimIndent()

    @Test
    fun `parses keyword results`() = runTest {
        server.enqueue(json(twoDocuments))

        val result = kakao.keyword("시청", GeoPoint(37.5, 127.0))

        val places = (result as SearchResult.Ok).value
        assertEquals(2, places.size)
        assertEquals("서울시청", places[0].name)
        assertEquals("서울 중구 세종대로 110", places[0].address)
        assertEquals(GeoPoint(37.56668, 126.97796919), places[0].point)
        assertEquals(1234, places[0].distanceM)
        // road_address_name blank -> address_name; distance "" -> null
        assertEquals("서울 중구 정동 5-1", places[1].address)
        assertNull(places[1].distanceM)

        val url = server.takeRequest().url
        assertEquals("/v2/local/search/keyword.json", url.encodedPath)
        assertEquals("127.0", url.queryParameter("x"))
        assertEquals("37.5", url.queryParameter("y"))
        assertEquals("distance", url.queryParameter("sort"))
        assertEquals("15", url.queryParameter("size"))
    }

    @Test
    fun `keyword without near omits x y and sort`() = runTest {
        server.enqueue(json(twoDocuments))

        kakao.keyword("시청", null)

        val url = server.takeRequest().url
        assertEquals("시청", url.queryParameter("query"))
        assertEquals("15", url.queryParameter("size"))
        assertNull(url.queryParameter("x"))
        assertNull(url.queryParameter("y"))
        assertNull(url.queryParameter("sort"))
    }

    @Test
    fun `sends KakaoAK header`() = runTest {
        server.enqueue(json(twoDocuments))

        kakao.keyword("시청", null)

        assertEquals("KakaoAK test-key", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `query is url encoded`() = runTest {
        server.enqueue(json(twoDocuments))

        kakao.keyword("  서울 시청&역  ", null)

        val request = server.takeRequest()
        assertEquals("서울 시청&역", request.url.queryParameter("query"))
        // '&' in the query text must not have split it into extra parameters (only query + size).
        assertEquals(2, request.url.querySize)
    }

    @Test
    fun `blank query returns empty without request`() = runTest {
        val result = kakao.keyword("  ", GeoPoint(37.5, 127.0))

        assertEquals(SearchResult.Ok(emptyList<Place>()), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `401 maps to INVALID_KEY`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())

        assertEquals(SearchResult.Err(SearchError.INVALID_KEY), kakao.keyword("시청", null))
    }

    @Test
    fun `403 maps to MAP_NOT_ENABLED`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())

        assertEquals(SearchResult.Err(SearchError.MAP_NOT_ENABLED), kakao.keyword("시청", null))
    }

    @Test
    fun `500 and malformed json map to OTHER`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("{}").build())
        server.enqueue(json("not json"))

        assertEquals(SearchResult.Err(SearchError.OTHER), kakao.keyword("시청", null))
        assertEquals(SearchResult.Err(SearchError.OTHER), kakao.keyword("시청", null))
    }

    @Test
    fun `connection failure maps to NETWORK`() = runTest {
        server.close()

        assertEquals(SearchResult.Err(SearchError.NETWORK), kakao.keyword("시청", null))
        assertEquals(SearchResult.Err(SearchError.NETWORK), kakao.addressOf(GeoPoint(37.5, 127.0)))
    }

    @Test
    fun `missing key maps to KEY_MISSING`() = runTest {
        val noKey = client("")

        assertEquals(SearchResult.Err(SearchError.KEY_MISSING), noKey.keyword("시청", null))
        assertEquals(SearchResult.Err(SearchError.KEY_MISSING), noKey.addressOf(GeoPoint(37.5, 127.0)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `address prefers road address`() = runTest {
        server.enqueue(
            json(
                """{"documents":[{"road_address":{"address_name":"서울 중구 세종대로 110"},
                    "address":{"address_name":"서울 중구 태평로1가 31"}}]}""",
            ),
        )

        val result = kakao.addressOf(GeoPoint(37.56668, 126.97797))

        assertEquals(SearchResult.Ok<String?>("서울 중구 세종대로 110"), result)
        val url = server.takeRequest().url
        assertEquals("/v2/local/geo/coord2address.json", url.encodedPath)
        assertEquals("126.97797", url.queryParameter("x"))
        assertEquals("37.56668", url.queryParameter("y"))
    }

    @Test
    fun `address falls back to lot address when road address is null`() = runTest {
        server.enqueue(
            json("""{"documents":[{"road_address":null,"address":{"address_name":"서울 중구 태평로1가 31"}}]}"""),
        )

        assertEquals(SearchResult.Ok<String?>("서울 중구 태평로1가 31"), kakao.addressOf(GeoPoint(37.5, 127.0)))
    }

    @Test
    fun `address with no documents returns null`() = runTest {
        server.enqueue(json("""{"documents":[]}"""))

        assertEquals(SearchResult.Ok<String?>(null), kakao.addressOf(GeoPoint(37.5, 127.0)))
    }
}

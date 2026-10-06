package com.cowork.bikerecoder.search

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Kakao Local REST API client. Pure JVM; never throws except [CancellationException]. */
class KakaoLocalClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : PlaceSearch {

    companion object {
        const val DEFAULT_BASE_URL = "https://dapi.kakao.com"
        private const val PAGE_SIZE = "15"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private class KeywordResponse(val documents: List<KeywordDocument> = emptyList())

    @Serializable
    private class KeywordDocument(
        @SerialName("place_name") val placeName: String = "",
        @SerialName("road_address_name") val roadAddressName: String = "",
        @SerialName("address_name") val addressName: String = "",
        val x: String = "",
        val y: String = "",
        val distance: String = "",
    )

    @Serializable
    private class AddressResponse(val documents: List<AddressDocument> = emptyList())

    @Serializable
    private class AddressDocument(
        @SerialName("road_address") val roadAddress: AddressName? = null,
        val address: AddressName? = null,
    )

    @Serializable
    private class AddressName(@SerialName("address_name") val addressName: String = "")

    override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> {
        if (apiKey.isBlank()) return SearchResult.Err(SearchError.KEY_MISSING)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchResult.Ok(emptyList())
        val url = baseUrl.newBuilder()
            .addPathSegments("v2/local/search/keyword.json")
            .addQueryParameter("query", trimmed)
            .apply {
                if (near != null) {
                    addQueryParameter("x", near.lon.toString())
                    addQueryParameter("y", near.lat.toString())
                    addQueryParameter("sort", "distance")
                }
            }
            .addQueryParameter("size", PAGE_SIZE)
            .build()
        return fetch(url) { body ->
            // A document without usable coordinates is skipped instead of failing the whole list.
            json.decodeFromString<KeywordResponse>(body).documents.mapNotNull { doc ->
                val lat = doc.y.trim().toDoubleOrNull() ?: return@mapNotNull null
                val lon = doc.x.trim().toDoubleOrNull() ?: return@mapNotNull null
                Place(
                    name = doc.placeName,
                    address = doc.roadAddressName.ifBlank { doc.addressName },
                    point = GeoPoint(lat = lat, lon = lon),
                    distanceM = doc.distance.trim().toIntOrNull(),
                )
            }
        }
    }

    override suspend fun addressOf(point: GeoPoint): SearchResult<String?> {
        if (apiKey.isBlank()) return SearchResult.Err(SearchError.KEY_MISSING)
        val url = baseUrl.newBuilder()
            .addPathSegments("v2/local/geo/coord2address.json")
            .addQueryParameter("x", point.lon.toString())
            .addQueryParameter("y", point.lat.toString())
            .build()
        return fetch(url) { body ->
            val doc = json.decodeFromString<AddressResponse>(body).documents.firstOrNull()
            doc?.roadAddress?.addressName?.takeIf { it.isNotBlank() }
                ?: doc?.address?.addressName?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun <T> fetch(url: HttpUrl, parse: (String) -> T): SearchResult<T> =
        withContext(Dispatchers.IO) {
            // OkHttp rejects header values with control / non-ASCII characters (e.g. a malformed key).
            val request = try {
                Request.Builder()
                    .url(url)
                    .header("Authorization", "KakaoAK $apiKey")
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext SearchResult.Err(SearchError.INVALID_KEY)
            }
            val call = client.newCall(request)
            try {
                // Blocking socket reads are not cancellable by themselves; abort the call on coroutine cancellation.
                coroutineScope {
                    val watcher = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    try {
                        call.execute().use { response ->
                            when {
                                response.code == 401 -> SearchResult.Err(SearchError.INVALID_KEY)
                                response.code == 403 -> SearchResult.Err(SearchError.MAP_NOT_ENABLED)
                                !response.isSuccessful -> SearchResult.Err(SearchError.OTHER)
                                else -> SearchResult.Ok(parse(response.body.string()))
                            }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // An abort caused by cancellation (call.cancel -> IOException) must surface as cancellation.
                ensureActive()
                SearchResult.Err(SearchError.NETWORK)
            } catch (e: Exception) {
                // Parse errors (SerializationException, ...) carry no key material.
                SearchResult.Err(SearchError.OTHER)
            }
        }
}

package com.cowork.bikerecoder.search

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val x: String,
        val y: String,
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
            json.decodeFromString<KeywordResponse>(body).documents.map { doc ->
                Place(
                    name = doc.placeName,
                    address = doc.roadAddressName.ifBlank { doc.addressName },
                    point = GeoPoint(lat = doc.y.toDouble(), lon = doc.x.toDouble()),
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
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "KakaoAK $apiKey")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> SearchResult.Err(SearchError.INVALID_KEY)
                        response.code == 403 -> SearchResult.Err(SearchError.MAP_NOT_ENABLED)
                        !response.isSuccessful -> SearchResult.Err(SearchError.OTHER)
                        else -> SearchResult.Ok(parse(response.body.string()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                SearchResult.Err(SearchError.NETWORK)
            } catch (e: Exception) {
                // Parse errors (SerializationException, NumberFormatException, ...) carry no key material.
                SearchResult.Err(SearchError.OTHER)
            }
        }
}

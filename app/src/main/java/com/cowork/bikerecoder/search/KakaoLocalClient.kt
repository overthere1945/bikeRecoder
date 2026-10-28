package com.cowork.bikerecoder.search

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

        /** Subway, cafe, restaurant, convenience store, mart, bank, public office, attraction, culture, hospital. */
        val NEARBY_CATEGORIES = listOf("SW8", "CE7", "FD6", "CS2", "MT1", "BK9", "PO3", "AT4", "CT1", "HP8")
        const val NEARBY_RADIUS_M = 50
        const val NEARBY_MAX = 5
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

    @Serializable
    private class RegionResponse(val documents: List<RegionDocument> = emptyList())

    @Serializable
    private class RegionDocument(
        @SerialName("region_type") val regionType: String = "",
        @SerialName("region_1depth_name") val depth1: String = "",
        @SerialName("region_2depth_name") val depth2: String = "",
    )

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
                    // Accuracy, not distance: distance order lets a nearby shop bury the place the rider typed.
                    // x/y (no radius) only fill each place's distance; they do not favor nearby places.
                    addQueryParameter("sort", "accuracy")
                }
            }
            .addQueryParameter("size", PAGE_SIZE)
            .build()
        return fetch(url, ::parsePlaces)
    }

    /** The keyword and category searches answer with the same documents. */
    private fun parsePlaces(body: String): List<Place> =
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

    /**
     * The administrative region (`region_type` "H") of [point]: its (시·도, 시·군·구) names as Kakao writes them, e.g.
     * ("서울특별시", "강서구"); null when there is none (video titles, spec §3.8).
     */
    suspend fun regionOf(point: GeoPoint): SearchResult<Pair<String, String>?> {
        if (apiKey.isBlank()) return SearchResult.Err(SearchError.KEY_MISSING)
        val url = baseUrl.newBuilder()
            .addPathSegments("v2/local/geo/coord2regioncode.json")
            .addQueryParameter("x", point.lon.toString())
            .addQueryParameter("y", point.lat.toString())
            .build()
        return fetch(url) { body ->
            json.decodeFromString<RegionResponse>(body).documents.firstOrNull { it.regionType == "H" }?.let { it.depth1 to it.depth2 }
        }
    }

    /**
     * One category search per [NEARBY_CATEGORIES] code, all at once, within [NEARBY_RADIUS_M]; merged nearest first, one
     * place per name, at most [NEARBY_MAX]. A failed category is left out; with every one failed the list is empty.
     */
    override suspend fun nearby(point: GeoPoint): SearchResult<List<Place>> {
        if (apiKey.isBlank()) return SearchResult.Err(SearchError.KEY_MISSING)
        val answers = coroutineScope {
            NEARBY_CATEGORIES.map { code ->
                async {
                    val url = baseUrl.newBuilder()
                        .addPathSegments("v2/local/search/category.json")
                        .addQueryParameter("category_group_code", code)
                        .addQueryParameter("x", point.lon.toString())
                        .addQueryParameter("y", point.lat.toString())
                        .addQueryParameter("radius", NEARBY_RADIUS_M.toString())
                        .addQueryParameter("sort", "distance")
                        .addQueryParameter("size", NEARBY_MAX.toString())
                        .build()
                    fetch(url, ::parsePlaces)
                }
            }.awaitAll()
        }
        val places = answers.filterIsInstance<SearchResult.Ok<List<Place>>>()
            .flatMap { it.value }
            .filter { it.name.isNotBlank() }
            .sortedBy { it.distanceM ?: Int.MAX_VALUE }
            .distinctBy { it.name }
            .take(NEARBY_MAX)
        return SearchResult.Ok(places)
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

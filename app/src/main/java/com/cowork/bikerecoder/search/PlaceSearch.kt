package com.cowork.bikerecoder.search

import com.cowork.bikerecoder.core.model.GeoPoint

data class Place(val name: String, val address: String, val point: GeoPoint, val distanceM: Int?)

enum class SearchError(val messageKo: String) {
    KEY_MISSING("카카오 API 키가 설정되지 않았습니다. secrets.json을 확인하세요"),
    INVALID_KEY("API 키가 올바르지 않습니다. secrets.json을 확인하세요"),
    MAP_NOT_ENABLED("카카오맵 사용 설정(제품 설정)이 꺼져 있습니다"),
    NETWORK("인터넷에 연결되어 있지 않습니다. 지도를 길게 눌러 목적지를 정할 수 있습니다"),
    OTHER("검색 중 오류가 발생했습니다"),
}

sealed interface SearchResult<out T> {
    data class Ok<T>(val value: T) : SearchResult<T>
    data class Err(val error: SearchError) : SearchResult<Nothing>
}

interface PlaceSearch {
    suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>>

    /** Reverse geocode; `Ok(null)` when Kakao has no address for [point]. */
    suspend fun addressOf(point: GeoPoint): SearchResult<String?>
}

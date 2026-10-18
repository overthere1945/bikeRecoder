package com.cowork.bikerecoder.core.routing

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile

/** 경로 탐색 요청. */
data class RouteRequest(
    val start: GeoPoint,
    val startBearingDeg: Float?,
    val stops: List<GeoPoint>,
    val profile: RouteProfile,
)

/** 경로 탐색 실패 원인. */
enum class RouteFailure {
    NO_SEGMENT_DATA,
    NO_ROUTE,
    TIMEOUT,
    OTHER,
}

/** 경로 탐색 결과. */
sealed interface RouteResult {
    data class Success(val route: Route) : RouteResult
    data class Failure(val reason: RouteFailure, val detail: String) : RouteResult
}

/** 경로 탐색기. */
interface Router {
    suspend fun route(request: RouteRequest): RouteResult
}

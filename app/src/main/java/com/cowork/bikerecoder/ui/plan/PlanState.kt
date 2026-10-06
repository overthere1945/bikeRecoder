package com.cowork.bikerecoder.ui.plan

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile

/** 마지막 항목 = 목적지. [key]는 목록 안에서 안정적인 식별자(재정렬/삭제용). */
data class PlannedStop(val key: Long, val name: String, val point: GeoPoint)

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Ready(val route: Route) : RouteUiState
    data class Error(val message: String) : RouteUiState
}

data class PlanUiState(
    val stops: List<PlannedStop>,
    val profile: RouteProfile,
    val route: RouteUiState,
    val canStart: Boolean,
)

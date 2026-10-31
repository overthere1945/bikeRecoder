package com.cowork.bikerecoder.ui.plan

import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.UsualSpeed
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

/**
 * [usualSpeedMps]: 평소 속도(V1.7.0 스펙 §1.6), 읽기 전에는 기본 15 km/h.
 * [elevation]: Ready 경로의 고도 단면(고도가 없으면 null). [durationSeconds]: Ready 경로를 평소 속도로 오르막 보정해 간
 * 시간(§1.7, §2.5). 둘 다 경로가 Ready가 아니면 null.
 */
data class PlanUiState(
    val stops: List<PlannedStop>,
    val profile: RouteProfile,
    val route: RouteUiState,
    val canStart: Boolean,
    val usualSpeedMps: Double = UsualSpeed.DEFAULT_MPS,
    val elevation: ElevationProfile? = null,
    val durationSeconds: Double? = null,
)

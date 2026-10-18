package com.cowork.bikerecoder.core.trip

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile

/** 여행 종류: 당일 또는 여러 날에 걸친 다일 여행. */
enum class TripType { SINGLE_DAY, MULTI_DAY }

/** 여행 진행 상태. */
enum class TripStatus { ACTIVE, COMPLETED }

/** 여행 한 건. */
data class Trip(
    val id: Long,
    val type: TripType,
    val status: TripStatus,
    val profile: RouteProfile,
    val createdAt: Long,
    val lastActiveAt: Long,
    val completedAt: Long?,
)

/** 여행에 속한 경유지 또는 목적지. */
data class TripStop(
    val id: Long,
    val tripId: Long,
    val order: Int,
    val name: String,
    val point: GeoPoint,
    val isDestination: Boolean,
    val visitedAt: Long?,
)

/** 여행/경유지 저장소. */
interface TripStore {
    suspend fun activeTrip(): Trip?
    suspend fun trip(id: Long): Trip?
    /** id=0으로 넘기면 새 id를 반환한다. */
    suspend fun insertTrip(trip: Trip): Long
    suspend fun updateTrip(trip: Trip)
    /** order 오름차순. */
    suspend fun stops(tripId: Long): List<TripStop>
    suspend fun replaceStops(tripId: Long, stops: List<TripStop>)
    suspend fun markVisited(stopId: Long, at: Long)
}

/** 여행에 연결된 오프라인 지도 다운로드 제어. */
interface OfflineMapController {
    suspend fun deleteForTrip(tripId: Long)
    suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double)
}

/** 여행 시작 시 사용자에게 물어야 할 질문. */
sealed interface StartPrompt {
    /** 이어갈 다일 여행이 없으므로 여행 종류(당일/다일)를 묻는다. */
    data object AskType : StartPrompt

    /** 진행 중인 다일 여행이 있으므로 이어서 할지 묻는다. */
    data class AskContinue(val trip: Trip) : StartPrompt
}

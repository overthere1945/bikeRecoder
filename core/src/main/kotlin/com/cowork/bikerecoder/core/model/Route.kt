package com.cowork.bikerecoder.core.model

/** 경로 탐색 프로필. fileName은 BRouter 프로필 파일명과 대응한다. */
enum class RouteProfile(val fileName: String) {
    CYCLEWAY_FIRST("cycleway-first"),
    BALANCED("balanced"),
    SHORTEST("shortest"),
}

/** 회전 안내 종류. */
enum class TurnType {
    STRAIGHT,
    LEFT,
    SLIGHT_LEFT,
    SHARP_LEFT,
    RIGHT,
    SLIGHT_RIGHT,
    SHARP_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    U_TURN,
    ROUNDABOUT,
}

/** 경로 상의 안내 지점 하나. */
data class Instruction(
    val pointIndex: Int,
    val type: TurnType,
    val roundaboutExit: Int,
    val distanceFromStartM: Double,
)

/** 경로 요약 정보. */
data class RouteSummary(
    val totalDistanceM: Double,
    val ascentM: Int,
    val cyclewayRatio: Double,
    val profile: RouteProfile,
)

/** 탐색된 전체 경로. */
data class Route(
    val points: List<GeoPoint>,
    /** points와 같은 길이, [0]=0.0 */
    val cumulativeM: List<Double>,
    /** distanceFromStartM 오름차순 */
    val instructions: List<Instruction>,
    /** 각 Stop(경유지들…, 목적지)에 대응하는 points 인덱스 */
    val stopPointIndices: List<Int>,
    val summary: RouteSummary,
)

/** 경유지 또는 목적지. */
data class Stop(
    val id: Long,
    val name: String,
    val point: GeoPoint,
    val isDestination: Boolean,
)

package com.cowork.bikerecoder.ui.common

import com.cowork.bikerecoder.core.routing.RouteFailure

/** User-facing Korean text for a failed route computation (plan screen and navigation start). */
fun routeFailureText(reason: RouteFailure): String = when (reason) {
    RouteFailure.NO_SEGMENT_DATA -> "경로 데이터가 없습니다. 설정에서 내려받으세요"
    RouteFailure.NO_ROUTE -> "경로를 찾을 수 없습니다"
    RouteFailure.TIMEOUT -> "경로 계산 시간이 너무 깁니다. 경유지를 추가해 주세요"
    RouteFailure.OTHER -> "경로를 계산하지 못했습니다"
}

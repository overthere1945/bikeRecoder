package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.elevation.Climb
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import kotlin.math.min

/** 길안내 "앞길 고도" 띠의 안내 줄 상태(스펙 §3.2). */
sealed interface AheadState {
    /** 오르막 안: 끝까지 남은 거리. */
    data class InClimb(val remainingM: Double, val avgGradePct: Double) : AheadState

    /** [AheadOfRider.WINDOW_M] 안에서 시작하는 첫 오르막. */
    data class ClimbAhead(val inM: Double, val lengthM: Double, val avgGradePct: Double) : AheadState

    /** 앞 2 km에 오르막이 없다. */
    data object Flat : AheadState
}

/** 현재 진행 거리 기준의 앞길 상태와 띠 그래프용 단면. */
object AheadOfRider {
    const val WINDOW_M = 2_000.0

    /** [distanceAlongM]이 어떤 오르막 안이면 [AheadState.InClimb], 아니면 2 km 안 첫 오르막, 둘 다 없으면 [AheadState.Flat]. */
    fun state(climbs: List<Climb>, distanceAlongM: Double): AheadState {
        val d = distanceAlongM
        climbs.firstOrNull { d >= it.startM && d < it.endM }?.let {
            return AheadState.InClimb(remainingM = it.endM - d, avgGradePct = it.avgGradePct)
        }
        climbs.filter { it.startM > d && it.startM - d <= WINDOW_M }.minByOrNull { it.startM }?.let {
            return AheadState.ClimbAhead(inM = it.startM - d, lengthM = it.lengthM, avgGradePct = it.avgGradePct)
        }
        return AheadState.Flat
    }

    /** d부터 min(d + 2 km, 목적지)까지 잘라 낸 단면. 50 m 미만이면 null. */
    fun strip(profile: ElevationProfile, distanceAlongM: Double): ElevationProfile? =
        profile.slice(distanceAlongM, min(distanceAlongM + WINDOW_M, profile.totalM))
}

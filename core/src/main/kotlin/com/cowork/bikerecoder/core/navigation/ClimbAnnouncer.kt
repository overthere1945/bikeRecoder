package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.elevation.Climb
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint

/**
 * 오르막 음성을 말할 때를 정한다(스펙 §3.3). 오르막마다 남은 거리 `gap = startM - d`.
 * - 그 경로(단면)에서 오르막을 처음 볼 때 `gap < 300`이면 그 오르막은 말하지 않는다(경로 시작·다시 탐색 직후).
 * - 처음 볼 때 `gap ≥ 300`이었고 지금 `250 ≤ gap ≤ 350`이며 아직 말하지 않았으면 그 오르막을 돌려준다(한 번).
 * - 말한 오르막은 시작 좌표로 기억해, 다른 단면(다시 탐색한 경로)에서 시작 좌표가 50 m 안인 오르막은 말한 것으로 본다.
 * - [reset]은 길안내가 끝날 때.
 * - 회전 안내 때문에 미룬 오르막 음성이 바로 다음(약 1초) 다시 탐색으로 버려져도 그 오르막은 말한 것으로 남는다.
 *   새 경로에서 처음 보는 gap이 어차피 300 m 미만이라 말하지 않으므로 받아들인다(스펙 §3.3).
 *
 * 단면이 바뀌었는지는 객체 동일성으로 본다(경로가 바뀔 때마다 새 단면을 만든다).
 */
class ClimbAnnouncer {
    private companion object {
        const val ANNOUNCE_M = 300.0
        const val WINDOW_MIN_M = 250.0
        const val WINDOW_MAX_M = 350.0
        const val SAME_START_M = 50.0
    }

    private var profile: ElevationProfile? = null

    /** 이 단면에서 처음 볼 때 말할 수 있었는지(처음 gap ≥ 300), 오르막 시작 거리별. */
    private val eligible = HashMap<Double, Boolean>()

    /** 말한 오르막의 시작 좌표(길안내 동안 유지). */
    private val announcedStarts = ArrayList<GeoPoint>()

    fun reset() {
        profile = null
        eligible.clear()
        announcedStarts.clear()
    }

    /** 이번 위치에서 말할 오르막. 없으면 null. 돌려준 오르막은 말한 것으로 기억한다. */
    fun onProgress(climbs: List<Climb>, profile: ElevationProfile, distanceAlongM: Double): Climb? {
        if (profile !== this.profile) {
            this.profile = profile
            eligible.clear()
        }
        var result: Climb? = null
        for (climb in climbs) {
            val gap = climb.startM - distanceAlongM
            val ok = eligible.getOrPut(climb.startM) { gap >= ANNOUNCE_M }
            if (!ok || result != null || gap < WINDOW_MIN_M || gap > WINDOW_MAX_M) continue
            val start = profile.pointAt(climb.startM)
            if (announcedStarts.any { GeoMath.distanceM(it, start) <= SAME_START_M }) continue
            announcedStarts.add(start)
            result = climb
        }
        return result
    }
}

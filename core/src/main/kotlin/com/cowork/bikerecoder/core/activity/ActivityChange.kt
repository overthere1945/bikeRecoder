package com.cowork.bikerecoder.core.activity

import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RecordingSession
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.toTotals
import java.time.ZoneId

/** 기록 뒤 구간의 종류를 바꿀 때의 합계 다시 계산(스펙 §1.5). */
object ActivityChange {

    /**
     * 저장된 [points]를 [activity]의 기록 기준으로 기록 엔진에 다시 넣어 합계를 구한다. 자동 일시정지는 늘 켠다.
     * [points]는 건드리지 않는다. 엔진이 채택한 점이 2개 미만이거나 거리가 [MIN_RIDE_DISTANCE_M] 미만이면 null
     * (호출자는 기존 합계를 그대로 둔다).
     */
    fun recompute(
        startedAt: Long,
        endedAt: Long,
        points: List<TrackPoint>,
        activity: ActivityType,
        zone: ZoneId,
    ): SegmentTotals? {
        val session = RecordingSession(
            startMillis = startedAt,
            autoPause = true,
            zone = zone,
            profile = ActivityProfile.of(activity),
        )
        session.start()
        var accepted = 0
        for (p in points) {
            val fix = LocationFix(
                point = p.point,
                accuracyM = p.accuracyM,
                speedMps = p.speedMps,
                bearingDeg = null,
                timeMillis = p.timeMillis,
                altitudeM = p.altitudeM,
            )
            if (session.onFix(fix, fix.timeMillis).point != null) accepted++
        }
        val totals = session.stop(endedAt).stats.toTotals()
        if (accepted < 2 || totals.distanceM < MIN_RIDE_DISTANCE_M) return null
        return totals
    }
}

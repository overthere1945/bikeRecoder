package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RecordingSession
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.toTotals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 가져온 주행을 기록 엔진으로 다시 계산한 결과(스펙 §2.2).
 * @property startedAt 첫 점의 시간(epoch 밀리초)
 * @property endedAt 마지막 점의 시간(epoch 밀리초)
 * @property localDate [startedAt]의 Asia/Seoul 날짜
 * @property points 엔진이 기록 중 상태에서 채택한 점
 * @property totals `stop` 뒤의 합계
 */
data class ReplayedRide(
    val startedAt: Long,
    val endedAt: Long,
    val localDate: LocalDate,
    val points: List<TrackPoint>,
    val totals: SegmentTotals,
)

/**
 * GPX 점을 실시간 기록과 같은 [RecordingSession]에 시간순으로 넣어 점과 합계를 다시 계산한다.
 * 자동 일시정지는 사용자 설정과 상관없이 늘 켠다. `onTick`은 부르지 않는다(GPS 감시는 필요 없다).
 */
object RideReplay {

    /**
     * [fixes]는 시간순이고 같은 시간이 없어야 한다(`GpxReader`의 결과).
     * 비어 있거나, 채택된 점이 2개 미만이거나, 거리가 [MIN_RIDE_DISTANCE_M] 미만이면 null(호출자가 TOO_SHORT로 본다).
     */
    fun replay(fixes: List<LocationFix>, zone: ZoneId = ZoneId.of("Asia/Seoul")): ReplayedRide? {
        if (fixes.isEmpty()) return null
        val startedAt = fixes.first().timeMillis
        val endedAt = fixes.last().timeMillis

        val session = RecordingSession(startMillis = startedAt, autoPause = true, zone = zone)
        session.start()
        val points = ArrayList<TrackPoint>()
        for (fix in fixes) {
            session.onFix(fix, fix.timeMillis).point?.let(points::add)
        }
        val totals = session.stop(endedAt).stats.toTotals()

        if (points.size < 2 || totals.distanceM < MIN_RIDE_DISTANCE_M) return null
        return ReplayedRide(
            startedAt = startedAt,
            endedAt = endedAt,
            localDate = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate(),
            points = points,
            totals = totals,
        )
    }
}

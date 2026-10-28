package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.TrackPoint

/** 갤러리에서 찾은 사진 한 장. [exif]는 EXIF 위치(없으면 null). */
data class PhotoCandidate(val mediaId: Long, val takenAt: Long, val exif: GeoPoint?)

enum class PhotoPositionSource { EXIF, TRACK }

/** 경로 위에 놓인 사진. */
data class PlacedPhoto(
    val mediaId: Long,
    val takenAt: Long,
    val point: GeoPoint,
    val source: PhotoPositionSource,
)

object PhotoPlacement {
    /** 구간 앞뒤로 사진을 찾는 여유. */
    const val WINDOW_MS = 5 * 60_000L

    /** 이 간격 이하로 벌어진 이웃 점 사이에서만 보간한다. 더 벌어지면 가까운 점에 붙인다. */
    const val GAP_MS = 30_000L

    fun windowStart(startedAt: Long): Long = startedAt - WINDOW_MS

    fun windowEnd(endedAt: Long): Long = endedAt + WINDOW_MS

    /**
     * 구간에 기록 지점이 하나도 없으면([track]이 비면) EXIF 위치가 있어도 null.
     * 그렇지 않으면 EXIF 위치가 있을 때 그대로, 없으면 촬영 시각으로 [track] 위의 위치를 정한다.
     * [track]은 시간순이어야 한다.
     */
    fun place(c: PhotoCandidate, track: List<TrackPoint>): PlacedPhoto? {
        if (track.isEmpty()) return null
        c.exif?.let { return PlacedPhoto(c.mediaId, c.takenAt, it, PhotoPositionSource.EXIF) }
        return PlacedPhoto(c.mediaId, c.takenAt, pointAt(c.takenAt, track), PhotoPositionSource.TRACK)
    }

    private fun pointAt(t: Long, track: List<TrackPoint>): GeoPoint {
        if (t <= track.first().timeMillis) return track.first().point
        if (t >= track.last().timeMillis) return track.last().point
        // track[lo].time <= t < track[hi].time 가 되는 이웃을 이진 탐색으로 찾는다.
        var lo = 0
        var hi = track.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (track[mid].timeMillis <= t) lo = mid else hi = mid
        }
        val a = track[lo]
        val b = track[hi]
        val span = b.timeMillis - a.timeMillis
        if (span <= 0L) return a.point
        if (span > GAP_MS) {
            return if (t - a.timeMillis <= b.timeMillis - t) a.point else b.point
        }
        val f = (t - a.timeMillis).toDouble() / span
        return GeoPoint(
            lat = a.point.lat + f * (b.point.lat - a.point.lat),
            lon = a.point.lon + f * (b.point.lon - a.point.lon),
        )
    }
}

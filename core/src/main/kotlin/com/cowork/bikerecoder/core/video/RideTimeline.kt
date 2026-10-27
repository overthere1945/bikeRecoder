package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.TrackPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 이동 시간 축 위의 기록 지점 하나. [moveMs] = 여정 처음부터 지금까지의 이동 시간(일시정지·구간 사이 제외).
 * [segmentIndex]는 여정 안 대상 구간 순서, [dayIndex]는 날짜 순서(0 = 첫날).
 */
data class RidePoint(
    val moveMs: Long,
    val point: GeoPoint,
    val altitudeM: Double?,
    val timeMillis: Long,
    val segmentIndex: Int,
    val dayIndex: Int,
)

/**
 * 이동 시각 하나의 보간 결과. [doneIndex] = 지나온 마지막 지점의 인덱스, [fraction] = 그 지점에서 다음 지점까지 간 비율(0~1).
 */
data class Located(
    val point: GeoPoint,
    val altitudeM: Double?,
    val doneIndex: Int,
    val fraction: Double,
    val segmentIndex: Int,
    val dayIndex: Int,
)

/**
 * 대상 구간들을 이동 시간 축 하나로 이은 경로. [points]는 moveMs가 줄지 않는 순서(일시정지 뒤 지점은 앞 지점과 같은 moveMs).
 * [dayStarts] = 둘째 날부터 그날 첫 moveMs와 자막 "N일째 · M월 d일"(하루짜리면 비어 있음).
 */
class RideTimeline(
    val points: List<RidePoint>,
    val dayStarts: List<Pair<Long, String>> = emptyList(),
) {
    private val moves = LongArray(points.size) { points[it].moveMs }
    private val times = LongArray(points.size) { points[it].timeMillis }

    val totalMoveMs: Long = if (points.isEmpty()) 0L else moves.last()

    /** 이동 시각 [moveMs]의 위치·고도·지나온 인덱스(범위 밖은 처음/끝 지점에 붙인다). */
    fun at(moveMs: Long): Located {
        require(points.isNotEmpty()) { "빈 경로" }
        val i = lastAtOrBefore(moves, moveMs).coerceAtLeast(0)
        val a = points[i]
        if (i == points.lastIndex || moveMs <= a.moveMs) return a.located(i, 0.0)
        val b = points[i + 1]
        val f = (moveMs - a.moveMs).toDouble() / (b.moveMs - a.moveMs)
        val altitude = if (a.altitudeM != null && b.altitudeM != null) a.altitudeM + (b.altitudeM - a.altitudeM) * f else a.altitudeM ?: b.altitudeM
        return Located(
            point = GeoPoint(a.point.lat + (b.point.lat - a.point.lat) * f, a.point.lon + (b.point.lon - a.point.lon) * f),
            altitudeM = altitude,
            doneIndex = i,
            fraction = f,
            segmentIndex = a.segmentIndex,
            dayIndex = a.dayIndex,
        )
    }

    /**
     * 시각 [timeMillis](사진 촬영 시각 등)에 해당하는 이동 시각. 일시정지·구간 사이면 멈춘 지점의 이동 시각,
     * 첫 지점 앞이나 마지막 지점 뒤면 null.
     */
    fun moveMsAtTime(timeMillis: Long): Long? {
        if (points.isEmpty() || timeMillis < times.first() || timeMillis > times.last()) return null
        val i = lastAtOrBefore(times, timeMillis)
        val a = points[i]
        if (i == points.lastIndex) return a.moveMs
        val b = points[i + 1]
        if (b.moveMs == a.moveMs || b.timeMillis == a.timeMillis) return a.moveMs
        return a.moveMs + (b.moveMs - a.moveMs) * (timeMillis - a.timeMillis) / (b.timeMillis - a.timeMillis)
    }

    private fun RidePoint.located(index: Int, fraction: Double) =
        Located(point, altitudeM, index, fraction, segmentIndex, dayIndex)

    /** 정렬된 [values]에서 값이 [key] 이하인 마지막 인덱스(없으면 -1). */
    private fun lastAtOrBefore(values: LongArray, key: Long): Int {
        var lo = 0
        var hi = values.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] <= key) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

object RideTimelineBuilder {

    /** 점 사이 간격이 이보다 길면 일시정지로 보고 이동 시간에 넣지 않는다. */
    const val PAUSE_GAP_MS = 10_000L

    private val DATE = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)

    /**
     * 대상 구간(시작 시각 순으로 정렬)들의 지점을 이동 시간 축으로 잇는다. 구간 사이 시간과 [PAUSE_GAP_MS]를 넘는 점 간격은 빼고,
     * dayIndex는 구간 localDate 순서. 둘째 날부터 그날 첫 지점 시각을 [zone] 날짜로 적은 자막을 [RideTimeline.dayStarts]에 둔다.
     */
    fun build(segments: List<Pair<RideSegment, List<TrackPoint>>>, zone: ZoneId): RideTimeline {
        val ordered = segments.filter { it.second.isNotEmpty() }.sortedBy { it.first.startedAt }
        val dayOf = ordered.map { it.first.localDate }.distinct().sorted().withIndex().associate { it.value to it.index }
        val points = ArrayList<RidePoint>(ordered.sumOf { it.second.size })
        val dayStarts = ArrayList<Pair<Long, String>>()
        var move = 0L
        var lastDay = 0
        ordered.forEachIndexed { segmentIndex, (segment, track) ->
            val day = dayOf.getValue(segment.localDate)
            var prev: TrackPoint? = null
            for (p in track.sortedBy { it.timeMillis }) {
                prev?.let { val gap = p.timeMillis - it.timeMillis; if (gap <= PAUSE_GAP_MS) move += gap }
                if (day != lastDay) {
                    val date = Instant.ofEpochMilli(p.timeMillis).atZone(zone).format(DATE)
                    dayStarts += move to "${day + 1}일째 · $date"
                    lastDay = day
                }
                points += RidePoint(move, p.point, p.altitudeM, p.timeMillis, segmentIndex, day)
                prev = p
            }
        }
        return RideTimeline(points, dayStarts)
    }
}

package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.core.trip.TripType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val MSG_ACTIVE_TRIP_NOT_DELETABLE = "진행 중인 여행은 삭제할 수 없습니다"
const val MSG_OPEN_SEGMENT_NOT_DELETABLE = "진행 중인 구간은 삭제할 수 없습니다"
const val CONFIRM_DELETE_TRIP = "이 여행의 기록을 모두 삭제할까요?"
const val CONFIRM_DELETE_SEGMENT = "이 구간을 삭제할까요?"

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * A trip card (spec §3.5): "{date} · {당일|여러 날(N일)} · {distance} · {moving time}". The kind follows the trip's
 * type, so a multi-day trip recorded on one day reads "여러 날(1일)".
 */
fun tripCardText(s: TripRideSummary): String {
    val kind = when (s.trip.type) {
        TripType.SINGLE_DAY -> "당일"
        TripType.MULTI_DAY -> "여러 날(${s.dayCount}일)"
    }
    return "${RideFormat.date(s.firstDate)} · $kind · ${RideFormat.distance(s.distanceM)} · " +
        RideFormat.duration(s.movingTimeMs)
}

/** A segment row of the trip detail: "{start HH:mm (Seoul)} · {distance} · {moving time}". */
fun segmentRowText(segment: RideSegment): String =
    "${startTimeText(segment)} · ${RideFormat.distance(segment.totals.distanceM)} · " +
        RideFormat.duration(segment.totals.movingTimeMs)

/** The segment's start time "HH:mm" in Seoul. */
fun startTimeText(segment: RideSegment): String =
    HOUR_MINUTE.format(Instant.ofEpochMilli(segment.startedAt).atZone(SEOUL))

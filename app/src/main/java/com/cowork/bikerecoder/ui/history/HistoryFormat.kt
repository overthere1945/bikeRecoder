package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.core.trip.TripType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val MSG_ACTIVE_TRIP_NOT_DELETABLE = "진행 중인 여행은 삭제할 수 없습니다"
const val MSG_OPEN_SEGMENT_NOT_DELETABLE = "진행 중인 구간은 삭제할 수 없습니다"
const val CONFIRM_DELETE_TRIP = "이 여행의 기록을 모두 삭제할까요?"
const val CONFIRM_DELETE_SEGMENT = "이 구간을 삭제할까요?"

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private const val WEEKDAYS = "월화수목금토일"

/** "M월 d일 (E)" with the Korean weekday, e.g. "10월 25일 (일)" (the history cards and the cumulative map's rows). */
fun cardDateText(date: LocalDate): String =
    "${date.monthValue}월 ${date.dayOfMonth}일 (${WEEKDAYS[date.dayOfWeek.value - 1]})"

/**
 * A trip card's badge (spec §6.11): "당일" or "여러 날 · {N}일" (N = days with a ride). It follows the trip's type, so a
 * multi-day trip recorded on one day reads "여러 날 · 1일".
 */
fun cardBadgeText(s: TripRideSummary): String = when (s.trip.type) {
    TripType.SINGLE_DAY -> "당일"
    TripType.MULTI_DAY -> "여러 날 · ${s.dayCount}일"
}

/** A trip card's second line (spec §6.11): "{거리} · {이동 시간} · {평균}km/h", the average = distance / moving time. */
fun cardLine2Text(s: TripRideSummary): String {
    val avgMps = if (s.movingTimeMs > 0) s.distanceM / (s.movingTimeMs / 1000.0) else 0.0
    return "${RideFormat.distance(s.distanceM)} · ${RideFormat.duration(s.movingTimeMs)} · ${RideFormat.speedKmh(avgMps)}km/h"
}

/** A month's header over its cards (spec §6.11): "YYYY년 M월 · {거리} · {n}회". */
fun monthHeaderText(year: Int, month: Int, distanceM: Double, trips: Int): String =
    "${year}년 ${month}월 · ${RideFormat.distance(distanceM)} · ${trips}회"

/** A segment row of the trip detail: "{start HH:mm (Seoul)} · {distance} · {moving time}". */
fun segmentRowText(segment: RideSegment): String =
    "${startTimeText(segment)} · ${RideFormat.distance(segment.totals.distanceM)} · " +
        RideFormat.duration(segment.totals.movingTimeMs)

/** The segment's start time "HH:mm" in Seoul. */
fun startTimeText(segment: RideSegment): String =
    HOUR_MINUTE.format(Instant.ofEpochMilli(segment.startedAt).atZone(SEOUL))

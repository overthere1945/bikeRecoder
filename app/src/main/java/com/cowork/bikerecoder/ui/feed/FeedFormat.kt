package com.cowork.bikerecoder.ui.feed

import com.cowork.bikerecoder.ui.history.cardDateText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The date line of a card (V1.4.0 spec §2.3): one day "10월 25일 (일) 오후 6:12" (the time of [firstStartedAt] in [zone]),
 * several days "10월 18일~20일", across months "10월 31일~11월 1일".
 */
fun feedDateLine(first: LocalDate, last: LocalDate, firstStartedAt: Long, zone: ZoneId): String = when {
    first == last -> "${cardDateText(first)} ${clockText(firstStartedAt, zone)}"
    first.year == last.year && first.month == last.month ->
        "${first.monthValue}월 ${first.dayOfMonth}일~${last.dayOfMonth}일"
    else -> "${first.monthValue}월 ${first.dayOfMonth}일~${last.monthValue}월 ${last.dayOfMonth}일"
}

/** "오후 6:12", "오전 12:05": the 12-hour clock in [zone]. */
fun clockText(millis: Long, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val hour = time.hour
    val hour12 = if (hour % 12 == 0) 12 else hour % 12
    return "${if (hour < 12) "오전" else "오후"} $hour12:${"%02d".format(time.minute)}"
}

/** The name of the satellite picture file of a trip ([com.cowork.bikerecoder.feed.FeedThumbnails] writes `<tripId>-<key>.webp`). */
fun feedThumbName(tripId: Long, key: String): String = "$tripId-$key.webp"

/** "64.2 km": kilometres to one decimal, rounded half up. */
fun kmText(meters: Double): String =
    "${BigDecimal(meters).divide(THOUSAND, 1, RoundingMode.HALF_UP).toPlainString()} km"

/** "123 m", "1,234 m": whole metres, rounded half up, with a space like [kmText] (the V1.4.0 screens; review M1). */
fun meterText(meters: Double): String =
    "${String.format(java.util.Locale.US, "%,d", BigDecimal(meters).setScale(0, RoundingMode.HALF_UP).toLong())} m"

/** "2026년 10월 · 32.1 km · 7회": a month of the feed, [distanceM] the sum of its cards. */
fun feedMonthHeaderText(year: Int, month: Int, distanceM: Double, trips: Int): String =
    "${year}년 ${month}월 · ${kmText(distanceM)} · ${trips}회"

/** "+12.3 km" / "−4.0 km" (a real minus sign): [meters] signed, to one decimal. */
fun signedKmText(meters: Double): String {
    val rounded = BigDecimal(meters).divide(THOUSAND, 1, RoundingMode.HALF_UP)
    return (if (rounded.signum() < 0) "−" else "+") + rounded.abs().toPlainString() + " km"
}

/** "이번 주 64.2 km · 4회". */
fun weekTotalText(meters: Double, rides: Int): String = "이번 주 ${kmText(meters)} · ${rides}회"

/** "지난주 이맘때보다 +12.3 km". */
fun weekDiffText(diffMeters: Double): String = "지난주 이맘때보다 ${signedKmText(diffMeters)}"

/** "64 / 100 km", "412 / 1,000 km": the kilometres so far (rounded down) of the goal, thousands grouped. */
fun goalProgressText(meters: Double, goalKm: Int): String =
    "${groupedInt((meters / 1000.0).toInt())} / ${groupedInt(goalKm)} km"

/** 1180 → "1,180". */
fun groupedInt(value: Int): String = String.format(java.util.Locale.US, "%,d", value)

/** "평균 15.5 km/h", with " · ♥ 132" when a heart rate is known. */
fun averageLineText(avgSpeedMps: Double, avgBpm: Int?): String {
    val speed = com.cowork.bikerecoder.core.format.RideFormat.speedKmh(avgSpeedMps)
    return "평균 $speed km/h" + (avgBpm?.let { " · ♥ $it" } ?: "")
}

private val THOUSAND = BigDecimal(1_000)

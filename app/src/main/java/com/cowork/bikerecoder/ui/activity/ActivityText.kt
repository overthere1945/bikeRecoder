package com.cowork.bikerecoder.ui.activity

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The screens' and the notification's activity copy (V1.9.0): every new on-screen string of the activity types lives
 * here (the spoken ones are core `KoreanPhrases`). Elsewhere the app keeps its "주행" wording until V2.0.0.
 */
object ActivityText {

    /** Within this long before the sunset (and after it) the sunset is a warning. */
    const val SUNSET_WARNING_MS = 3_600_000L

    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

    /** The activity's name, also what a screen reader says for its badge or chip. */
    fun name(type: ActivityType): String = when (type) {
        ActivityType.BIKE -> "자전거"
        ActivityType.WALK -> "걷기"
        ActivityType.RUN -> "달리기"
        ActivityType.HIKE -> "등산"
    }

    /** The recording notification's title: "{이름} 기록 중"; a bike keeps "주행 기록 중" (spec §2.4). */
    fun recordingTitle(type: ActivityType): String =
        if (type == ActivityType.BIKE) "주행 기록 중" else "${name(type)} 기록 중"

    /** The head of the main screen's riding banner and of the band: "{이름} 기록 중" (the bike's text is unchanged). */
    fun recordingHead(type: ActivityType): String = "${name(type)} 기록 중"

    /**
     * The hike's sunset line: "일몰 17:32 · 1시간 40분 남음" (the time left as [RideFormat.duration]), from the sunset on
     * "일몰 17:32 · 지남".
     */
    fun sunsetLine(sunsetMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(sunsetMillis).atZone(zone).format(HOUR_MINUTE)
        val left = if (nowMillis >= sunsetMillis) "지남" else "${RideFormat.duration(sunsetMillis - nowMillis)} 남음"
        return "일몰 $time · $left"
    }

    /** The sunset is at most an hour away, or already past (the line's warning colour). */
    fun sunsetWarning(sunsetMillis: Long, nowMillis: Long): Boolean = sunsetMillis - nowMillis <= SUNSET_WARNING_MS

    /** The notification's sunset part, from an hour before the sunset on: "일몰까지 25분", then "일몰 지남"; else null. */
    fun sunsetNotice(sunsetMillis: Long, nowMillis: Long): String? = when {
        !sunsetWarning(sunsetMillis, nowMillis) -> null
        nowMillis >= sunsetMillis -> "일몰 지남"
        else -> "일몰까지 ${RideFormat.duration(sunsetMillis - nowMillis)}"
    }
}

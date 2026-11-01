package com.cowork.bikerecoder.ui.activity

import com.cowork.bikerecoder.core.activity.ActivityFormat
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.ui.feed.kmText
import com.cowork.bikerecoder.ui.feed.meterText
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

    /**
     * The three numbers of a record card (spec §2.2) as (label, value), in the activity's order: a bike 거리·이동 시간·평균
     * 속도, a run 거리·평균 페이스·이동 시간, a walk 거리·이동 시간·평균 페이스, a hike 산행 시간·누적 상승·최고 고도.
     * Values use the feed card's formats ("5.0 km", "27분", "5'32"/km"); the hike time is the [elapsedMs] (rests included).
     */
    fun cardCells(
        activity: ActivityType,
        distanceM: Double,
        movingTimeMs: Long,
        elapsedMs: Long,
        ascentM: Double,
        maxAltitudeM: Double?,
    ): List<Pair<String, String>> {
        val distance = "거리" to kmText(distanceM)
        val moving = "이동 시간" to RideFormat.duration(movingTimeMs)
        val pace = "평균 페이스" to "${ActivityFormat.paceOf(distanceM, movingTimeMs)}/km"
        return when (activity) {
            ActivityType.BIKE -> {
                val mps = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0
                listOf(distance, moving, "평균 속도" to "${RideFormat.speedKmh(mps)} km/h")
            }
            ActivityType.RUN -> listOf(distance, pace, moving)
            ActivityType.WALK -> listOf(distance, moving, pace)
            ActivityType.HIKE -> listOf(
                "산행 시간" to RideFormat.duration(elapsedMs),
                "누적 상승" to meterText(ascentM),
                "최고 고도" to (maxAltitudeM?.let(::meterText) ?: "--"),
            )
        }
    }
}

package com.cowork.bikerecoder.ui.activity

import com.cowork.bikerecoder.core.activity.ActivityFormat
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.stats.RideRecordKind
import com.cowork.bikerecoder.ui.feed.kmText
import com.cowork.bikerecoder.ui.feed.meterText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The screens' and the notification's activity copy (V1.9.0): every new on-screen string of the activity types lives
 * here (the spoken ones are core `KoreanPhrases`). Elsewhere the app keeps its "주행" wording until V2.0.0.
 */
object ActivityText {

    /** Within this long before the sunset (and after it) the sunset is a warning. */
    const val SUNSET_WARNING_MS = 3_600_000L

    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

    /** The segment screen's [⋮] item (spec §1.5). */
    const val CHANGE_ACTIVITY = "활동 종류 바꾸기"

    /** The change dialog's note: points not saved during a pause are not in the recording to recompute from. */
    const val CHANGE_NOTE = "일시정지 중에 저장되지 않은 위치는 되살릴 수 없습니다."

    const val CHANGE_CONFIRM = "바꾸기"
    const val CHANGE_CANCEL = "취소"

    /** The toast when the new activity could not be saved. */
    const val CHANGE_FAILED = "활동 종류를 바꾸지 못했습니다"

    /** Where the big map's legend would be on a trip of several activities: their colour scales differ (spec §3.5). */
    const val MIXED_COLOR_NOTE = "종류마다 색 기준이 다릅니다"

    /** Labels of the pace, hike time and total ascent cells: one definition for the cards, tables and the stats tab. */
    const val LABEL_AVG_PACE = "평균 페이스"
    const val LABEL_HIKE_TIME = "산행 시간"
    const val LABEL_TOTAL_ASCENT = "누적 상승"
    const val LABEL_MAX_ALTITUDE = "최고 고도"

    /** Labels of the recording screen's and the summary's new cells (V1.9.0 spec §3.2). */
    const val LABEL_CURRENT_PACE = "현재 페이스"
    const val LABEL_LAST_KM = "직전 1 km"
    const val LABEL_AVG_SPEED = "평균 속도"
    const val LABEL_TOP_SPEED = "최고 속도"
    const val LABEL_CURRENT_ALTITUDE = "현재 고도"
    const val LABEL_TOTAL_DESCENT = "누적 하강"

    /** The video's number band shows the pace of a run or walk under this label (the cards say "평균 페이스"). */
    const val LABEL_PACE = "페이스"

    /** The closing card's run cell: the pace of the fastest 1 km (spec V1.9.0 §5.1). */
    const val LABEL_FASTEST_KM = "가장 빠른 1 km"

    /** The title of the stats tab's per-activity goal rows under [전체]. */
    const val GOALS_TITLE = "목표"

    /** The goal row bars' labels: the weekly and the yearly goal. */
    const val GOAL_WEEK_SHORT = "주"
    const val GOAL_YEAR_SHORT = "연"

    /** The stats tab's [전체] chip (null in the settings). */
    const val STATS_ALL = "전체"

    /** The stats tab's count cell word for a chip (spec §4.1): "라이딩 N회", "걷기 N회", "달리기 N회", "등산 N회", "활동 N회". */
    fun statsRidesWord(chip: ActivityType?): String = when (chip) {
        null -> "활동"
        ActivityType.BIKE -> "라이딩"
        ActivityType.WALK -> "걷기"
        ActivityType.RUN -> "달리기"
        ActivityType.HIKE -> "등산"
    }

    /**
     * A running best effort's time (spec §4.3): under an hour minutes'seconds" (`24'51"`), from an hour on
     * `1:52'10"`. Seconds are rounded to the nearest.
     */
    fun effortTime(timeMs: Long): String {
        val total = (timeMs + 500) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d'%02d\"", h, m, s) else String.format(Locale.ROOT, "%d'%02d\"", m, s)
    }

    /**
     * A personal record's name in the stats tab's list (spec §4.3, V1.9.1 §C) and, with "신기록", in the summary's new-record
     * line. Each kind belongs to one activity and is worded after it; a bike keeps its wording.
     */
    fun recordLabel(kind: RideRecordKind): String = when (kind) {
        RideRecordKind.LONGEST_DISTANCE -> "가장 긴 주행"
        RideRecordKind.LONGEST_TIME -> "가장 오래 탄 주행"
        RideRecordKind.MOST_ASCENT -> "가장 많이 오른 주행"
        RideRecordKind.FASTEST_AVG -> "가장 빠른 평균 속도"
        RideRecordKind.RUN_LONGEST_DISTANCE -> "가장 긴 달리기"
        RideRecordKind.RUN_BEST_1K, RideRecordKind.RUN_BEST_5K, RideRecordKind.RUN_BEST_10K, RideRecordKind.RUN_BEST_HALF ->
            "${kind.effort!!.label} 최고 기록"
        RideRecordKind.WALK_LONGEST_DISTANCE -> "가장 긴 걷기"
        RideRecordKind.WALK_LONGEST_TIME -> "가장 오래 걸은 기록"
        RideRecordKind.HIKE_MOST_ASCENT -> "가장 많이 오른 산행"
        RideRecordKind.HIKE_HIGHEST -> "가장 높이 오른 산행"
        RideRecordKind.HIKE_LONGEST_ELAPSED -> "가장 긴 산행 시간"
    }

    /** The 개인 기록 row of the best week under [chip] (null = [전체]): "가장 많이 탄 주", "산행을 가장 많이 한 주", "가장 활발했던 주". */
    fun bestWeekLabel(chip: ActivityType?): String = bestPeriodLabel(chip, "주")

    /** The row of the best month: "가장 많이 탄 달", "가장 많이 걸은 달", ... "가장 활발했던 달". */
    fun bestMonthLabel(chip: ActivityType?): String = bestPeriodLabel(chip, "달")

    private fun bestPeriodLabel(chip: ActivityType?, unit: String): String = when (chip) {
        ActivityType.BIKE -> "가장 많이 탄 $unit"
        ActivityType.WALK -> "가장 많이 걸은 $unit"
        ActivityType.RUN -> "가장 많이 달린 $unit"
        ActivityType.HIKE -> "산행을 가장 많이 한 $unit"
        null -> "가장 활발했던 $unit"
    }

    /** The 개인 기록 row of the longest streak: "가장 긴 연속 주행", "가장 긴 연속 걷기", ... "가장 긴 연속 활동". */
    fun longestStreakLabel(chip: ActivityType?): String = "가장 긴 연속 " + when (chip) {
        ActivityType.BIKE -> "주행"
        ActivityType.WALK -> "걷기"
        ActivityType.RUN -> "달리기"
        ActivityType.HIKE -> "산행"
        null -> "활동"
    }

    /** The streak line while this week is counted: "4주 연속 주행 중", "걷는 중", "달리는 중", "산행 중", "활동 중". */
    fun streakRunning(weeks: Int, chip: ActivityType?): String = "${weeks}주 연속 " + when (chip) {
        ActivityType.BIKE -> "주행 중"
        ActivityType.WALK -> "걷는 중"
        ActivityType.RUN -> "달리는 중"
        ActivityType.HIKE -> "산행 중"
        null -> "활동 중"
    }

    /** The streak line before this week counts: "3주 연속 · 이번 주에 타면 4주" ("걸으면", "달리면", "산에 오르면", "활동하면"). */
    fun streakPending(weeks: Int, chip: ActivityType?): String = "${weeks}주 연속 · 이번 주에 ${streakCondition(chip)} ${weeks + 1}주"

    /** The streak line without one: "이번 주에 타면 연속 주행이 시작됩니다"; every other chip "... 연속 기록이 시작됩니다". */
    fun streakNone(chip: ActivityType?): String =
        "이번 주에 ${streakCondition(chip)} 연속 ${if (chip == ActivityType.BIKE) "주행" else "기록"}이 시작됩니다"

    private fun streakCondition(chip: ActivityType?): String = when (chip) {
        ActivityType.BIKE -> "타면"
        ActivityType.WALK -> "걸으면"
        ActivityType.RUN -> "달리면"
        ActivityType.HIKE -> "산에 오르면"
        null -> "활동하면"
    }

    /** The cumulative map's empty text for a bike, unchanged since V1.2.0. */
    const val CUMULATIVE_EMPTY_BIKE = "아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다"

    /** The cumulative map's empty text under every other chip and [전체]. */
    const val CUMULATIVE_EMPTY_ANY = "아직 기록이 없습니다. 활동을 기록하면 여기에 쌓입니다"

    /** What the cumulative map says when no ride is recorded under [chip] (null = [전체]). */
    fun cumulativeEmpty(chip: ActivityType?): String =
        if (chip == ActivityType.BIKE) CUMULATIVE_EMPTY_BIKE else CUMULATIVE_EMPTY_ANY

    /** The feed's empty state: the screen shows every activity. */
    const val FEED_EMPTY = "첫 활동을 기록해 보세요"

    /** The trip detail screen's title: a single day "활동 기록", a trip of several days "여행 기록". */
    fun tripDetailTitle(multiDay: Boolean): String = if (multiDay) "여행 기록" else "활동 기록"

    // The activity type button on the map screen (V1.9.1 §A).
    /** The sheet's title. */
    const val TYPE_SHEET_TITLE = "활동 종류"

    /** What a screen reader says for the type button: "활동 종류, 걷기. 눌러서 바꾸기". */
    fun typeButtonDescription(type: ActivityType): String = "$TYPE_SHEET_TITLE, ${name(type)}. 눌러서 바꾸기"

    /**
     * The summary's new-record line (spec §4.3): "🏆 가장 긴 주행 신기록", "🏆 5 km 최고 기록 24'51\"", "🏆 가장 높이 오른 등산".
     * [effortMs] is the best effort's time (only for the run bests).
     */
    fun newRecordLine(kind: RideRecordKind, effortMs: Long?): String = when (kind) {
        RideRecordKind.LONGEST_DISTANCE, RideRecordKind.LONGEST_TIME, RideRecordKind.MOST_ASCENT, RideRecordKind.FASTEST_AVG,
        RideRecordKind.RUN_LONGEST_DISTANCE, RideRecordKind.WALK_LONGEST_DISTANCE, RideRecordKind.WALK_LONGEST_TIME,
        -> "🏆 ${recordLabel(kind)} 신기록"
        RideRecordKind.RUN_BEST_1K, RideRecordKind.RUN_BEST_5K, RideRecordKind.RUN_BEST_10K, RideRecordKind.RUN_BEST_HALF ->
            "🏆 ${recordLabel(kind)}" + (effortMs?.let { " ${effortTime(it)}" } ?: "")
        RideRecordKind.HIKE_MOST_ASCENT, RideRecordKind.HIKE_HIGHEST, RideRecordKind.HIKE_LONGEST_ELAPSED -> "🏆 ${recordLabel(kind)}"
    }

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
     * The pace is of [paceDistanceM] in [paceMovingTimeMs]: on a mixed trip the dominant activity's rides only. A bike's
     * speed reads "18.0 km/h" as on the feed; [spacedSpeedUnit] = false gives "18.0km/h", the recording summary's
     * V1.8.0 style, so that screen writes its speeds one way.
     */
    fun cardCells(
        activity: ActivityType,
        distanceM: Double,
        movingTimeMs: Long,
        elapsedMs: Long,
        ascentM: Double,
        maxAltitudeM: Double?,
        paceDistanceM: Double = distanceM,
        paceMovingTimeMs: Long = movingTimeMs,
        spacedSpeedUnit: Boolean = true,
    ): List<Pair<String, String>> {
        val distance = "거리" to kmText(distanceM)
        val moving = "이동 시간" to RideFormat.duration(movingTimeMs)
        val pace = LABEL_AVG_PACE to "${ActivityFormat.paceOf(paceDistanceM, paceMovingTimeMs)}/km"
        return when (activity) {
            ActivityType.BIKE -> {
                val mps = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0
                val unit = if (spacedSpeedUnit) " km/h" else "km/h"
                listOf(distance, moving, LABEL_AVG_SPEED to "${RideFormat.speedKmh(mps)}$unit")
            }
            ActivityType.RUN -> listOf(distance, pace, moving)
            ActivityType.WALK -> listOf(distance, moving, pace)
            ActivityType.HIKE -> listOf(
                LABEL_HIKE_TIME to RideFormat.duration(elapsedMs),
                LABEL_TOTAL_ASCENT to meterText(ascentM),
                LABEL_MAX_ALTITUDE to (maxAltitudeM?.let(::meterText) ?: "--"),
            )
        }
    }

    /**
     * The rows of a segment screen's totals table (spec §3.2) as (label, value) cells, one list per row: a bike 거리·이동
     * 시간 / 경과 시간·평균/최고 / 누적 상승·하강 / 최고·최저 고도 (as it was); a run or walk shows "평균 페이스" and
     * "평균 속도" instead of "평균/최고"; a hike starts with "산행 시간" (the elapsed time, rests included) and has no
     * separate 경과 시간.
     */
    fun segmentTotalRows(activity: ActivityType, totals: SegmentTotals): List<List<Pair<String, String>>> {
        val distance = "거리" to RideFormat.distance(totals.distanceM)
        val moving = "이동 시간" to RideFormat.clock(totals.movingTimeMs)
        val elapsed = "경과 시간" to RideFormat.clock(totals.elapsedMs)
        val climb = listOf(LABEL_TOTAL_ASCENT to RideFormat.altitude(totals.ascentM), LABEL_TOTAL_DESCENT to RideFormat.altitude(totals.descentM))
        val altitude = listOf(LABEL_MAX_ALTITUDE to RideFormat.altitude(totals.maxAltitudeM), "최저 고도" to RideFormat.altitude(totals.minAltitudeM))
        return when (activity) {
            ActivityType.BIKE -> listOf(listOf(distance, moving), listOf(elapsed, averageMax(totals)), climb, altitude)
            ActivityType.RUN, ActivityType.WALK ->
                listOf(listOf(distance, moving, elapsed), paceAndSpeed(totals), climb, altitude)
            ActivityType.HIKE -> listOf(
                listOf(LABEL_HIKE_TIME to RideFormat.clock(totals.elapsedMs)),
                listOf(distance, moving),
                listOf(averageMax(totals)),
                climb,
                altitude,
            )
        }
    }

    /**
     * The rows of a trip screen's totals table (spec §3.2) for [activity] (a trip of several activities passes
     * [ActivityType.BIKE]): 거리·이동 시간 / 평균/최고 / 누적 상승·하강·최고 고도; a run or walk has "평균 페이스" and
     * "평균 속도" in the second row; a hike has "산행 시간" (the elapsed times summed, as [RideFormat.duration]) as the
     * first row.
     */
    fun tripTotalRows(activity: ActivityType, totals: SegmentTotals): List<List<Pair<String, String>>> {
        val distanceAndMoving = listOf(
            "거리" to RideFormat.distance(totals.distanceM),
            "이동 시간" to RideFormat.clock(totals.movingTimeMs),
        )
        val altitude = listOf(
            LABEL_TOTAL_ASCENT to RideFormat.altitude(totals.ascentM),
            LABEL_TOTAL_DESCENT to RideFormat.altitude(totals.descentM),
            LABEL_MAX_ALTITUDE to RideFormat.altitude(totals.maxAltitudeM),
        )
        return when (activity) {
            ActivityType.BIKE -> listOf(distanceAndMoving, listOf(averageMax(totals)), altitude)
            ActivityType.RUN, ActivityType.WALK -> listOf(distanceAndMoving, paceAndSpeed(totals), altitude)
            ActivityType.HIKE -> listOf(
                listOf(LABEL_HIKE_TIME to RideFormat.duration(totals.elapsedMs)),
                distanceAndMoving,
                listOf(averageMax(totals)),
                altitude,
            )
        }
    }

    private fun averageMax(totals: SegmentTotals): Pair<String, String> =
        "평균/최고" to "${RideFormat.speedKmh(totals.avgSpeedMps)} / ${RideFormat.speedKmh(totals.maxSpeedMps)}km/h"

    private fun paceAndSpeed(totals: SegmentTotals): List<Pair<String, String>> = listOf(
        LABEL_AVG_PACE to "${ActivityFormat.paceOf(totals.distanceM, totals.movingTimeMs)}/km",
        LABEL_AVG_SPEED to "${RideFormat.speedKmh(totals.avgSpeedMps)}km/h",
    )

    /**
     * The share text's numbers line of a run or walk (spec §5.2): "5.0 km · 27분 · 평균 5'32\"/km" ([kmText], then
     * [RideFormat.duration]). Without a usable pace (no moving time, or slower than 60 minutes a kilometre) the average is left out.
     * The pace is of [paceDistanceM] in [paceMovingTimeMs]: on a mixed trip the dominant activity's rides only.
     */
    fun shareRunWalkNumbers(
        distanceM: Double,
        movingTimeMs: Long,
        paceDistanceM: Double = distanceM,
        paceMovingTimeMs: Long = movingTimeMs,
    ): String {
        val pace = ActivityFormat.paceOf(paceDistanceM, paceMovingTimeMs)
        return buildList {
            add(kmText(distanceM))
            add(RideFormat.duration(movingTimeMs))
            if (pace != ActivityFormat.NO_PACE) add("평균 $pace/km")
        }.joinToString(" · ")
    }

    /**
     * The share text's numbers line of a hike (spec §5.2): "산행 3시간 20분 · 상승 520 m · 최고 836 m"; the highest point
     * is left out when unknown.
     */
    fun shareHikeNumbers(elapsedMs: Long, ascentM: Double, maxAltitudeM: Double?): String = buildList {
        add("산행 ${RideFormat.duration(elapsedMs)}")
        add("상승 ${meterText(ascentM)}")
        if (maxAltitudeM != null) add("최고 ${meterText(maxAltitudeM)}")
    }.joinToString(" · ")

    /** The settings row of an activity's default hashtags (spec §5.2): "기본 해시태그 · 걷기". */
    fun hashtagsRowLabel(type: ActivityType): String = "$HASHTAGS_ROW · ${name(type)}"

    /** The word before the activity's name on the settings' hashtag rows and their dialog. */
    const val HASHTAGS_ROW = "기본 해시태그"
}

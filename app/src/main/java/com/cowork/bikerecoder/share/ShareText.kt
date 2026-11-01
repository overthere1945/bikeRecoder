package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.video.RideTitle
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.feed.kmText
import com.cowork.bikerecoder.ui.feed.meterText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/** Hashtags used when the setting has never been saved (spec §3.5). An empty saved value means "none". */
const val DEFAULT_SHARE_HASHTAGS = "#자전거 #라이딩"

/** The first values of the other activities' default hashtags (V1.9.0 spec §5.2); the bike's is [DEFAULT_SHARE_HASHTAGS]. */
const val DEFAULT_SHARE_HASHTAGS_WALK = "#걷기 #산책"
const val DEFAULT_SHARE_HASHTAGS_RUN = "#러닝 #달리기"
const val DEFAULT_SHARE_HASHTAGS_HIKE = "#등산 #하이킹"

/** The default hashtags of [type] while its setting has never been saved. */
fun defaultShareHashtags(type: ActivityType): String = when (type) {
    ActivityType.BIKE -> DEFAULT_SHARE_HASHTAGS
    ActivityType.WALK -> DEFAULT_SHARE_HASHTAGS_WALK
    ActivityType.RUN -> DEFAULT_SHARE_HASHTAGS_RUN
    ActivityType.HIKE -> DEFAULT_SHARE_HASHTAGS_HIKE
}

/** The longest share text the sheet's box accepts. */
const val MAX_SHARE_TEXT = 2000

/** The longest input of the default-hashtags setting. */
const val MAX_HASHTAGS_INPUT = 100

/** The longest e-mail subject, in code points. */
const val MAX_SUBJECT = 80

/**
 * What the auto share text is built from (spec §3.1): the trip's shown title, first and last riding day (Asia/Seoul),
 * and the sums of its finished rides (10 m or longer, same as the cards). [activity] is the trip's dominant activity,
 * which picks the numbers line (V1.9.0 spec §5.2); [elapsedMs] is the hike segments' elapsed time and [maxAltitudeM] the
 * highest point (a hike's line). [paceDistanceM] and [paceMovingTimeMs] are the sums of the [activity]'s rides only, the
 * pace of a run's or walk's line (a mixed trip's other rides do not move it); null: [distanceM] and [movingTimeMs].
 */
data class ShareFacts(
    val title: String,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val distanceM: Double,
    val movingTimeMs: Long,
    val ascentM: Double,
    val regionName: String?,
    val activity: ActivityType = ActivityType.BIKE,
    val elapsedMs: Long = 0L,
    val maxAltitudeM: Double? = null,
    val paceDistanceM: Double? = null,
    val paceMovingTimeMs: Long? = null,
)

/**
 * Line 2: "2026년 10월 25일 (일)" for one day, "2026년 10월 25일 (일) ~ 27일 (화) · 3일" for several, with the month
 * (and the year) repeated on the end date only when they change.
 */
fun shareDateLine(first: LocalDate, last: LocalDate): String {
    if (first == last) return "${RideFormat.date(first)} (${weekday(first)})"
    val end = when {
        first.year != last.year -> "${RideFormat.date(last)} (${weekday(last)})"
        first.month != last.month -> "${last.monthValue}월 ${last.dayOfMonth}일 (${weekday(last)})"
        else -> "${last.dayOfMonth}일 (${weekday(last)})"
    }
    val days = ChronoUnit.DAYS.between(first, last) + 1
    return "${RideFormat.date(first)} (${weekday(first)}) ~ $end · ${days}일"
}

private fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/** "서울 강서구" → ["#서울", "#강서구"]; no region → empty. Words that clean to nothing are dropped. */
fun regionTags(regionName: String?): List<String> =
    regionName.orEmpty().split(WHITESPACE).mapNotNull { cleanTag(it) }

/** A single-word hashtag: "#" plus the letters, digits and "_" of [word]; null when nothing is left. */
fun cleanTag(word: String): String? {
    val body = StringBuilder()
    word.codePoints().filter { Character.isLetterOrDigit(it) || it == '_'.code }.forEach { body.appendCodePoint(it) }
    return if (body.isEmpty()) null else "#$body"
}

/** The default-hashtags setting as saved: every word cleaned and given a "#", repeats dropped, joined by one space. */
fun normalizeHashtags(input: String): String =
    input.split(WHITESPACE).mapNotNull { cleanTag(it) }.distinct().joinToString(" ")

/**
 * The auto share text (spec §3.1), up to four lines: title, date, numbers, hashtags (default, region, then place; repeats
 * keep the first). A bike's numbers leave out the ascent under 1 m and the average when there is no moving time; a run's or
 * walk's are distance, time and average pace, a hike's the hike time, ascent and highest point ([ActivityText]). No
 * hashtags means no fourth line. The caller passes the [defaultTags] of the dominant activity and the [placeTags]
 * already formed ("#낙동강자전거길", spec §4.5).
 */
fun autoShareText(facts: ShareFacts, defaultTags: String, placeTags: List<String> = emptyList()): String {
    val numbers = when (facts.activity) {
        ActivityType.BIKE -> buildList {
            add(kmText(facts.distanceM))
            add(RideFormat.duration(facts.movingTimeMs))
            if (facts.ascentM >= 1.0) add("상승 고도 ${meterText(facts.ascentM)}")
            if (facts.movingTimeMs > 0) add("평균 ${averageKmh(facts.distanceM, facts.movingTimeMs)} km/h")
        }.joinToString(" · ")
        ActivityType.RUN, ActivityType.WALK -> ActivityText.shareRunWalkNumbers(
            facts.distanceM,
            facts.movingTimeMs,
            facts.paceDistanceM ?: facts.distanceM,
            facts.paceMovingTimeMs ?: facts.movingTimeMs,
        )
        ActivityType.HIKE -> ActivityText.shareHikeNumbers(facts.elapsedMs, facts.ascentM, facts.maxAltitudeM)
    }
    val tags = (normalizeHashtags(defaultTags).split(WHITESPACE).filter { it.isNotEmpty() } + regionTags(facts.regionName) + placeTags)
        .distinct()
        .joinToString(" ")
    return buildList {
        add(facts.title)
        add(shareDateLine(facts.firstDate, facts.lastDate))
        add(numbers)
        if (tags.isNotEmpty()) add(tags)
    }.joinToString("\n")
}

/** km/h to one decimal, rounded half up: 32 140 m in 1.75 h → "18.4". */
private fun averageKmh(distanceM: Double, movingTimeMs: Long): String =
    BigDecimal(distanceM).multiply(BigDecimal(3_600))
        .divide(BigDecimal(movingTimeMs), 1, RoundingMode.HALF_UP).toPlainString()

/** The mood chips of the sheet (spec §3.3); the hashtag of a chip is its [word]. */
enum class Mood(val emoji: String, val word: String) {
    SANGKWAE("😊", "상쾌"),
    SINNAM("😄", "신남"),
    PUDEUT("💪", "뿌듯"),
    YEOYU("😌", "여유"),
    HIMDEUM("🥵", "힘듦"),
    PIGON("😴", "피곤"),
    BI("🌧", "비"),
    CHOEGO("🔥", "최고");

    val tag get() = "#$word"
}

/** The chip is on while its hashtag stands as a word in [text]; "#상쾌함" is not "#상쾌". */
fun moodOn(text: String, mood: Mood): Boolean = tagOn(text, mood.tag)

/** Flips the chip: [toggleTag] of its hashtag. */
fun toggleMood(text: String, mood: Mood): String = toggleTag(text, mood.tag)

/** [tag] stands as a whole word in [text]; "#북한산둘레길" is not "#북한산". */
fun tagOn(text: String, tag: String): Boolean = tagRegex(tag).containsMatchIn(text)

/**
 * Flips [tag]: off → appends " [tag]" to the last line that starts with "#" (a new line at the end when there is none);
 * on → removes every such word and squeezes the spaces it leaves, dropping a line that ends up empty.
 */
fun toggleTag(text: String, tag: String): String {
    if (!tagOn(text, tag)) return withTag(text, tag)
    val regex = tagRegex(tag)
    val kept = text.split("\n").mapNotNull { line ->
        if (!regex.containsMatchIn(line)) return@mapNotNull line
        line.replace(regex, "").replace(SPACES, " ").trim().ifEmpty { null }
    }
    return kept.joinToString("\n")
}

private fun withTag(text: String, tag: String): String {
    val lines = text.split("\n")
    val index = lines.indexOfLast { it.trimStart().startsWith("#") }
    if (index >= 0) {
        return lines.mapIndexed { i, line -> if (i == index) line.trimEnd() + " " + tag else line }.joinToString("\n")
    }
    return when {
        text.isEmpty() -> tag
        text.endsWith("\n") -> text + tag
        else -> "$text\n$tag"
    }
}

private fun tagRegex(tag: String) = Regex("(^|\\s)${Regex.escape(tag)}(?=\\s|$)")

/** The record the text was written against (spec §3.4): "finished rides:metres", e.g. "3:32140". */
fun shareStamp(rides: Int, distanceM: Double): String = "$rides:${distanceM.roundToLong()}"

/** The e-mail subject: the first non-blank line of [text], cut at [MAX_SUBJECT] code points. */
fun emailSubject(text: String): String =
    RideTitle.truncateCodePoints(text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(), MAX_SUBJECT)

private val WHITESPACE = Regex("\\s+")
private val SPACES = Regex("[ \\t]{2,}")

package com.cowork.bikerecoder.core.export

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.TrackPoint
import java.time.LocalDate
import java.util.Locale

/**
 * 내보낼 여행의 구간 하나(스펙 §4.3·§4.4).
 * @property startedAt 구간 시작 시각(epoch 밀리초). 구간은 이 값 순서로 넘겨 준다.
 * @property localDate 구간 시작의 Asia/Seoul 날짜. KML 이름 `{yyyy-MM-dd} 주행`에 쓴다.
 * @property points 기록 점(시간순)
 * @property heartRate 이 구간의 심박 표본(없으면 빈 목록)
 * @property activity 구간의 활동 종류. GPX `<type>`과 KML 구간 이름에 쓴다.
 */
data class ExportSegment(
    val startedAt: Long,
    val localDate: LocalDate,
    val points: List<TrackPoint>,
    val heartRate: List<HeartRateSample>,
    val activity: ActivityType = ActivityType.BIKE,
)

/** GPX·KML 안에 들어가는 글(스펙 §5). */
object ExportLabels {
    const val START = "출발"
    const val END = "도착"
    const val ROUTE_PREFIX = "bikeRecoder 경로 · "

    /** 경로 파일의 이름. [destinationName]은 목적지 이름. */
    fun routeName(destinationName: String): String = ROUTE_PREFIX + destinationName

    /** KML 구간 이름 `{yyyy-MM-dd} {주행|걷기|달리기|등산}`(스펙 §2.3). 자전거는 "주행" 그대로. */
    fun rideName(date: LocalDate, activity: ActivityType = ActivityType.BIKE): String = when (activity) {
        ActivityType.BIKE -> "$date 주행"
        ActivityType.WALK -> "$date 걷기"
        ActivityType.RUN -> "$date 달리기"
        ActivityType.HIKE -> "$date 등산"
    }
}

/** 로케일과 상관없이 점(.)을 소수점으로 쓰는 숫자 글자 만들기. */
internal fun formatFixed(value: Double, decimals: Int): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", value)
    // 반올림하면 0이 되는 음수는 "-0.0"이 아니라 "0.0"으로 쓴다.
    return if (text.startsWith("-") && text.none { it in '1'..'9' }) text.substring(1) else text
}

/** 좌표 소수 7자리. */
internal fun formatCoordinate(value: Double): String = formatFixed(value, 7)

/** 고도 소수 1자리. */
internal fun formatElevation(value: Double): String = formatFixed(value, 1)

/** XML 글자·속성값 이스케이프(`& < > " '`). XML 1.0에서 허용되지 않는 제어 문자는 뺀다. */
internal fun escapeXml(text: String): String {
    val out = StringBuilder(text.length + 8)
    for (ch in text) {
        when (ch) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&apos;")
            else -> if (ch >= ' ' || ch == '\t' || ch == '\n' || ch == '\r') out.append(ch)
        }
    }
    return out.toString()
}

package com.cowork.bikerecoder.core.activity

import com.cowork.bikerecoder.core.recording.RideSegment

/**
 * 구간 하나의 활동 종류(V1.9.0). 종류가 없는 곳(기존 행, 길안내 기록, 모르는 GPX `<type>`)은 모두 [BIKE]다.
 *
 * @property stored DB에 저장하는 문자열. 이름 그대로이며 바꾸면 기존 기록을 읽지 못한다.
 * @property gpxType GPX `<type>`에 쓰는 문자열.
 */
enum class ActivityType(val gpxType: String) {
    BIKE("cycling"),
    WALK("walking"),
    RUN("running"),
    HIKE("hiking"),
    ;

    val stored: String get() = name

    companion object {
        /** 저장 문자열을 종류로 읽는다. 모르는 문자열·null은 [BIKE]. */
        fun fromStored(s: String?): ActivityType = entries.firstOrNull { it.name == s } ?: BIKE
    }
}

/**
 * 여행 하나의 종류 구성.
 * @property dominant 대표 종류: 이동 시간이 가장 긴 한 구간의 종류. 같으면 `startedAt`이 앞선 구간을 따른다.
 * @property badges 배지 순서: 대표 종류가 먼저, 나머지는 종류별 이동 시간 합이 큰 순서(같으면 먼저 나온 종류). 최대 [MAX_BADGES]개.
 */
data class ActivityMix(val dominant: ActivityType, val badges: List<ActivityType>) {
    companion object {
        const val MAX_BADGES = 3

        /** 구간이 없으면 `ActivityMix(BIKE, listOf(BIKE))`. */
        fun of(segments: List<RideSegment>): ActivityMix {
            if (segments.isEmpty()) return ActivityMix(ActivityType.BIKE, listOf(ActivityType.BIKE))
            val byStart = segments.sortedBy { it.startedAt }
            // maxByOrNull은 같으면 앞의 것을 고른다.
            val dominant = byStart.maxByOrNull { it.totals.movingTimeMs }!!.activity
            val sums = LinkedHashMap<ActivityType, Long>()
            for (s in byStart) sums[s.activity] = (sums[s.activity] ?: 0L) + s.totals.movingTimeMs
            val others = sums.entries.filter { it.key != dominant }.sortedByDescending { it.value }.map { it.key }
            return ActivityMix(dominant, (listOf(dominant) + others).take(MAX_BADGES))
        }
    }
}

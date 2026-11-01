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

        /**
         * 구간이 없으면 `ActivityMix(BIKE, listOf(BIKE))`. 빈 구간([RideSegment.isEmpty], 거리 0 이하)은 건너뛴다.
         * 모든 구간이 비었으면 전부를 쓴다.
         */
        fun of(segments: List<RideSegment>): ActivityMix {
            val counted = segments.filterNot { it.isEmpty }.ifEmpty { segments }
            return ofParts(counted.sortedBy { it.startedAt }.map { it.activity to it.totals.movingTimeMs })
        }

        /**
         * 구간을 (종류, 이동 시간 ms)로만 아는 곳(피드 카드)을 위한 [of]. [parts]는 이미 `startedAt` 순이고 빈 구간은 없다.
         * 비어 있으면 `ActivityMix(BIKE, listOf(BIKE))`.
         */
        fun ofParts(parts: List<Pair<ActivityType, Long>>): ActivityMix {
            if (parts.isEmpty()) return ActivityMix(ActivityType.BIKE, listOf(ActivityType.BIKE))
            // maxByOrNull은 같으면 앞의 것을 고른다.
            val dominant = parts.maxByOrNull { it.second }!!.first
            val sums = LinkedHashMap<ActivityType, Long>()
            for ((type, movingMs) in parts) sums[type] = (sums[type] ?: 0L) + movingMs
            val others = sums.entries.filter { it.key != dominant }.sortedByDescending { it.value }.map { it.key }
            return ActivityMix(dominant, (listOf(dominant) + others).take(MAX_BADGES))
        }
    }
}

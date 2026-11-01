package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.RouteSimplifier
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.TrackPoint

/** 시작~끝 시간 구간(epoch 밀리초). */
data class TimeSpan(val start: Long, val end: Long)

/** 확인 목록에서 주행 하나의 상태. */
sealed interface RideStatus {
    /** 가져올 수 있는 새 주행. */
    data object New : RideStatus

    /** 앱에 이미 있는 구간과 시간이 겹친다. */
    data object OverlapsExisting : RideStatus

    /** 목록에서 먼저 나온 주행과 시간이 겹친다. */
    data object DuplicateInList : RideStatus

    /** 읽을 수 없는 파일·항목. */
    data class Unreadable(val problem: ImportProblem) : RideStatus
}

/** 가져오기 판단 규칙(스펙 §3.2). */
object ImportRules {

    /**
     * `<type>` 글자(대소문자 무시, 앞뒤 공백 무시)로 활동 종류를 정한다(스펙 V1.9.0 §5.3). 순서대로 첫 규칙을 따른다.
     * 1. 자전거: 정확히 "1"(Strava 숫자)이거나 `bik`·`cycl`·`ride`를 포함 (V1.8.0 규칙 그대로. "trail_running"은 여기에 걸리지 않는다)
     * 2. 달리기: `run` 포함
     * 3. 걷기: `walk` 포함
     * 4. 등산: `hik` 포함
     * 5. 그 밖(null·공백·숫자·모르는 글자)은 자전거. Relive 파일에는 `<type>`이 없을 수 있다.
     */
    fun activityOf(type: String?): ActivityType {
        val t = type?.trim()?.lowercase()
        if (t.isNullOrEmpty()) return ActivityType.BIKE
        return when {
            t == "1" || "bik" in t || "cycl" in t || "ride" in t -> ActivityType.BIKE
            "run" in t -> ActivityType.RUN
            "walk" in t -> ActivityType.WALK
            "hik" in t -> ActivityType.HIKE
            else -> ActivityType.BIKE
        }
    }

    /** 두 구간이 겹치는가. 끝과 시작이 딱 맞닿는 것은 겹침이 아니다. */
    fun overlaps(a: TimeSpan, b: TimeSpan): Boolean = a.start < b.end && b.start < a.end

    /** 앱에 있는 구간의 시간 구간. 기록 중인 구간(`endedAt == null`)은 끝을 [now]로 본다. */
    fun existingSpans(segments: List<RideSegment>, now: Long): List<TimeSpan> =
        segments.map { TimeSpan(it.startedAt, it.endedAt ?: now) }

    /**
     * 읽은 순서대로 주어진 [spans]에서 상태를 정한다. null 시간 구간은 읽을 수 없는 항목이며
     * 중복 비교에서 빠진다(호출자가 자기 사유로 [RideStatus.Unreadable]을 다시 채운다. 여기서는 UNREADABLE).
     *
     * 우선순위: 기존과 겹침 → 목록에서 앞선 읽힌 주행과 겹침(앞선 것이 어떤 상태든 먼저 나온 것이 우선) → 새 주행.
     * 활동 종류는 상태에 영향을 주지 않는다(어떤 종류든 가져올 수 있다).
     */
    fun statuses(spans: List<TimeSpan?>, existing: List<TimeSpan>): List<RideStatus> {
        val seen = ArrayList<TimeSpan>(spans.size)
        return spans.map { span ->
            if (span == null) return@map RideStatus.Unreadable(ImportProblem.UNREADABLE)
            val status = when {
                existing.any { overlaps(span, it) } -> RideStatus.OverlapsExisting
                seen.any { overlaps(span, it) } -> RideStatus.DuplicateInList
                else -> RideStatus.New
            }
            seen += span
            status
        }
    }

    /** 확인 목록의 작은 경로 그림용 단순화 점. */
    fun previewPoints(points: List<TrackPoint>): List<GeoPoint> = RouteSimplifier.simplify(points.map { it.point })
}

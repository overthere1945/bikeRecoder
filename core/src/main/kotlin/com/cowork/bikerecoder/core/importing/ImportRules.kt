package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.RouteSimplifier
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.TrackPoint

/** GPX `<type>`으로 본 활동 종류(스펙 §3.2). */
enum class Activity { CYCLING, RUNNING, WALKING, HIKING, OTHER }

/** 시작~끝 시간 구간(epoch 밀리초). */
data class TimeSpan(val start: Long, val end: Long)

/** 확인 목록에서 주행 하나의 상태. */
sealed interface RideStatus {
    /** 가져올 수 있는 새 주행. */
    data object New : RideStatus

    /** 자전거가 아닌 활동. */
    data object NotCycling : RideStatus

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
     * `<type>` 글자(대소문자 무시)로 활동을 정한다. null·공백은 자전거로 본다(Relive 파일에는 없을 수 있다).
     * `bik`·`cycl`·`ride`를 포함하거나 정확히 "1"(Strava 숫자)이면 자전거.
     */
    fun activityOf(type: String?): Activity {
        val t = type?.trim()?.lowercase()
        if (t.isNullOrEmpty()) return Activity.CYCLING
        return when {
            t == "1" || "bik" in t || "cycl" in t || "ride" in t -> Activity.CYCLING
            "run" in t -> Activity.RUNNING
            "walk" in t -> Activity.WALKING
            "hik" in t -> Activity.HIKING
            else -> Activity.OTHER
        }
    }

    /** 두 구간이 겹치는가. 끝과 시작이 딱 맞닿는 것은 겹침이 아니다. */
    fun overlaps(a: TimeSpan, b: TimeSpan): Boolean = a.start < b.end && b.start < a.end

    /** 앱에 있는 구간의 시간 구간. 기록 중인 구간(`endedAt == null`)은 끝을 [now]로 본다. */
    fun existingSpans(segments: List<RideSegment>, now: Long): List<TimeSpan> =
        segments.map { TimeSpan(it.startedAt, it.endedAt ?: now) }

    /**
     * 읽은 순서대로 주어진 [spans]·[activities]에서 상태를 정한다. null 시간 구간은 읽을 수 없는 항목이며
     * 중복 비교에서 빠진다(호출자가 자기 사유로 [RideStatus.Unreadable]을 다시 채운다. 여기서는 UNREADABLE).
     * 활동이 null이면 자전거로 본다.
     *
     * 우선순위: 기존과 겹침 → 목록에서 앞선 읽힌 주행과 겹침(앞선 것이 어떤 상태든 먼저 나온 것이 우선) →
     * 자전거 아님 → 새 주행.
     */
    fun statuses(spans: List<TimeSpan?>, activities: List<Activity?>, existing: List<TimeSpan>): List<RideStatus> {
        require(spans.size == activities.size) { "spans와 activities의 길이가 다릅니다" }
        val seen = ArrayList<TimeSpan>(spans.size)
        return spans.mapIndexed { i, span ->
            if (span == null) return@mapIndexed RideStatus.Unreadable(ImportProblem.UNREADABLE)
            val status = when {
                existing.any { overlaps(span, it) } -> RideStatus.OverlapsExisting
                seen.any { overlaps(span, it) } -> RideStatus.DuplicateInList
                (activities[i] ?: Activity.CYCLING) != Activity.CYCLING -> RideStatus.NotCycling
                else -> RideStatus.New
            }
            seen += span
            status
        }
    }

    /** 확인 목록의 작은 경로 그림용 단순화 점. */
    fun previewPoints(points: List<TrackPoint>): List<GeoPoint> = RouteSimplifier.simplify(points.map { it.point })
}

package com.cowork.bikerecoder.core.video

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 영상에 넣을 사진 하나: [moveMs] = 촬영 시각에 해당하는 이동 시각. */
data class PhotoCue(val mediaId: Long, val moveMs: Long, val takenAt: Long)

enum class SceneKind { INTRO_OVERVIEW, INTRO_DESCENT, RIDE, PHOTO, OUTRO }

/**
 * 프레임 한 장의 계획. [sceneFrame] = 그 장면 안 장 번호(0부터), [sceneFrames] = 그 장면의 장 수,
 * [sceneProgress] = 그 장면 안 진행(첫 장 0, 마지막 장 1, = [sceneFrame] / ([sceneFrames] − 1)),
 * [cardFrom] = 끝 장면에서 요약 카드가 나타나기 시작하는 장(끝 장면에서만 쓴다), [caption] = 여러 날 자막,
 * [fade] = 끝의 어두워짐(0~1), [reuseMap] = 앞 프레임의 지도 스냅샷을 그대로 써도 됨(카메라가 멈춰 있는 끝 유지 구간만),
 * [alongM] = 그 프레임의 경로 거리(시작 0, 사진 = 사진 묶음의 거리, 끝 = 전체), [speed] = 이동 장면의 빠르기 ÷ 평균(그 밖에는 1).
 */
data class FramePlan(
    val index: Int,
    val kind: SceneKind,
    val moveMs: Long,
    val sceneProgress: Float,
    val sceneFrame: Int,
    val sceneFrames: Int,
    val photo: PhotoCue?,
    val caption: String?,
    val fade: Float,
    val reuseMap: Boolean,
    val cardFrom: Int = VideoTimeline.OUTRO_CARD_FROM,
    val alongM: Double = 0.0,
    val speed: Float = 1f,
)

/** 영상 시간 배분(스펙 §2): 시작 3초 → 주행(사진 장면 포함) → 끝 4초. */
object VideoTimeline {

    // 장면 길이는 초로 적고, 장 수는 [framesOf]로 얻는다(FPS가 바뀌어도 시간이 같다).
    const val OVERVIEW_SECONDS = 1.2 // 하늘 위
    const val DESCENT_SECONDS = 1.8 // 내려가기
    const val PHOTO_SECONDS = 2.0 // 사진 장면
    const val OUTRO_SECONDS = 4.0
    const val OUTRO_RISE_SECONDS = 1.5 // 끝 0~1.5초: 마지막 주행 카메라에서 끝 카메라로 솟아오른다
    const val OUTRO_CARD_FROM_SECONDS = 1.2 // 끝 장면에서 요약 카드가 나타나기 시작하는 때
    const val OUTRO_CARD_SECONDS = 0.25 // 요약 카드가 나타나는 데 걸리는 시간
    const val FADE_SECONDS = 1.0 // 끝 마지막 1초
    const val CAPTION_SECONDS = 1.5

    val OVERVIEW_FRAMES = framesOf(OVERVIEW_SECONDS)
    val DESCENT_FRAMES = framesOf(DESCENT_SECONDS)
    val PHOTO_FRAMES = framesOf(PHOTO_SECONDS)
    val OUTRO_FRAMES = framesOf(OUTRO_SECONDS)
    val OUTRO_RISE_FRAMES = framesOf(OUTRO_RISE_SECONDS)
    val OUTRO_CARD_FROM = framesOf(OUTRO_CARD_FROM_SECONDS)
    val OUTRO_CARD_FRAMES = framesOf(OUTRO_CARD_SECONDS)
    val FADE_FRAMES = framesOf(FADE_SECONDS)
    val CAPTION_FRAMES = framesOf(CAPTION_SECONDS)
    const val SAME_PLACE_MS = 1_000L

    private const val INTRO_SECONDS = OVERVIEW_SECONDS + DESCENT_SECONDS

    private val photoOrder = compareBy<PhotoCue>({ it.moveMs }, { it.takenAt })

    /**
     * [cues]가 [cap]보다 많으면 경로 거리 축 [0, [totalM]]을 cap칸으로 나눠 칸마다 칸 가운데에 가장 가까운 사진을 고른다(스펙 §1.3).
     * 사진의 거리는 [alongOf]. 사진이 없는 칸은 그 칸 가운데에 가장 가까운 남은 사진으로 채운다(사진이 몰려 있어도 cap장). 결과는 시간순.
     */
    fun choosePhotos(cues: List<PhotoCue>, cap: Int, totalM: Double, alongOf: (PhotoCue) -> Double): List<PhotoCue> {
        val sorted = cues.sortedWith(photoOrder)
        if (sorted.size <= cap) return sorted
        if (cap <= 0) return emptyList()
        val along = DoubleArray(sorted.size) { alongOf(sorted[it]) }
        val binM = totalM / cap
        fun center(bin: Int) = binM * (bin + 0.5)
        fun binOf(i: Int) = if (binM <= 0.0) 0 else floor(along[i] / binM).toInt().coerceIn(0, cap - 1)

        val picked = IntArray(cap) { -1 }
        sorted.indices.forEach { i ->
            val bin = binOf(i)
            val current = picked[bin]
            if (current < 0 || abs(along[i] - center(bin)) < abs(along[current] - center(bin))) picked[bin] = i
        }
        val used = picked.filter { it >= 0 }.toMutableSet()
        for (bin in 0 until cap) {
            if (picked[bin] >= 0) continue
            val nearest = sorted.indices.filter { it !in used }.minBy { abs(along[it] - center(bin)) }
            picked[bin] = nearest
            used += nearest
        }
        return used.map { sorted[it] }.sortedWith(photoOrder)
    }

    /** [choosePhotos]를 [timeline]의 경로 거리로(사진의 거리 = 촬영 이동 시각의 경로 거리). */
    fun choosePhotos(cues: List<PhotoCue>, cap: Int, timeline: RideTimeline): List<PhotoCue> =
        choosePhotos(cues, cap, timeline.routeDistance.total, alongFunction(timeline))

    private fun alongFunction(timeline: RideTimeline): (PhotoCue) -> Double =
        if (timeline.points.isEmpty()) { _ -> 0.0 } else { cue -> timeline.routeDistance.atMove(cue.moveMs) }

    /**
     * 이동 장면의 빠르기 곡선과 영상에 넣을 사진(스펙 §1, §2). 기본 길이 R0 = [VideoPace.rideSeconds](경로 거리 km),
     * 사진 수 = min(길이의 사진 상한, max(1, ⌊R0 / 4⌋), 사진 수)(사진 끄면 0)를 거리 축에서 고르고,
     * 이동 장면 길이 R = min(R0, 최대 길이 − 3 − 4 − 2 × 사진 수, 이동 시간)(최소 min(4, 이동 시간); 늘이지 않는다).
     * 이동 시간이 0이면 R = 0이라 [VideoPace.profile]을 부르지 않고 길이 0의 고른 곡선을 돌려준다(이동 장면 프레임이 없을 때만 쓰인다).
     */
    internal fun paceFor(timeline: RideTimeline, photos: List<PhotoCue>, options: VideoOptions): Pair<PaceProfile, List<PhotoCue>> {
        val route = timeline.routeDistance
        val r0 = VideoPace.rideSeconds(route.total / 1000.0)
        val count = if (options.photos) min(options.length.photoCap, max(1, floor(r0 / 4).toInt())) else 0
        val alongOf = alongFunction(timeline)
        val chosen = choosePhotos(photos, count, route.total, alongOf)
        val moving = timeline.totalMoveMs / 1000.0
        val seconds = min(min(r0, options.length.seconds - INTRO_SECONDS - OUTRO_SECONDS - PHOTO_SECONDS * chosen.size), moving)
            .coerceAtLeast(min(VideoPace.MIN_RIDE_SECONDS, moving))
        if (seconds <= 0.0) return PaceProfile.uniform(0.0, route.total) to chosen
        val pace = VideoPace.profile(timeline, seconds, chosen.map(alongOf), VideoHighlights.alongM(timeline))
        return pace to chosen
    }

    /**
     * 프레임 계획(스펙 §1, §2). 시작 = 하늘 위 [OVERVIEW_FRAMES] + 내려가기 [DESCENT_FRAMES](합 3초), 끝 = [OUTRO_FRAMES](솟아오르기
     * [OUTRO_RISE_FRAMES] 뒤 유지, 유지 구간만 [FramePlan.reuseMap]). 이동 장면은 [paceFor]의 길이 R·빠르기 곡선을 따른다:
     * 장 수 n = framesOf(R), 장 k의 시각 = k / (n − 1) × R(n이 1이면 0), 그 장의 [FramePlan.alongM] = 곡선의 경로 거리, [FramePlan.moveMs] = 그 거리의
     * 이동 시각, [FramePlan.speed] = 곡선의 빠르기 배수. 사진(이동 시각 ±1초 묶음)은 이동 장면의 거리가 그 사진의 거리에 처음 닿을 때
     * 멈춘 채 2초씩, [dayStarts]의 자막은 거리가 그날 첫 지점의 거리에 처음 닿을 때부터 이동 장면 [CAPTION_FRAMES](1.5초)장에 붙는다.
     * 이동 시간이 0이면 이동 장면이 없고 시작·사진·끝만 나온다.
     */
    fun plan(timeline: RideTimeline, photos: List<PhotoCue>, options: VideoOptions, dayStarts: List<Pair<Long, String>>): List<FramePlan> {
        val route = timeline.routeDistance
        val totalMove = timeline.totalMoveMs
        val (pace, chosen) = paceFor(timeline, photos, options)
        val rideFrames = framesOf(pace.seconds)
        val alongOf = alongFunction(timeline)

        val out = ArrayList<FramePlan>(OVERVIEW_FRAMES + DESCENT_FRAMES + rideFrames + PHOTO_FRAMES * chosen.size + OUTRO_FRAMES)
        fun emit(kind: SceneKind, moveMs: Long, k: Int, of: Int, photo: PhotoCue? = null, caption: String? = null,
                 fade: Float = 0f, reuseMap: Boolean = false, alongM: Double = 0.0, speed: Float = 1f) {
            val progress = if (of > 1) k.toFloat() / (of - 1) else 0f
            out += FramePlan(out.size, kind, moveMs, progress, k, of, photo, caption, fade, reuseMap, alongM = alongM, speed = speed)
        }

        // 하늘 위 장면은 카메라가 돌고 사진 장면은 카메라가 사진 위치를 돌아 매 장 새 지도다.
        repeat(OVERVIEW_FRAMES) { emit(SceneKind.INTRO_OVERVIEW, 0, it, OVERVIEW_FRAMES) }
        repeat(DESCENT_FRAMES) { emit(SceneKind.INTRO_DESCENT, 0, it, DESCENT_FRAMES) }

        val groups = groupSamePlace(chosen)
        var nextGroup = 0
        fun emitGroup(group: List<PhotoCue>) {
            val anchor = group.first().moveMs
            val along = alongOf(group.first())
            for (cue in group) repeat(PHOTO_FRAMES) { emit(SceneKind.PHOTO, anchor, it, PHOTO_FRAMES, photo = cue, alongM = along) }
        }

        val days = dayStarts.sortedBy { it.first }.map { (moveMs, text) -> route.atMove(moveMs) to text }
        var nextDay = 0
        var caption: String? = null
        var captionLeft = 0
        for (k in 0 until rideFrames) {
            val second = if (rideFrames > 1) k.toDouble() / (rideFrames - 1) * pace.seconds else 0.0
            val along = pace.alongAt(second)
            while (nextGroup < groups.size && alongOf(groups[nextGroup].first()) <= along) emitGroup(groups[nextGroup++])
            while (nextDay < days.size && days[nextDay].first <= along) {
                caption = days[nextDay++].second
                captionLeft = CAPTION_FRAMES
            }
            val text = if (captionLeft > 0) caption.also { captionLeft-- } else null
            emit(SceneKind.RIDE, route.moveAt(along), k, rideFrames, caption = text, alongM = along, speed = pace.speedFactor(second).toFloat())
        }
        while (nextGroup < groups.size) emitGroup(groups[nextGroup++])

        repeat(OUTRO_FRAMES) {
            val fadeK = it - (OUTRO_FRAMES - FADE_FRAMES)
            val fade = if (fadeK < 0) 0f else fadeK.toFloat() / (FADE_FRAMES - 1)
            emit(SceneKind.OUTRO, totalMove, it, OUTRO_FRAMES, fade = fade, reuseMap = it >= OUTRO_RISE_FRAMES, alongM = route.total)
        }
        return out
    }

    /** 시간순 사진을 첫 사진 moveMs ±1초 안끼리 묶는다(묶음은 이어서 나온다). */
    private fun groupSamePlace(sorted: List<PhotoCue>): List<List<PhotoCue>> {
        val groups = ArrayList<MutableList<PhotoCue>>()
        for (cue in sorted) {
            val last = groups.lastOrNull()
            if (last != null && cue.moveMs - last.first().moveMs <= SAME_PLACE_MS) last += cue else groups += mutableListOf(cue)
        }
        return groups
    }
}

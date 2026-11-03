package com.cowork.bikerecoder.core.video

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** 영상에 넣을 사진 하나: [moveMs] = 촬영 시각에 해당하는 이동 시각. */
data class PhotoCue(val mediaId: Long, val moveMs: Long, val takenAt: Long)

enum class SceneKind { INTRO_OVERVIEW, INTRO_DESCENT, RIDE, PHOTO, OUTRO }

/**
 * 프레임 한 장의 계획. [sceneFrame] = 그 장면 안 장 번호(0부터), [sceneFrames] = 그 장면의 장 수,
 * [sceneProgress] = 그 장면 안 진행(첫 장 0, 마지막 장 1, = [sceneFrame] / ([sceneFrames] − 1)),
 * [cardFrom] = 끝 장면에서 요약 카드가 나타나기 시작하는 장(끝 장면에서만 쓴다), [caption] = 여러 날 자막,
 * [fade] = 끝의 어두워짐(0~1), [reuseMap] = 앞 프레임의 지도 스냅샷을 그대로 써도 됨(카메라가 멈춰 있는 끝 유지 구간만).
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
     * [cues]가 [cap]보다 많으면 이동 시간 축 [0, totalMoveMs]를 cap칸으로 나눠 칸마다 칸 가운데에 가장 가까운 사진을 고른다.
     * 사진이 없는 칸은 그 칸 가운데에 가장 가까운 남은 사진으로 채운다(사진이 몰려 있어도 cap장). 결과는 시간순.
     */
    fun choosePhotos(cues: List<PhotoCue>, cap: Int, totalMoveMs: Long): List<PhotoCue> {
        val sorted = cues.sortedWith(photoOrder)
        if (sorted.size <= cap) return sorted
        if (cap <= 0) return emptyList()
        val binMs = totalMoveMs.toDouble() / cap
        fun center(bin: Int) = binMs * (bin + 0.5)
        fun binOf(cue: PhotoCue) = if (binMs <= 0.0) 0 else floor(cue.moveMs / binMs).toInt().coerceIn(0, cap - 1)

        val picked = IntArray(cap) { -1 }
        sorted.forEachIndexed { i, cue ->
            val bin = binOf(cue)
            val current = picked[bin]
            if (current < 0 || abs(cue.moveMs - center(bin)) < abs(sorted[current].moveMs - center(bin))) picked[bin] = i
        }
        val used = picked.filter { it >= 0 }.toMutableSet()
        for (bin in 0 until cap) {
            if (picked[bin] >= 0) continue
            val nearest = sorted.indices.filter { it !in used }.minBy { abs(sorted[it].moveMs - center(bin)) }
            picked[bin] = nearest
            used += nearest
        }
        return used.map { sorted[it] }.sortedWith(photoOrder)
    }

    /**
     * 프레임 계획. 시작 = 하늘 위 [OVERVIEW_FRAMES] + 내려가기 [DESCENT_FRAMES](합 3초), 끝 = [OUTRO_FRAMES](솟아오르기
     * [OUTRO_RISE_FRAMES] 뒤 유지, 유지 구간만 [FramePlan.reuseMap]). 주행 장면 R = T − 3 − 4 − 2 × 사진 수(이동 시간이 더 짧으면 이동 시간, 늘이지 않음),
     * 주행 프레임 i의 moveMs = i / 주행 프레임 수 × 이동 시간. 사진(±1초 묶음)은 그 moveMs에 이르면 멈춘 채 2초씩,
     * [dayStarts]의 자막은 그 moveMs부터 주행 프레임 [CAPTION_FRAMES](1.5초)장에 붙는다.
     */
    fun plan(timeline: RideTimeline, photos: List<PhotoCue>, options: VideoOptions, dayStarts: List<Pair<Long, String>>): List<FramePlan> {
        val totalMove = timeline.totalMoveMs
        val chosen = if (options.photos) choosePhotos(photos, options.length.photoCap, totalMove) else emptyList()
        val n = chosen.size
        val rideSeconds = min(options.length.seconds - INTRO_SECONDS - OUTRO_SECONDS - PHOTO_SECONDS * n, totalMove / 1000.0)
            .coerceAtLeast(0.0)
        val frameCount = ((INTRO_SECONDS + rideSeconds + PHOTO_SECONDS * n + OUTRO_SECONDS) * FPS).roundToInt()
        val rideFrames = frameCount - OVERVIEW_FRAMES - DESCENT_FRAMES - OUTRO_FRAMES - PHOTO_FRAMES * n

        val out = ArrayList<FramePlan>(frameCount)
        fun emit(kind: SceneKind, moveMs: Long, k: Int, of: Int, photo: PhotoCue? = null, caption: String? = null,
                 fade: Float = 0f, reuseMap: Boolean = false) {
            val progress = if (of > 1) k.toFloat() / (of - 1) else 0f
            out += FramePlan(out.size, kind, moveMs, progress, k, of, photo, caption, fade, reuseMap)
        }

        // 하늘 위 장면은 카메라가 돌고 사진 장면은 카메라가 사진 위치를 돌아 매 장 새 지도다.
        repeat(OVERVIEW_FRAMES) { emit(SceneKind.INTRO_OVERVIEW, 0, it, OVERVIEW_FRAMES) }
        repeat(DESCENT_FRAMES) { emit(SceneKind.INTRO_DESCENT, 0, it, DESCENT_FRAMES) }

        val groups = groupSamePlace(chosen)
        var nextGroup = 0
        fun emitGroup(group: List<PhotoCue>) {
            val anchor = group.first().moveMs
            for (cue in group) repeat(PHOTO_FRAMES) { emit(SceneKind.PHOTO, anchor, it, PHOTO_FRAMES, photo = cue) }
        }

        val days = dayStarts.sortedBy { it.first }
        var nextDay = 0
        var caption: String? = null
        var captionLeft = 0
        for (i in 0 until rideFrames) {
            val moveMs = i * totalMove / rideFrames
            while (nextGroup < groups.size && groups[nextGroup].first().moveMs <= moveMs) emitGroup(groups[nextGroup++])
            while (nextDay < days.size && days[nextDay].first <= moveMs) {
                caption = days[nextDay++].second
                captionLeft = CAPTION_FRAMES
            }
            val text = if (captionLeft > 0) caption.also { captionLeft-- } else null
            emit(SceneKind.RIDE, moveMs, i, rideFrames, caption = text)
        }
        while (nextGroup < groups.size) emitGroup(groups[nextGroup++])

        repeat(OUTRO_FRAMES) {
            val fadeK = it - (OUTRO_FRAMES - FADE_FRAMES)
            val fade = if (fadeK < 0) 0f else fadeK.toFloat() / (FADE_FRAMES - 1)
            emit(SceneKind.OUTRO, totalMove, it, OUTRO_FRAMES, fade = fade, reuseMap = it >= OUTRO_RISE_FRAMES)
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

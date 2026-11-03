package com.cowork.bikerecoder.core.video

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

/** 박에 맞춘 계획(스펙 §5.3): [frames]와, 영상 시각 0에 틀 음악 파일 시각 [musicStartUs]. */
data class Planned(val frames: List<FramePlan>, val musicStartUs: Long)

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
        val (pace, chosen) = paceFor(timeline, photos, options)
        val groups = groupSamePlace(chosen)
        val alongOf = alongFunction(timeline)
        val slots = rideSlots(pace, framesOf(pace.seconds), groups.map { alongOf(it.first()) })
        return emit(timeline, pace, groups, slots, dayStarts, PHOTO_FRAMES, OUTRO_FRAMES, OUTRO_CARD_FROM)
    }

    /** 사진 장면은 적어도 이만큼의 박(스펙 §5.3). */
    const val PHOTO_MIN_BEATS = 3

    /** 박에 맞춘 끝 장면은 적어도 이만큼(초). */
    const val OUTRO_MIN_SECONDS = 3.0

    /** 박에 맞추려고 늘이거나 줄인 이동 장면 한 토막은 원래 길이의 이 비율 이상이어야 한다(스펙 §5.3). */
    const val PIECE_MIN_RATIO = 0.7

    private val INTRO_US = (INTRO_SECONDS * 1_000_000).roundToLong()

    /**
     * 장면을 박에 맞춘 계획(스펙 §5.3). [beats]가 null이면 [plan] 그대로, 음악 시작점 0.
     *
     * - 음악 시작점 [Planned.musicStartUs] = `첫 박 + m·마디 − 3초` 가운데 `max(0, 첫 박 − 반 마디)` 이상인 가장 작은 값: 이동 장면 시작(3초)이 마디에 온다.
     *   영상 시각 t의 음악 시각은 t + musicStartUs.
     * - 사진 장면 = 2초에 가장 가까운 박 수(최소 [PHOTO_MIN_BEATS]박), 끝 장면 = 4초에 가장 가까운 마디 수(최소 [OUTRO_MIN_SECONDS]초),
     *   끝 카드는 끝 장면 시작 뒤 1.2초에 가장 가까운 박에 나타난다. 솟아오르기 1.5초·어두워짐 마지막 1초는 그대로.
     * - 박 길이 때문에 최대 길이([VideoLength.seconds])를 넘으면 먼저 이동 장면을 고르게 줄인다([PaceProfile.warped]).
     * - 사진 장면 시작은 가장 가까운 박으로(반 박 이하), 끝 장면 시작은 가장 가까운 마디로 옮긴다. 끝 장면은 마디에 와야 영상이 마디 끝에서
     *   끝나므로 옮기는 양의 상한이 반 마디다(판정 P8). 앞에서부터 차례로, 그 전환 바로 앞 이동 장면 토막을 늘이거나 줄여 옮긴다. 뒤 격자점이
     *   최대 길이에만 막히면 앞 격자점으로 당긴다(판정 P9). 토막이 원래 길이의 [PIECE_MIN_RATIO] 미만이 되거나 영상이 최대 길이를 넘으면
     *   그 전환은 옮기지 않고 (앞 전환이 옮긴 만큼 밀린) 제 시각을 지킨다([gridMove]).
     * - 옮기는 단위는 장(1/30초)이라 전환은 박에서 반 장 안에 온다.
     */
    fun planToBeats(timeline: RideTimeline, photos: List<PhotoCue>, options: VideoOptions,
                    dayStarts: List<Pair<Long, String>>, beats: Beats?): Planned {
        if (beats == null) return Planned(plan(timeline, photos, options, dayStarts), 0)
        val beat = beats.beatUs / 1_000_000.0
        val bar = beats.barUs / 1_000_000.0
        val photoFrames = framesOf(max(PHOTO_MIN_BEATS, (PHOTO_SECONDS / beat).roundToInt()) * beat)
        var bars = max(1, (OUTRO_SECONDS / bar).roundToInt())
        while (bars * bar < OUTRO_MIN_SECONDS) bars++
        val outroFrames = framesOf(bars * bar)
        val cardFrom = framesOf((OUTRO_CARD_FROM_SECONDS / beat).roundToInt() * beat)

        val (paced, chosen) = paceFor(timeline, photos, options)
        val groups = groupSamePlace(chosen)
        val alongOf = alongFunction(timeline)
        val alongs = groups.map { alongOf(it.first()) }
        val maxFrames = options.length.seconds * FPS
        val introFrames = OVERVIEW_FRAMES + DESCENT_FRAMES

        // 박 길이로 최대 길이를 넘으면 이동 장면을 고르게 줄인다.
        var pace = paced
        var n = framesOf(pace.seconds)
        val excess = introFrames + n + photoFrames * chosen.size + outroFrames - maxFrames
        if (excess > 0 && n - excess >= 2) {
            n -= excess
            pace = pace.warped(listOf(pace.seconds to n.toDouble() / FPS))
        }
        var slots = rideSlots(pace, n, alongs)
        if (n >= 2) {
            val snapped = snap(pace, n, groups, alongs, slots, photoFrames, outroFrames, maxFrames, beat * FPS)
            pace = snapped.first
            slots = snapped.second
        }
        val music = beats.startUs + ceilDiv(max(0L, beats.startUs - beats.barUs / 2) - beats.startUs + INTRO_US, beats.barUs) * beats.barUs - INTRO_US
        return Planned(emit(timeline, pace, groups, slots, dayStarts, photoFrames, outroFrames, cardFrom), music)
    }

    /**
     * [planToBeats]의 전환 옮기기. 격자는 장 단위: 이동 장면 시작(장 [OVERVIEW_FRAMES] + [DESCENT_FRAMES])이 마디, 박 간격 [beatFrames]장.
     * 사진 묶음 g는 이동 장면 장 [slots]\[g] 앞에 나온다(그 앞 사진 장면 수 × [photoFrames]만큼 뒤). 반환: 늘이고 줄인 곡선과 새 자리.
     * 옮긴 전환과 옮기지 못한 전환 모두 기준점이 되어(앞 토막이 0인 것은 빼고) 뒤 전환을 옮겨도 제 자리를 지킨다. 기준점의 새 시각은
     * 새 자리의 장 바로 앞 반 장이라 그 장에서 처음 사진의 거리에 닿는다.
     */
    private fun snap(pace: PaceProfile, n: Int, groups: List<List<PhotoCue>>, alongs: List<Double>, slots: IntArray,
                     photoFrames: Int, outroFrames: Int, maxFrames: Int, beatFrames: Double): Pair<PaceProfile, IntArray> {
        val introFrames = OVERVIEW_FRAMES + DESCENT_FRAMES
        val endFrame = introFrames + n + photoFrames * groups.sumOf { it.size } + outroFrames

        var shift = 0
        var lastSlot = 0
        fun tryMove(frame: Int, piece: Int, step: Double): Int =
            gridMove(frame + shift, step, piece, room = maxFrames - (endFrame + shift))

        val newSlots = IntArray(groups.size)
        val anchored = ArrayList<Int>()
        var photosBefore = 0
        for (g in groups.indices) {
            val k = slots[g]
            if (k in 1 until n) {
                val piece = k - lastSlot
                shift += tryMove(introFrames + k + photoFrames * photosBefore, piece, beatFrames)
                if (piece > 0) {
                    anchored += g
                    lastSlot = k
                }
            }
            newSlots[g] = k + shift
            photosBefore += groups[g].size
        }
        shift += tryMove(introFrames + n + photoFrames * photosBefore, n - lastSlot, beatFrames * 4)
        val newN = n + shift
        for (g in groups.indices) if (slots[g] >= n) newSlots[g] = newN

        val seconds = newN.toDouble() / FPS
        val step = seconds / (newN - 1)
        val anchors = ArrayList<Pair<Double, Double>>()
        for (g in anchored) {
            val old = pace.secondAt(alongs[g])
            val new = (newSlots[g] - 0.5) * step
            val (lastOld, lastNew) = anchors.lastOrNull() ?: (0.0 to 0.0)
            if (old > lastOld && new > lastNew && old < pace.seconds && new < seconds) anchors += old to new
        }
        anchors += pace.seconds to seconds
        return pace.warped(anchors) to newSlots
    }

    /**
     * 장 [frame]의 전환을 격자(이동 장면 시작 장부터 [step]장 간격: 박 또는 마디)에 맞출 때 옮길 장 수(스펙 §5.3). [piece] = 그 전환 바로
     * 앞 이동 장면 토막의 장 수, [room] = 영상이 최대 길이까지 더 늘 수 있는 장 수. 못 맞추면 0.
     * - 가장 가까운 격자점까지의 (반올림 전) 거리가 반 칸 이하이고(판정 P8), 토막이 [PIECE_MIN_RATIO]·1장 이상 남고, [room]을 넘지 않으면 그리로.
     * - 가장 가까운 점이 뒤에 있어 최대 길이만 막으면 바로 앞 격자점으로 당긴다(판정 P9). 그 점은 반 칸보다 멀지만 한 칸 안이고, 토막 규칙은 같다.
     */
    internal fun gridMove(frame: Int, step: Double, piece: Int, room: Int): Int {
        if (piece <= 0) return 0
        val introFrames = OVERVIEW_FRAMES + DESCENT_FRAMES
        val nearest = introFrames + ((frame - introFrames) / step).roundToInt() * step
        fun keepsPiece(move: Int) = piece + move >= max(1.0, PIECE_MIN_RATIO * piece)
        val move = nearest.roundToInt() - frame
        if (abs(nearest - frame) > step / 2 || !keepsPiece(move)) return 0
        if (move <= room) return move
        val earlier = (nearest - step).roundToInt() - frame
        return if (nearest > frame && keepsPiece(earlier) && earlier <= room) earlier else 0
    }

    /**
     * 사진 묶음마다 앞에 나올 이동 장면 장: [pace]를 [rideFrames]장에 펼 때 거리가 [alongs](시간순)에 처음 닿는 장, 끝까지 안 닿으면 [rideFrames].
     */
    private fun rideSlots(pace: PaceProfile, rideFrames: Int, alongs: List<Double>): IntArray {
        val slots = IntArray(alongs.size) { rideFrames }
        var next = 0
        for (k in 0 until rideFrames) {
            val along = pace.alongAt(rideSecond(k, rideFrames, pace.seconds))
            while (next < alongs.size && alongs[next] <= along) slots[next++] = k
        }
        return slots
    }

    private fun rideSecond(k: Int, rideFrames: Int, seconds: Double) =
        if (rideFrames > 1) k.toDouble() / (rideFrames - 1) * seconds else 0.0

    /**
     * 프레임을 늘어놓는다([plan]·[planToBeats] 공통): 시작 3초 → 이동 장면([pace]를 framesOf(길이)장에, 사진 묶음 g는 장 [slots]\[g] 앞에
     * 사진마다 [photoFrames]장) → 끝 [outroFrames]장(요약 카드는 [cardFrom]장부터).
     */
    private fun emit(timeline: RideTimeline, pace: PaceProfile, groups: List<List<PhotoCue>>, slots: IntArray,
                     dayStarts: List<Pair<Long, String>>, photoFrames: Int, outroFrames: Int, cardFrom: Int): List<FramePlan> {
        val route = timeline.routeDistance
        val totalMove = timeline.totalMoveMs
        val rideFrames = framesOf(pace.seconds)
        val alongOf = alongFunction(timeline)

        val out = ArrayList<FramePlan>(OVERVIEW_FRAMES + DESCENT_FRAMES + rideFrames + photoFrames * groups.sumOf { it.size } + outroFrames)
        fun emit(kind: SceneKind, moveMs: Long, k: Int, of: Int, photo: PhotoCue? = null, caption: String? = null,
                 fade: Float = 0f, reuseMap: Boolean = false, alongM: Double = 0.0, speed: Float = 1f) {
            val progress = if (of > 1) k.toFloat() / (of - 1) else 0f
            out += FramePlan(out.size, kind, moveMs, progress, k, of, photo, caption, fade, reuseMap, cardFrom, alongM, speed)
        }

        // 하늘 위 장면은 카메라가 돌고 사진 장면은 카메라가 사진 위치를 돌아 매 장 새 지도다.
        repeat(OVERVIEW_FRAMES) { emit(SceneKind.INTRO_OVERVIEW, 0, it, OVERVIEW_FRAMES) }
        repeat(DESCENT_FRAMES) { emit(SceneKind.INTRO_DESCENT, 0, it, DESCENT_FRAMES) }

        var nextGroup = 0
        fun emitGroup(group: List<PhotoCue>) {
            val anchor = group.first().moveMs
            val along = alongOf(group.first())
            for (cue in group) repeat(photoFrames) { emit(SceneKind.PHOTO, anchor, it, photoFrames, photo = cue, alongM = along) }
        }

        val days = dayStarts.sortedBy { it.first }.map { (moveMs, text) -> route.atMove(moveMs) to text }
        var nextDay = 0
        var caption: String? = null
        var captionLeft = 0
        for (k in 0 until rideFrames) {
            val second = rideSecond(k, rideFrames, pace.seconds)
            val along = pace.alongAt(second)
            while (nextGroup < groups.size && slots[nextGroup] <= k) emitGroup(groups[nextGroup++])
            while (nextDay < days.size && days[nextDay].first <= along) {
                caption = days[nextDay++].second
                captionLeft = CAPTION_FRAMES
            }
            val text = if (captionLeft > 0) caption.also { captionLeft-- } else null
            emit(SceneKind.RIDE, route.moveAt(along), k, rideFrames, caption = text, alongM = along, speed = pace.speedFactor(second).toFloat())
        }
        while (nextGroup < groups.size) emitGroup(groups[nextGroup++])

        repeat(outroFrames) {
            val fadeK = it - (outroFrames - FADE_FRAMES)
            val fade = if (fadeK < 0) 0f else fadeK.toFloat() / (FADE_FRAMES - 1)
            emit(SceneKind.OUTRO, totalMove, it, outroFrames, fade = fade, reuseMap = it >= OUTRO_RISE_FRAMES, alongM = route.total)
        }
        return out
    }

    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

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

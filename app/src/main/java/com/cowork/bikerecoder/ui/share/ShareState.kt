package com.cowork.bikerecoder.ui.share

import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.SharePick
import com.cowork.bikerecoder.share.ShareTarget

/**
 * The "활동 공유하기" sheet of trip [tripId] (V1.5.0 spec §1.2).
 *
 * - [text] is the text box, [autoText] what [처음 글로] puts back; [stale]: the saved text was written against another
 *   record ("기록이 바뀌어 숫자가 다를 수 있습니다"). [moods] are the chips that are on in [text].
 * - [general] / [story] / [email] are what the buttons send (spec §2.2); [fileLines] the "보낼 것" lines.
 * - [blocked]: why no image can be made (spec §2.5), shown in place of [fileLines]; [canRetry] offers [다시 시도].
 * - [making]: the portrait image is being made; the buttons give way to "요약 이미지를 만드는 중…" and [취소].
 * - [tripFinished]: the trip can make media at all ([com.cowork.bikerecoder.ui.video.canMakeMedia]).
 */
data class ShareSheetState(
    val tripId: Long,
    val text: String,
    val autoText: String,
    val stale: Boolean,
    val moods: Set<Mood>,
    val general: SharePick,
    val story: SharePick,
    val email: SharePick,
    val fileLines: List<String>,
    val blocked: String?,
    val canRetry: Boolean,
    val making: Boolean,
    val tripFinished: Boolean,
) {
    /** An image can be made when a button needs one: the trip is finished and nothing refused it. */
    val canMakeImage: Boolean get() = tripFinished && blocked == null

    /** What [target] sends; [ShareTarget.COPY] sends no file and shares the general pick. */
    fun pick(target: ShareTarget): SharePick = when (target) {
        ShareTarget.STORY -> story
        ShareTarget.EMAIL -> email
        else -> general
    }

    /**
     * The button can be pressed (spec §2.5): only [페이스북] [인스타그램] [스토리] [저장] are dimmed, when they would need
     * an image that cannot be made. [이메일] and [더 보기] then send the text only; [복사] always works.
     */
    fun enabled(target: ShareTarget): Boolean = when (target) {
        ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY, ShareTarget.SAVE ->
            pick(target) is SharePick.File || canMakeImage
        ShareTarget.EMAIL, ShareTarget.COPY, ShareTarget.MORE -> true
    }
}

/** One-shot results of the sheet's buttons, each handled once by the screen. */
sealed interface ShareEvent {
    /**
     * Open the app of [target] with [file] and [text]; a null [file] sends the text only. [made]: the sheet has just made
     * [file] for this press ("영상을 만들었습니다" need not stay).
     */
    data class Send(val target: ShareTarget, val file: ShareCandidate?, val text: String, val made: Boolean = false) : ShareEvent

    /** [복사]: put [text] on the clipboard. */
    data class Copy(val text: String) : ShareEvent

    /** A short message ("갤러리에 저장했습니다"). */
    data class Notice(val message: String) : ShareEvent
}

/** [공유] is offered (spec §1.1): the trip has a finished ride of [MIN_RIDE_DISTANCE_M] or more; it may still go on. */
fun canShare(segments: List<RideSegment>): Boolean =
    segments.any { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }

/** The trip of segment [segmentId] when its summary offers [공유] ([canShare]): the whole trip is shared. */
suspend fun shareTripOf(segmentId: Long, rideStore: RideStore): Long? {
    val tripId = rideStore.segment(segmentId)?.tripId ?: return null
    return tripId.takeIf { canShare(rideStore.segments(tripId)) }
}

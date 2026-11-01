package com.cowork.bikerecoder.ui.share

import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.ShareFile
import com.cowork.bikerecoder.share.ShareItem
import com.cowork.bikerecoder.share.ShareMode
import com.cowork.bikerecoder.share.ShareSelection
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.cards.CardKind
import com.cowork.bikerecoder.share.tagOn

/** Where a card of the sheet stands (share cards spec §3.1, §6). */
sealed interface CardStatus {
    data object Rendering : CardStatus

    /** Drawn into the share cache as [file]. */
    data class Ready(val file: ShareFile) : CardStatus

    /** Drawing failed; [noSpace]: for want of storage ("저장 공간이 부족해 카드를 만들지 못했습니다"). */
    data class Failed(val noSpace: Boolean) : CardStatus
}

/** One drawn card: its [kind] at the [mode]'s size; [photoMediaId] is the photo card's background (null for the satellite). */
data class CardKey(val kind: CardKind, val mode: ShareMode, val photoMediaId: Long?)

/**
 * The "활동 공유하기" sheet of trip [tripId] (share cards spec §1; text as in V1.5.0 spec §3).
 *
 * - [text] is the text box, [autoText] what [처음 글로] puts back; [stale]: the saved text was written against another
 *   record ("기록이 바뀌어 숫자가 다를 수 있습니다"). [moods] are the chips that are on in [text].
 * - [mode] is the post shape; [items] the "보낼 것" strip and [selection] what is picked, in tap order (spec §1.2).
 * - [cards] are the cards drawn so far ([keyOf]); [photoCardMediaId] the photo card's background (null: no photos).
 * - [placeTags]: the "지나간 길" chips (spec §4.5; [placeOn]).
 * - [tripFinished]: cards can be drawn at all ([com.cowork.bikerecoder.ui.video.canMakeMedia]); else
 *   "여행을 마치면 카드를 만들 수 있습니다" and only photos and the video can be picked.
 * - [waiting]: a pressed button waits for its cards (spec §3.1): "카드를 만드는 중…" and [취소] in place of the buttons.
 * - [error]: why the last press could not send ("카드를 만들지 못했습니다"), with [다시 시도].
 */
data class ShareSheetState(
    val tripId: Long,
    val text: String,
    val autoText: String,
    val stale: Boolean,
    val moods: Set<Mood>,
    val mode: ShareMode,
    val items: List<ShareItem>,
    val selection: ShareSelection,
    val cards: Map<CardKey, CardStatus>,
    val photoCardMediaId: Long?,
    val placeTags: List<String>,
    val tripFinished: Boolean,
    val waiting: Boolean,
    val error: String?,
) {
    /** The card [item] stands for in this [mode]; null for photos and the video (they need no card). */
    fun keyOf(item: ShareItem): CardKey? = when (item) {
        ShareItem.SatelliteCard -> CardKey(CardKind.SATELLITE, mode, null)
        ShareItem.PhotoCard -> CardKey(CardKind.PHOTO, mode, photoCardMediaId)
        is ShareItem.Photo, is ShareItem.Video -> null
    }

    /** How [item]'s card stands (null: not a card, or not drawn yet). */
    fun cardOf(item: ShareItem): CardStatus? = keyOf(item)?.let { cards[it] }

    /**
     * The button can be pressed (spec §1.3): [페이스북] [인스타그램] [스토리] need something picked, [저장] also a finished
     * trip, [스티커] a finished trip; [이메일] and [더 보기] send the text only when nothing is picked; [복사] always works.
     */
    fun enabled(target: ShareTarget): Boolean = when (target) {
        ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY -> selection.chosen.isNotEmpty()
        ShareTarget.SAVE -> tripFinished && selection.chosen.isNotEmpty()
        ShareTarget.STICKER -> tripFinished
        ShareTarget.EMAIL, ShareTarget.COPY, ShareTarget.MORE -> true
    }

    /** The "지나간 길" chip of [tag] is on: the tag stands as a word in [text] (spec §4.5). */
    fun placeOn(tag: String): Boolean = tagOn(text, tag)
}

/** One-shot results of the sheet's buttons, each handled once by the screen. */
sealed interface ShareEvent {
    /**
     * Open the app of [target] with [files] in the strip's order (empty: the text only) and [text] (spec §5.1); the screen
     * applies [com.cowork.bikerecoder.share.planSend].
     */
    data class Send(val target: ShareTarget, val files: List<ShareFile>, val text: String) : ShareEvent

    /** [복사]: put [text] on the clipboard. */
    data class Copy(val text: String) : ShareEvent

    /** A message ("카드 1장을 갤러리에 저장했습니다"); [long]: shown for longer (the sticker's, spec §5.6). */
    data class Notice(val message: String, val long: Boolean = false) : ShareEvent
}

/** [공유] is offered (spec §1.1): the trip has a finished ride of [MIN_RIDE_DISTANCE_M] or more; it may still go on. */
fun canShare(segments: List<RideSegment>): Boolean =
    segments.any { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }

/** The trip of segment [segmentId] when its summary offers [공유] ([canShare]): the whole trip is shared. */
suspend fun shareTripOf(segmentId: Long, rideStore: RideStore): Long? {
    val tripId = rideStore.segment(segmentId)?.tripId ?: return null
    return tripId.takeIf { canShare(rideStore.segments(tripId)) }
}

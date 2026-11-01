package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.core.video.CardSize
import kotlin.math.roundToInt

/** The two post shapes of the share sheet (spec §1.2). */
enum class ShareMode(val size: CardSize) { FEED(CardSize.FEED), STORY(CardSize.STORY) }

/** One thing that can be picked for a post (spec §1.2). */
sealed interface ShareItem {
    data object SatelliteCard : ShareItem
    data object PhotoCard : ShareItem

    /** [index] counts from 1 in photo order. */
    data class Photo(val mediaId: Long, val index: Int) : ShareItem
    data class Video(val candidate: ShareCandidate) : ShareItem
}

/** Every pickable item in display order: satellite card, photo card (only with photos), photos, video. */
fun shareItems(photoIds: List<Long>, video: ShareCandidate?): List<ShareItem> = buildList {
    add(ShareItem.SatelliteCard)
    if (photoIds.isNotEmpty()) add(ShareItem.PhotoCard)
    photoIds.forEachIndexed { i, id -> add(ShareItem.Photo(id, i + 1)) }
    if (video != null) add(ShareItem.Video(video))
}

/** The items picked for a post, in tap order (the order is the numbering), under the rules of spec §1.2. */
data class ShareSelection(val mode: ShareMode, val chosen: List<ShareItem>) {

    /** Taps [item]; the second value is a notice to show, if any. */
    fun toggle(item: ShareItem): Pair<ShareSelection, String?> = when (mode) {
        ShareMode.STORY -> copy(chosen = if (item in chosen) emptyList() else listOf(item)) to null
        ShareMode.FEED -> when {
            item in chosen -> copy(chosen = chosen - item) to null
            item is ShareItem.Video -> copy(chosen = listOf(item)) to null
            else -> {
                val rest = chosen.filterNot { it is ShareItem.Video }
                if (rest.size >= MAX_ITEMS) this to MSG_MAX else copy(chosen = rest + item) to null
            }
        }
    }

    companion object {
        const val MAX_ITEMS = 10
        const val MSG_MAX = "최대 10장까지 고를 수 있습니다"

        /**
         * The first selection (spec §1.2). Feed: the satellite card (when [cardsAllowed]) and up to four photos, spread
         * over the ride when there are more than four. Story: the satellite card, or the first photo without cards.
         */
        fun initial(mode: ShareMode, items: List<ShareItem>, cardsAllowed: Boolean): ShareSelection {
            val photos = items.filterIsInstance<ShareItem.Photo>()
            val chosen: List<ShareItem> = when (mode) {
                ShareMode.FEED -> {
                    val spread = if (photos.size > 4) {
                        (0..3).map { photos[(it * (photos.size - 1) / 3.0).roundToInt()] }.distinct()
                    } else {
                        photos
                    }
                    if (cardsAllowed) listOf(ShareItem.SatelliteCard) + spread else spread
                }
                ShareMode.STORY -> if (cardsAllowed) listOf(ShareItem.SatelliteCard) else listOfNotNull(photos.firstOrNull())
            }
            return ShareSelection(mode, chosen)
        }
    }
}

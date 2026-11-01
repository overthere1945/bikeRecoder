package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.video.MediaKind

/** A gallery file of the trip that may be sent; [sizeBytes] is null when MediaStore could not tell. */
data class ShareCandidate(
    val uri: String,
    val kind: MediaKind,
    val aspect: Aspect,
    val createdAt: Long,
    val sizeBytes: Long?,
)

/** The buttons of the share sheet. */
enum class ShareTarget { FACEBOOK, INSTAGRAM, STORY, EMAIL, COPY, SAVE, MORE, STICKER }

/** The largest e-mail attachment sum (share cards spec §5.4). */
const val EMAIL_MAX_BYTES = 20L * 1024 * 1024

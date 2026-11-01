package com.cowork.bikerecoder.share

/**
 * One file handed to another app (spec §3.2, §5): its content [uri] (a card in the share cache through the FileProvider,
 * or a gallery item), its [mimeType] and its size in bytes ([sizeBytes]; null: unknown).
 */
data class ShareFile(val uri: String, val mimeType: String, val sizeBytes: Long?)

/** Shown when Instagram's feed takes one image only and the others are left out (spec §5). */
const val MSG_INSTAGRAM_ONE = "인스타그램이 한 장만 받아 첫 장만 보냅니다"

/** Shown when [n] cards are attached because the mail would be too large with more (spec §5). */
fun msgEmailTrimmed(n: Int) = "용량 때문에 ${n}장만 붙였습니다"

/** What a button really sends: the [files] in order (empty: text only) and a [notice] to show when some were left out. */
data class SendPlan(val files: List<ShareFile>, val notice: String?)

/**
 * The files [target] takes of the selected [files] (spec §5). Story takes the first one; Instagram the first one
 * (with [MSG_INSTAGRAM_ONE]) unless its feed takes several ([instagramMultiple]); e-mail drops files from the end until
 * the summed size is within [EMAIL_MAX_BYTES], an unknown size counting as over the limit; the others take all.
 */
fun planSend(target: ShareTarget, files: List<ShareFile>, instagramMultiple: Boolean): SendPlan = when (target) {
    ShareTarget.STORY -> SendPlan(files.take(1), null)
    ShareTarget.INSTAGRAM ->
        if (!instagramMultiple && files.size > 1) SendPlan(files.take(1), MSG_INSTAGRAM_ONE) else SendPlan(files, null)
    ShareTarget.EMAIL -> {
        var kept = files.size
        while (kept > 0 && files.take(kept).exceedsEmailLimit()) kept--
        SendPlan(files.take(kept), if (kept in 1 until files.size) msgEmailTrimmed(kept) else null)
    }
    else -> SendPlan(files, null)
}

private fun List<ShareFile>.exceedsEmailLimit(): Boolean =
    any { it.sizeBytes == null } || sumOf { it.sizeBytes!! } > EMAIL_MAX_BYTES

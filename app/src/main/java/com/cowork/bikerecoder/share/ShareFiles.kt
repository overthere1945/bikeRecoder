package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.video.MediaKind
import java.math.BigDecimal
import java.math.RoundingMode

/** A gallery file of the trip that may be sent; [sizeBytes] is null when MediaStore could not tell. */
data class ShareCandidate(
    val uri: String,
    val kind: MediaKind,
    val aspect: Aspect,
    val createdAt: Long,
    val sizeBytes: Long?,
)

/** The buttons of the share sheet. */
enum class ShareTarget { FACEBOOK, INSTAGRAM, STORY, EMAIL, COPY, SAVE, MORE }

/** What a button sends: an existing file, or a portrait summary image made on the spot. */
sealed interface SharePick {
    data class File(val file: ShareCandidate) : SharePick
    data object MakeImage : SharePick
}

/** A video above this size is not mailed. */
const val EMAIL_MAX_BYTES = 20L * 1024 * 1024

/**
 * The file a button sends (spec §2.2). Only files made after the last finished ride ([lastEndedAt]) count; the newest
 * of a kind wins. Story takes portrait files only; e-mail skips videos over [EMAIL_MAX_BYTES] or of unknown size.
 * Not meant for [ShareTarget.COPY] (it sends no data); it is treated like the general buttons.
 */
fun pickFile(target: ShareTarget, candidates: List<ShareCandidate>, lastEndedAt: Long): SharePick {
    val fresh = candidates.filter { it.createdAt > lastEndedAt }.sortedByDescending { it.createdAt }
    val usable = when (target) {
        ShareTarget.STORY -> fresh.filter { it.aspect == Aspect.PORTRAIT }
        ShareTarget.EMAIL -> fresh.filter { it.kind == MediaKind.IMAGE || (it.sizeBytes ?: Long.MAX_VALUE) <= EMAIL_MAX_BYTES }
        else -> fresh
    }
    val found = usable.firstOrNull { it.kind == MediaKind.VIDEO } ?: usable.firstOrNull { it.kind == MediaKind.IMAGE }
    return if (found == null) SharePick.MakeImage else SharePick.File(found)
}

/** "영상 · 가로 · 12 MB", "요약 이미지 · 세로", "요약 이미지 · 세로 (누르면 만듭니다)". Only videos show a size. */
fun pickLine(pick: SharePick): String = when (pick) {
    SharePick.MakeImage -> "요약 이미지 · 세로 (누르면 만듭니다)"
    is SharePick.File -> {
        val file = pick.file
        val aspect = if (file.aspect == Aspect.PORTRAIT) "세로" else "가로"
        when (file.kind) {
            MediaKind.IMAGE -> "요약 이미지 · $aspect"
            MediaKind.VIDEO -> listOfNotNull("영상", aspect, file.sizeBytes?.let { "${megabytes(it)} MB" }).joinToString(" · ")
        }
    }
}

/** MB = bytes / 1,048,576: one decimal below 10, whole numbers from 10 up. */
private fun megabytes(bytes: Long): String {
    val mb = BigDecimal(bytes).divide(BigDecimal(1_048_576), 10, RoundingMode.HALF_UP)
    val oneDecimal = mb.setScale(1, RoundingMode.HALF_UP)
    return if (oneDecimal < BigDecimal(10)) oneDecimal.toPlainString() else mb.setScale(0, RoundingMode.HALF_UP).toPlainString()
}

/** The "보낼 것" lines of the sheet: the general pick, plus story and e-mail lines when they differ from it. */
fun fileLines(general: SharePick, story: SharePick, email: SharePick): List<String> = buildList {
    add("보낼 것: ${pickLine(general)}")
    if (story != general) add("스토리: ${pickLine(story)}")
    if (email != general) add("이메일: ${pickLine(email)}")
}

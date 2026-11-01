package com.cowork.bikerecoder.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.cowork.bikerecoder.video.mimeType

/** Title of the system chooser behind [ShareTarget.MORE]. */
private const val CHOOSER_TITLE = "활동 공유하기"

/** The share intent of every button (spec §4.2). Only builds the intents; launching them is the caller's job. */
object ShareIntents {

    /** [file] sent by [target], with [text] where the app takes one. */
    fun file(target: ShareTarget, file: ShareCandidate, text: String, apps: ShareApps): Intent {
        val uri = file.uri.toUri()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = file.kind.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return aim(target, send, text, apps)
    }

    /**
     * The [files] (never empty; text only goes through [textOnly]) sent by [target], with [text] where the app takes one
     * (spec §5). One file is an `ACTION_SEND` of its own type, more an `ACTION_SEND_MULTIPLE` of `image/jpeg` cards.
     */
    fun files(target: ShareTarget, files: List<ShareFile>, text: String, apps: ShareApps): Intent {
        require(files.isNotEmpty()) { "no files: use textOnly" }
        val uris = files.map { it.uri.toUri() }
        val multiple = files.size > 1
        val send = if (multiple) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = files[0].mimeType
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        }
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return aim(target, send, text, apps, multiple)
    }

    /** Only [text], when there is no file to send (spec §2.5). */
    fun textOnly(target: ShareTarget, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return aim(target, send, text, apps = null)
    }

    private fun aim(
        target: ShareTarget,
        send: Intent,
        text: String,
        apps: ShareApps?,
        multiple: Boolean = false,
    ): Intent = when (target) {
        ShareTarget.FACEBOOK -> send.setPackage(FACEBOOK_PACKAGE)
        ShareTarget.INSTAGRAM -> send.aimAtInstagram(if (multiple) apps?.instagramFeedMultiple() else apps?.instagramFeed())
        ShareTarget.STORY -> send.aimAtInstagram(apps?.instagramStory())
        ShareTarget.EMAIL -> send.apply {
            selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
            putExtra(Intent.EXTRA_SUBJECT, emailSubject(text))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ShareTarget.MORE -> Intent.createChooser(send.putExtra(Intent.EXTRA_TEXT, text), CHOOSER_TITLE)
        ShareTarget.COPY, ShareTarget.SAVE, ShareTarget.STICKER -> send
    }

    private fun Intent.aimAtInstagram(component: ComponentName?): Intent =
        if (component != null) setComponent(component) else setPackage(INSTAGRAM_PACKAGE)
}

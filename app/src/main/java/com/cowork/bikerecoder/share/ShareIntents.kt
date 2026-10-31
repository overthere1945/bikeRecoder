package com.cowork.bikerecoder.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
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

    /** Only [text], when there is no file to send (spec §2.5). */
    fun textOnly(target: ShareTarget, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return aim(target, send, text, apps = null)
    }

    private fun aim(target: ShareTarget, send: Intent, text: String, apps: ShareApps?): Intent = when (target) {
        ShareTarget.FACEBOOK -> send.setPackage(FACEBOOK_PACKAGE)
        ShareTarget.INSTAGRAM -> send.aimAtInstagram(apps?.instagramFeed())
        ShareTarget.STORY -> send.aimAtInstagram(apps?.instagramStory())
        ShareTarget.EMAIL -> send.apply {
            selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
            putExtra(Intent.EXTRA_SUBJECT, emailSubject(text))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ShareTarget.MORE -> Intent.createChooser(send.putExtra(Intent.EXTRA_TEXT, text), CHOOSER_TITLE)
        ShareTarget.COPY, ShareTarget.SAVE -> send
    }

    private fun Intent.aimAtInstagram(component: ComponentName?): Intent =
        if (component != null) setComponent(component) else setPackage(INSTAGRAM_PACKAGE)
}

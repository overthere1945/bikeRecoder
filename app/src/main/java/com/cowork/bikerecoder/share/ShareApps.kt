package com.cowork.bikerecoder.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.core.net.toUri

const val FACEBOOK_PACKAGE = "com.facebook.katana"
const val INSTAGRAM_PACKAGE = "com.instagram.android"

/** Which share apps are installed, and where Instagram's feed and story share screens are (spec §4.1). */
interface ShareApps {
    /** Whether the button of [target] is shown. */
    fun available(target: ShareTarget): Boolean

    /** Instagram's feed share activity, or null when it cannot be told apart. */
    fun instagramFeed(): ComponentName?

    /** Instagram's story share activity, or null when it cannot be told apart. */
    fun instagramStory(): ComponentName?

    /** The app icon for [ShareTarget.FACEBOOK], [ShareTarget.INSTAGRAM] and [ShareTarget.STORY]; null otherwise. */
    fun icon(target: ShareTarget): Drawable?
}

/** Looks the apps up through the package manager; needs the `<queries>` of the manifest. */
class PackageShareApps(private val context: Context) : ShareApps {
    private val pm: PackageManager get() = context.packageManager

    override fun available(target: ShareTarget): Boolean = when (target) {
        ShareTarget.FACEBOOK -> receivers(FACEBOOK_PACKAGE).isNotEmpty()
        ShareTarget.INSTAGRAM, ShareTarget.STORY -> receivers(INSTAGRAM_PACKAGE).isNotEmpty()
        ShareTarget.EMAIL -> pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, "mailto:".toUri()), 0).isNotEmpty()
        ShareTarget.COPY, ShareTarget.SAVE, ShareTarget.MORE -> true
    }

    override fun instagramFeed(): ComponentName? = pickInstagramFeed(instagramReceivers())?.toComponent()

    override fun instagramStory(): ComponentName? = pickInstagramStory(instagramReceivers())?.toComponent()

    override fun icon(target: ShareTarget): Drawable? {
        val packageName = when (target) {
            ShareTarget.FACEBOOK -> FACEBOOK_PACKAGE
            ShareTarget.INSTAGRAM, ShareTarget.STORY -> INSTAGRAM_PACKAGE
            else -> return null
        }
        return try {
            pm.getApplicationInfo(packageName, 0).loadIcon(pm)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun receivers(packageName: String): List<ResolveInfo> =
        pm.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("image/png").setPackage(packageName),
            0,
        )

    private fun instagramReceivers(): List<InstagramReceiver> =
        receivers(INSTAGRAM_PACKAGE).map {
            InstagramReceiver(it.activityInfo.packageName, it.activityInfo.name, it.activityInfo.exported)
        }

    private fun InstagramReceiver.toComponent() = ComponentName(packageName, className)
}

/** One activity of Instagram that takes a shared image or video. */
data class InstagramReceiver(val packageName: String, val className: String, val exported: Boolean)

/**
 * The feed share screen among [receivers]: of the exported ones, sorted by class name so the answer does not depend on
 * the order the package manager lists them, the one named exactly `ShareHandlerActivity`, else the first that is neither
 * a story nor a direct-message screen. Null when none is left.
 */
fun pickInstagramFeed(receivers: List<InstagramReceiver>): InstagramReceiver? {
    val candidates = receivers.exportedByName()
    return candidates.firstOrNull { it.className.substringAfterLast('.') == "ShareHandlerActivity" }
        ?: candidates.firstOrNull { !it.className.has("story") && !it.className.has("direct") }
}

/** The story share screen among [receivers]: the first exported one (by class name) that is a story and not a direct one. */
fun pickInstagramStory(receivers: List<InstagramReceiver>): InstagramReceiver? =
    receivers.exportedByName().firstOrNull { it.className.has("story") && !it.className.has("direct") }

private fun List<InstagramReceiver>.exportedByName() = filter { it.exported }.sortedBy { it.className }

private fun String.has(part: String) = contains(part, ignoreCase = true)

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

    /**
     * Instagram's feed share activity for several images at once (the same pick over the `ACTION_SEND_MULTIPLE`
     * receivers), or null when it cannot be told apart.
     */
    fun instagramFeedMultiple(): ComponentName? = null

    /** Instagram's story share activity, or null when it cannot be told apart. */
    fun instagramStory(): ComponentName?

    /** Whether Instagram's feed takes several images in one share (spec §5). */
    fun instagramTakesMultiple(): Boolean

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
        ShareTarget.COPY, ShareTarget.SAVE, ShareTarget.MORE, ShareTarget.STICKER -> true
    }

    override fun instagramFeed(): ComponentName? = pickInstagramFeed(instagramReceivers())?.toComponent()

    override fun instagramFeedMultiple(): ComponentName? =
        pickInstagramFeed(instagramReceivers(multiple = true))?.toComponent()

    override fun instagramTakesMultiple(): Boolean = instagramFeedMultiple() != null

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

    private fun receivers(packageName: String, multiple: Boolean = false): List<ResolveInfo> =
        pm.queryIntentActivities(
            if (multiple) {
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").setPackage(packageName)
            } else {
                Intent(Intent.ACTION_SEND).setType("image/png").setPackage(packageName)
            },
            0,
        )

    private fun instagramReceivers(multiple: Boolean = false): List<InstagramReceiver> =
        receivers(INSTAGRAM_PACKAGE, multiple).map {
            InstagramReceiver(it.activityInfo.packageName, it.activityInfo.name, it.activityInfo.exported)
        }

    private fun InstagramReceiver.toComponent() = ComponentName(packageName, className)
}

/** One activity of Instagram that takes a shared image or video. */
data class InstagramReceiver(val packageName: String, val className: String, val exported: Boolean)

/**
 * The feed share screen among [receivers]: of the exported ones, sorted by class name so the answer does not depend on
 * the order the package manager lists them, (1) the one whose simple name is exactly `ShareHandlerActivity`, else (2) the
 * first whose simple name starts with `ShareHandlerActivity` (Instagram's `ShareHandlerActivityMultipleFeedAlias` for
 * several images), else (3) the first that is neither a story, a direct-message nor a reel screen. Null when none is left.
 * A reel screen is never the feed: sent several images, `ReelShareHandlerActivity` opens Instagram's home, not the post
 * composer (seen on the S22).
 */
fun pickInstagramFeed(receivers: List<InstagramReceiver>): InstagramReceiver? {
    val candidates = receivers.exportedByName()
    return candidates.firstOrNull { it.simpleName == "ShareHandlerActivity" }
        ?: candidates.firstOrNull { it.simpleName.startsWith("ShareHandlerActivity") }
        ?: candidates.firstOrNull {
            !it.className.has("story") && !it.className.has("direct") && !it.className.has("reel")
        }
}

/**
 * The story share screen among [receivers]: the first exported one (by class name) that is a story and not a direct one —
 * so never a reel. `MultiStoryShareHandlerActivity` would qualify, but a story only ever sends one file.
 */
fun pickInstagramStory(receivers: List<InstagramReceiver>): InstagramReceiver? =
    receivers.exportedByName().firstOrNull { it.className.has("story") && !it.className.has("direct") }

private fun List<InstagramReceiver>.exportedByName() = filter { it.exported }.sortedBy { it.className }

private val InstagramReceiver.simpleName get() = className.substringAfterLast('.')

private fun String.has(part: String) = contains(part, ignoreCase = true)

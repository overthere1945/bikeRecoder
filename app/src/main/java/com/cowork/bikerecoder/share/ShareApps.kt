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

/**
 * Which share apps are installed, and where Instagram's feed and story and Facebook's post share screens are (spec §4.1).
 */
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

    /**
     * Facebook's post share activity for one file or text ([multiple] false) or for several images, or null when it
     * cannot be told apart. Aiming at the package alone lets the system pick: on the S22 and S25 its resolver showed only
     * "Facebook 내 그룹" (`ShareToGroupsAlias`), not the post composer.
     */
    fun facebookFeed(multiple: Boolean): ComponentName? = null

    /** Whether Instagram's feed takes several images in one share (spec §5). */
    fun instagramTakesMultiple(): Boolean

    /**
     * The mail apps: distinct packages of the activities taking `ACTION_SENDTO` `mailto:`, in package-manager order.
     * [ShareTarget.EMAIL] is aimed at them ([ShareIntents]) and shown only when there is one.
     */
    fun mailPackages(): List<String>

    /** The app icon for [ShareTarget.FACEBOOK], [ShareTarget.INSTAGRAM] and [ShareTarget.STORY]; null otherwise. */
    fun icon(target: ShareTarget): Drawable?
}

/** Looks the apps up through the package manager; needs the `<queries>` of the manifest. */
class PackageShareApps(private val context: Context) : ShareApps {
    private val pm: PackageManager get() = context.packageManager

    override fun available(target: ShareTarget): Boolean = when (target) {
        ShareTarget.FACEBOOK -> receivers(FACEBOOK_PACKAGE).isNotEmpty()
        ShareTarget.INSTAGRAM, ShareTarget.STORY -> receivers(INSTAGRAM_PACKAGE).isNotEmpty()
        ShareTarget.EMAIL -> mailPackages().isNotEmpty()
        ShareTarget.COPY, ShareTarget.SAVE, ShareTarget.MORE, ShareTarget.STICKER -> true
    }

    override fun instagramFeed(): ComponentName? = pickInstagramFeed(instagramReceivers())?.toComponent()

    override fun instagramFeedMultiple(): ComponentName? =
        pickInstagramFeed(instagramReceivers(multiple = true))?.toComponent()

    override fun instagramTakesMultiple(): Boolean = instagramFeedMultiple() != null

    override fun facebookFeed(multiple: Boolean): ComponentName? =
        pickFacebookFeed(appReceivers(FACEBOOK_PACKAGE, multiple))?.toComponent()

    override fun instagramStory(): ComponentName? = pickInstagramStory(instagramReceivers())?.toComponent()

    override fun mailPackages(): List<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, "mailto:".toUri()), 0)
            .map { it.activityInfo.packageName }
            .distinct()

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

    private fun instagramReceivers(multiple: Boolean = false): List<ShareReceiver> =
        appReceivers(INSTAGRAM_PACKAGE, multiple)

    private fun appReceivers(packageName: String, multiple: Boolean): List<ShareReceiver> =
        receivers(packageName, multiple).map {
            ShareReceiver(it.activityInfo.packageName, it.activityInfo.name, it.activityInfo.exported)
        }

    private fun ShareReceiver.toComponent() = ComponentName(packageName, className)
}

/** One activity of Instagram or Facebook that takes a shared image or video. */
data class ShareReceiver(val packageName: String, val className: String, val exported: Boolean)

/**
 * The feed share screen among [receivers]: of the exported ones, sorted by class name so the answer does not depend on
 * the order the package manager lists them, (1) the one whose simple name is exactly `ShareHandlerActivity`, else (2) the
 * first whose simple name starts with `ShareHandlerActivity` (Instagram's `ShareHandlerActivityMultipleFeedAlias` for
 * several images), else (3) the first that is neither a story, a direct-message nor a reel screen. Null when none is left.
 * A reel screen is never the feed: sent several images, `ReelShareHandlerActivity` opens Instagram's home, not the post
 * composer (seen on the S22).
 */
fun pickInstagramFeed(receivers: List<ShareReceiver>): ShareReceiver? {
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
fun pickInstagramStory(receivers: List<ShareReceiver>): ShareReceiver? =
    receivers.exportedByName().firstOrNull { it.className.has("story") && !it.className.has("direct") }

/**
 * Facebook's post composer among [receivers]: the first exported one (by class name) whose simple name starts with
 * `ImplicitShareIntentHandler` (`ImplicitShareIntentHandlerDefaultAlias` takes one or several images, a video and text,
 * and opens 새 게시물; seen on the S25). Null otherwise, so the share falls back to the package and the system's choice.
 */
fun pickFacebookFeed(receivers: List<ShareReceiver>): ShareReceiver? =
    receivers.exportedByName().firstOrNull { it.simpleName.startsWith("ImplicitShareIntentHandler") }

private fun List<ShareReceiver>.exportedByName() = filter { it.exported }.sortedBy { it.className }

private val ShareReceiver.simpleName get() = className.substringAfterLast('.')

private fun String.has(part: String) = contains(part, ignoreCase = true)

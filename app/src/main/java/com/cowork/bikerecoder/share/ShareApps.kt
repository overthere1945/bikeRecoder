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

    override fun instagramFeed(): ComponentName? =
        instagramActivities().firstOrNull { !it.contains("Story") && !it.contains("Direct") }

    override fun instagramStory(): ComponentName? =
        instagramActivities().firstOrNull { it.contains("Story") }

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

    private fun instagramActivities(): List<ComponentName> =
        receivers(INSTAGRAM_PACKAGE).map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }

    private fun ComponentName.contains(part: String) = className.contains(part)
}

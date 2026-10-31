package com.cowork.bikerecoder.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.share.PackageShareApps
import com.cowork.bikerecoder.share.ShareApps
import com.cowork.bikerecoder.share.ShareIntents
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.video.VideoNotifications
import kotlinx.coroutines.Dispatchers

/** The label of the text put on the clipboard (spec §3.6). */
private const val CLIP_LABEL = "활동"

const val MSG_TEXT_COPIED_PASTE = "글을 복사했습니다. 붙여 넣으세요"
const val MSG_COPIED = "복사했습니다"
const val MSG_NO_APP = "열 수 있는 앱이 없습니다"

/**
 * The share sheet over any screen ([viewModel] is activity-scoped, so the text survives a rotation) and its events: a
 * [ShareEvent.Send] opens the app ([launch]) and closes the sheet, [ShareEvent.Copy] and [ShareEvent.Notice] leave it
 * open (spec §1.2). Messages are short toasts ([tell]): the sheet is its own window, above any screen's snackbar.
 */
@Composable
fun ShareHost(
    viewModel: ShareViewModel,
    apps: ShareApps = rememberPackageShareApps(),
    launch: (Intent) -> Unit = defaultLaunch(),
    tell: (String) -> Unit = defaultTell(),
) {
    // Main.immediate: the text box gets each edit back at once (a late value would undo the next keystroke).
    val sheet by viewModel.sheet.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val context = LocalContext.current
    val currentLaunch by rememberUpdatedState(launch)
    val currentTell by rememberUpdatedState(tell)
    LaunchedEffect(viewModel, apps) {
        val actions = ShareActions(
            apps = apps,
            launch = { currentLaunch(it) },
            clip = { clip -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) },
            tell = { currentTell(it) },
            resultSeen = { VideoNotifications.cancelResult(context) },
            close = viewModel::close,
        )
        viewModel.events.collect(actions::handle)
    }
    sheet?.let { s ->
        ShareSheet(
            state = s,
            apps = apps,
            onEdit = viewModel::edit,
            onReset = viewModel::reset,
            onMood = viewModel::toggle,
            onPress = viewModel::press,
            onCancelMaking = viewModel::cancelMaking,
            onRetry = viewModel::retry,
            onDismiss = viewModel::close,
        )
    }
}

/**
 * What the sheet's events do (spec §1.2, §3.6, §4.2), apart from the screen. [clip] puts text on the clipboard, [tell]
 * shows a short message, [resultSeen] takes "영상을 만들었습니다" away, [close] closes the sheet.
 */
class ShareActions(
    private val apps: ShareApps,
    private val launch: (Intent) -> Unit,
    private val clip: (ClipData) -> Unit,
    private val tell: (String) -> Unit,
    private val resultSeen: () -> Unit,
    private val close: () -> Unit,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    fun handle(event: ShareEvent) {
        when (event) {
            is ShareEvent.Send -> send(event)
            is ShareEvent.Copy -> {
                clip(ClipData.newPlainText(CLIP_LABEL, event.text))
                // Android 13 and later tell it themselves.
                if (sdk < Build.VERSION_CODES.TIRAMISU) tell(MSG_COPIED)
            }
            is ShareEvent.Notice -> tell(event.message)
        }
    }

    /**
     * The app of the button opens with the file (or the text only). Facebook and Instagram take no text with it: the text
     * goes to the clipboard first, to be pasted there. The sheet closes once the app opened; with no app it stays.
     */
    private fun send(event: ShareEvent.Send) {
        // Made in the sheet and sent on now: the notification would only open it again.
        if (event.made) resultSeen()
        if (event.target in PASTE_TARGETS) {
            clip(ClipData.newPlainText(CLIP_LABEL, event.text))
            tell(MSG_TEXT_COPIED_PASTE)
        }
        val intent = event.file?.let { ShareIntents.file(event.target, it, event.text, apps) }
            ?: ShareIntents.textOnly(event.target, event.text)
        try {
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            tell(MSG_NO_APP)
            return
        }
        close()
    }

    private companion object {
        val PASTE_TARGETS = setOf(ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY)
    }
}

/** The installed apps through the package manager, looked up again by every sheet ([ShareSheet] remembers them). */
@Composable
fun rememberPackageShareApps(): ShareApps {
    val context = LocalContext.current.applicationContext
    return remember(context) { PackageShareApps(context) }
}

@Composable
private fun defaultLaunch(): (Intent) -> Unit {
    val context = LocalContext.current
    return remember(context) { { intent -> context.startActivity(intent) } }
}

@Composable
private fun defaultTell(): (String) -> Unit {
    val context: Context = LocalContext.current
    return remember(context) { { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } }
}

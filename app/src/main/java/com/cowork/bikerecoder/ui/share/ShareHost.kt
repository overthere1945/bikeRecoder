package com.cowork.bikerecoder.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.share.PackageShareApps
import com.cowork.bikerecoder.share.ShareApps
import com.cowork.bikerecoder.share.ShareIntents
import com.cowork.bikerecoder.share.ShareItem
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.planSend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.max

/** The label of the text put on the clipboard (spec §3.6). */
private const val CLIP_LABEL = "활동"

const val MSG_TEXT_COPIED_PASTE = "글을 복사했습니다. 붙여 넣으세요"
const val MSG_COPIED = "복사했습니다"
const val MSG_NO_APP = "열 수 있는 앱이 없습니다"

/**
 * The share sheet over any screen ([viewModel] is activity-scoped, so the text survives a rotation) and its events: a
 * [ShareEvent.Send] opens the app ([launch]) and closes the sheet, [ShareEvent.Copy] and [ShareEvent.Notice] leave it
 * open (spec §1.2). Messages are toasts ([tell]; long for the sticker and for what a send left out): the sheet is its own
 * window, above any screen's snackbar. Events are taken only while the screen is started ([handleWhileStarted]). The
 * strip's pictures come from [photos] and the share cache ([SharePictures]).
 */
@Composable
fun ShareHost(
    viewModel: ShareViewModel,
    photos: PhotoThumbnails,
    apps: ShareApps = rememberPackageShareApps(),
    launch: (Intent) -> Unit = defaultLaunch(),
    tell: (String, Boolean) -> Unit = defaultTell(),
) {
    // Main.immediate: the text box gets each edit back at once (a late value would undo the next keystroke).
    val sheet by viewModel.sheet.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val context = LocalContext.current
    val currentLaunch by rememberUpdatedState(launch)
    val currentTell by rememberUpdatedState(tell)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pictures = remember(context, photos) { SharePictures(context.applicationContext.contentResolver, photos) }
    LaunchedEffect(viewModel, apps, lifecycle) {
        val actions = ShareActions(
            apps = apps,
            launch = { currentLaunch(it) },
            clip = { clip -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) },
            tell = { message, long -> currentTell(message, long) },
            close = viewModel::close,
        )
        handleWhileStarted(lifecycle, viewModel.events, actions::handle)
    }
    sheet?.let { s ->
        ShareSheet(
            state = s,
            apps = apps,
            thumbnail = pictures::thumbnail,
            preview = pictures::preview,
            onEdit = viewModel::edit,
            onReset = viewModel::reset,
            onMood = viewModel::toggle,
            onPress = viewModel::press,
            onMode = viewModel::setMode,
            onTap = viewModel::tap,
            onPhotoCard = viewModel::setPhotoCard,
            onPlace = viewModel::togglePlace,
            onCancelMaking = viewModel::cancelMaking,
            onRetry = viewModel::retryCards,
            onDismiss = viewModel::close,
        )
    }
}

/**
 * The pictures of the share sheet (share cards spec §1.2), read off the main thread: a drawn card decoded from the share
 * cache to a width, a photo through [photos], the video's gallery thumbnail. Null while a card is not drawn, or when the
 * file cannot be read.
 */
internal class SharePictures(
    private val resolver: ContentResolver,
    private val photos: PhotoThumbnails,
    private val log: WarnLog = androidWarnLog("SharePictures"),
) {
    /** The strip's square ([THUMBNAIL_PX]: 72 dp at xxhdpi). */
    suspend fun thumbnail(item: ShareItem, state: ShareSheetState): ImageBitmap? = when (item) {
        is ShareItem.Photo -> photos.thumbnail(item.mediaId, THUMBNAIL_PX)
        else -> read(item, state, THUMBNAIL_PX)
    }

    /** The preview's picture at [widthPx] (a photo: the whole photo, as the cards read it). */
    suspend fun preview(item: ShareItem, state: ShareSheetState, widthPx: Int): ImageBitmap? = when (item) {
        is ShareItem.Photo -> photos.full(item.mediaId)
        else -> read(item, state, widthPx)
    }

    private suspend fun read(item: ShareItem, state: ShareSheetState, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            when (item) {
                ShareItem.SatelliteCard, ShareItem.PhotoCard ->
                    (state.cardOf(item) as? CardStatus.Ready)?.let { decode(it.file.uri.toUri(), widthPx) }
                is ShareItem.Video -> resolver.loadThumbnail(item.candidate.uri.toUri(), Size(widthPx, widthPx), null)
                is ShareItem.Photo -> null
            }?.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the picture of $item", e)
            null
        }
    }

    /** The image at [uri], scaled down (while decoding) to [widthPx] wide when it is wider. */
    private fun decode(uri: Uri, widthPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val width = info.size.width
            if (width > widthPx) {
                decoder.setTargetSize(widthPx, max(1, (info.size.height.toLong() * widthPx / width).toInt()))
            }
        }

    private companion object {
        const val THUMBNAIL_PX = 216
    }
}

/**
 * Hands [events] to [handle] only while [lifecycle] is at least started. One that comes while the app is away (cards
 * drawn meanwhile) waits in the view model's channel: Android would not let an app in the background open another, and
 * the sheet would close on a send that never happened.
 */
internal suspend fun handleWhileStarted(lifecycle: Lifecycle, events: Flow<ShareEvent>, handle: (ShareEvent) -> Unit) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { events.collect(handle) }
}

/**
 * What the sheet's events do (spec §1.2, §3.6, §4.2; share cards spec §5), apart from the screen. [clip] puts text on
 * the clipboard, [tell] shows a message (`long`: for longer), [close] closes the sheet.
 */
class ShareActions(
    private val apps: ShareApps,
    private val launch: (Intent) -> Unit,
    private val clip: (ClipData) -> Unit,
    private val tell: (message: String, long: Boolean) -> Unit,
    private val close: () -> Unit,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    fun handle(event: ShareEvent) {
        when (event) {
            is ShareEvent.Send -> send(event)
            is ShareEvent.Copy -> {
                clip(ClipData.newPlainText(CLIP_LABEL, event.text))
                // Android 13 and later tell it themselves.
                if (sdk < Build.VERSION_CODES.TIRAMISU) tell(MSG_COPIED, false)
            }
            is ShareEvent.Notice -> tell(event.message, event.long)
        }
    }

    /**
     * The app of the button opens with the files the button takes ([planSend]) or the text only. Facebook and Instagram
     * take no text with them: the text goes to the clipboard first, to be pasted there. What was left out ([planSend]'s
     * notice) and the paste hint are told as one longer message, so neither hides the other. The sheet closes once the
     * app opened; with no app it stays.
     */
    private fun send(event: ShareEvent.Send) {
        // Asking the package manager is for Instagram only (it runs on the main thread).
        val plan = planSend(event.target, event.files, event.target == ShareTarget.INSTAGRAM && apps.instagramTakesMultiple())
        val paste = event.target in PASTE_TARGETS
        if (paste) clip(ClipData.newPlainText(CLIP_LABEL, event.text))
        val message = listOfNotNull(plan.notice, MSG_TEXT_COPIED_PASTE.takeIf { paste }).joinToString("\n")
        if (message.isNotEmpty()) tell(message, plan.notice != null)
        val intent = if (plan.files.isEmpty()) {
            ShareIntents.textOnly(event.target, event.text)
        } else {
            ShareIntents.files(event.target, plan.files, event.text, apps)
        }
        try {
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            tell(MSG_NO_APP, false)
            return
        } catch (_: SecurityException) {
            // An activity of the app that is not open to us (a handler it keeps to itself).
            tell(MSG_NO_APP, false)
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
private fun defaultTell(): (String, Boolean) -> Unit {
    val context: Context = LocalContext.current
    return remember(context) {
        { message, long -> Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}

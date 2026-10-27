package com.cowork.bikerecoder.ui.video

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.cowork.bikerecoder.ui.common.GlyphIcon
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.mimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The made file (spec §3.5): "영상을 만들었습니다" / "이미지를 만들었습니다" with its gallery name ([fileName], read back
 * from MediaStore, null = not shown), the video playing (looped, a tap pauses) or the image, "이 휴대폰은 720p로
 * 만들었습니다" when [downscaled], then [공유] (the share sheet) and [갤러리에서 보기]. [media] is replaceable in tests.
 */
@Composable
fun MediaPreviewScreen(
    uri: String,
    kind: MediaKind,
    downscaled: Boolean,
    fileName: String?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onOpenGallery: () -> Unit,
    media: @Composable (Modifier) -> Unit = { modifier ->
        if (kind == MediaKind.VIDEO) PreviewVideo(uri, modifier) else PreviewImage(uri, modifier)
    },
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (kind == MediaKind.VIDEO) "영상을 만들었습니다" else "이미지를 만들었습니다",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (fileName != null) {
                        Text(
                            fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(PREVIEW_BACKDROP)
                    .testTag(VideoTags.PREVIEW_MEDIA),
                contentAlignment = Alignment.Center,
            ) { media(Modifier) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (downscaled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "이 휴대폰은 720p로 만들었습니다",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onShare,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).height(52.dp).testTag(VideoTags.SHARE),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("공유", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenGallery,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).height(52.dp).testTag(VideoTags.GALLERY),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("갤러리에서 보기", maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The video, fitted and looping; a tap pauses or plays (▶ while paused). */
@Composable
private fun PreviewVideo(uri: String, modifier: Modifier) {
    var ratio by remember { mutableStateOf<Float?>(null) }
    var paused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    if (failed) {
        Text("영상을 열 수 없습니다", color = Color.White)
        return
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        if (player.videoWidth > 0 && player.videoHeight > 0) ratio = player.videoWidth.toFloat() / player.videoHeight
                        start()
                    }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    setVideoURI(uri.toUri())
                    view = this
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier
                .aspectRatio(ratio ?: (9f / 16f), matchHeightConstraintsFirst = (ratio ?: 0f) < 1f)
                .clickable {
                    val v = view ?: return@clickable
                    if (v.isPlaying) v.pause() else v.start()
                    paused = !v.isPlaying
                },
        )
        if (paused) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "재생", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}

/** The image, fitted (decoded at most [PREVIEW_MAX_PX] on its long side). */
@Composable
private fun PreviewImage(uri: String, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<Result<ImageBitmap>?>(null, uri) { value = withContext(Dispatchers.IO) { decode(context, uri) } }
    when (val r = image) {
        null -> CircularProgressIndicator(color = Color.White)
        else -> r.fold(
            onSuccess = { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.fillMaxSize()) },
            onFailure = { Text("이미지를 열 수 없습니다", color = Color.White) },
        )
    }
}

private fun decode(context: Context, uri: String): Result<ImageBitmap> = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri.toUri())
    Result.success(
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > PREVIEW_MAX_PX) {
                val scale = PREVIEW_MAX_PX.toDouble() / longest
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
        }.asImageBitmap(),
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** [공유]: the share sheet with the file (spec §3.5). */
fun Context.shareMedia(uri: String, kind: MediaKind) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(kind.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri.toUri())
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startOrTell(Intent.createChooser(send, null))
}

/** [갤러리에서 보기] and a tap on "만든 영상·이미지": the gallery's (or another viewer's) view of the file. */
fun Context.viewMedia(uri: String, kind: MediaKind) {
    startOrTell(Intent(Intent.ACTION_VIEW).setDataAndType(uri.toUri(), kind.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

private fun Context.startOrTell(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show()
    }
}

private const val PREVIEW_MAX_PX = 2048

/** Behind the media: dark, as a viewer. */
private val PREVIEW_BACKDROP = Color(0xFF121212)

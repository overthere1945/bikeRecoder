package com.cowork.bikerecoder.ui.video

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.post.HrBand
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.video.MediaKind

/** Test tags of the video screens. */
object VideoTags {
    const val SHEET = "video-sheet"
    const val MAKE = "video-make"
    const val ESTIMATE = "video-estimate"
    const val DATA_NOTICE = "video-data-notice"
    const val MUSIC_NAME = "video-music-name"
    const val MAKE_VIDEO = "video-entry-video"
    const val MAKE_IMAGE = "video-entry-image"
    const val PROGRESS = "video-progress"
    const val CANCEL = "video-progress-cancel"
    const val MEDIA_ROW = "video-media-row"
    const val PREVIEW_MEDIA = "video-preview-media"
    const val SHARE = "video-preview-share"
    const val GALLERY = "video-preview-gallery"

    fun media(id: Long) = "video-media-$id"
}

/**
 * The making's sheet and dialogs over any screen ([viewModel] is activity-scoped). [음악 고르기] opens the system file
 * picker for audio (spec §3.5); the read permission is kept if the provider allows it — the job reads the file only
 * while it starts, so a refusal does not matter.
 */
@Composable
fun VideoHost(viewModel: VideoViewModel) {
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Readable for this session anyway.
        }
        viewModel.pickMusic(uri.toString())
    }
    sheet?.let { s ->
        VideoOptionsSheet(
            sheet = s,
            onOptions = viewModel::setOptions,
            onPickMusic = { picker.launch(arrayOf("audio/*")) },
            onClearMusic = viewModel::clearMusic,
            onMake = viewModel::make,
            onDismiss = viewModel::closeSheet,
        )
    }
    VideoDialogs(
        dialog = dialog,
        onConfirmBattery = viewModel::confirmBattery,
        onWithoutMusic = viewModel::makeWithoutMusic,
        onRetry = viewModel::retryUnfinished,
        onCloseUnfinished = viewModel::dismissUnfinished,
        onDismiss = viewModel::dismissDialog,
    )
}

/** [VideoOptionsContent] in a bottom sheet that opens fully. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOptionsSheet(
    sheet: VideoSheet,
    onOptions: (VideoOptions) -> Unit,
    onPickMusic: () -> Unit,
    onClearMusic: () -> Unit,
    onMake: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        VideoOptionsContent(sheet, onOptions, onPickMusic, onClearMusic, onMake)
    }
}

/**
 * The options (spec §1.2): 화면 비율, 길이, 사진 넣기, 숫자 표시, 경로 색 ([심박] only when [VideoSheet.canColorByHeart]),
 * 배경음악 (the picked file's name, [없음] goes back); the image asks for the aspect only (§1.3). The choices scroll;
 * "약 N분 걸립니다", on cellular the satellite data notice (V1.3.1 §1.4) and [만들기] stay at the bottom.
 */
@Composable
fun VideoOptionsContent(
    sheet: VideoSheet,
    onOptions: (VideoOptions) -> Unit,
    onPickMusic: () -> Unit,
    onClearMusic: () -> Unit,
    onMake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val o = sheet.options
    val video = sheet.kind == MediaKind.VIDEO
    Column(modifier = modifier.fillMaxWidth().testTag(VideoTags.SHEET)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (video) Icons.Filled.Movie else Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (video) "영상 만들기" else "이미지 만들기",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Section("화면 비율") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AspectTile("세로 9:16", Aspect.PORTRAIT, o.aspect == Aspect.PORTRAIT, Modifier.weight(1f)) {
                        onOptions(o.copy(aspect = Aspect.PORTRAIT))
                    }
                    AspectTile("가로 16:9", Aspect.LANDSCAPE, o.aspect == Aspect.LANDSCAPE, Modifier.weight(1f)) {
                        onOptions(o.copy(aspect = Aspect.LANDSCAPE))
                    }
                }
            }
            if (video) {
                Section("길이") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LENGTHS.forEach { (label, length) ->
                            ChoiceTile(label, o.length == length, Modifier.weight(1f)) { onOptions(o.copy(length = length)) }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    SwitchRow("사진 넣기", null, o.photos) { onOptions(o.copy(photos = it)) }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow("숫자 표시", "거리·속도·고도·심박", o.hud) { onOptions(o.copy(hud = it)) }
                }
                if (sheet.canColorByHeart) {
                    Section("경로 색") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChoiceTile("기본", o.coloring == RouteColoring.DEFAULT, Modifier.weight(1f), swatch = DEFAULT_SWATCH) {
                                onOptions(o.copy(coloring = RouteColoring.DEFAULT))
                            }
                            ChoiceTile("심박", o.coloring == RouteColoring.HEART_RATE, Modifier.weight(1f), swatch = HEART_SWATCH) {
                                onOptions(o.copy(coloring = RouteColoring.HEART_RATE))
                            }
                        }
                    }
                }
                Section("배경음악") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChoiceTile("없음", o.musicUri == null, Modifier.weight(1f), onClick = onClearMusic)
                            ChoiceTile("음악 고르기", o.musicUri != null, Modifier.weight(1f), icon = true, onClick = onPickMusic)
                        }
                        if (o.musicUri != null) MusicRow(sheet.musicName)
                    }
                }
            }
            Spacer(Modifier.height(0.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (video) {
                Text(
                    sheet.minutes?.let { "약 ${it}분 걸립니다" } ?: " ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(VideoTags.ESTIMATE),
                )
            }
            // V1.3.1 spec §1.4: cellular only, a word of warning, never a gate.
            sheet.dataNoticeMb?.let { mb ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        dataNoticeText(mb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag(VideoTags.DATA_NOTICE),
                    )
                }
            }
            Button(
                onClick = onMake,
                enabled = sheet.canMake,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag(VideoTags.MAKE),
            ) {
                if (sheet.starting) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("만들기", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** A choice as a rounded tile: tinted and outlined when [selected]. */
@Composable
private fun Tile(selected: Boolean, modifier: Modifier, onClick: () -> Unit, minHeight: Dp, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .border(
                if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
                MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ChoiceTile(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    swatch: Brush? = null,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    Tile(selected, modifier, onClick, minHeight = 52.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (swatch != null) {
                Box(Modifier.size(width = 18.dp, height = 6.dp).clip(CircleShape).background(swatch))
                Spacer(Modifier.width(8.dp))
            }
            if (icon) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 세로 / 가로 with a small frame of that shape. */
@Composable
private fun AspectTile(label: String, aspect: Aspect, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Tile(selected, modifier, onClick, minHeight = 92.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val (w, h) = if (aspect == Aspect.PORTRAIT) 22.dp to 38.dp else 38.dp to 22.dp
            Box(
                modifier = Modifier.height(38.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = w, height = h)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) colors.primary else colors.outline),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The picked music's file name. */
@Composable
private fun MusicRow(name: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            name ?: "…",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(VideoTags.MUSIC_NAME),
        )
    }
}

/**
 * [VideoDialog.Message] with [확인]; "배터리가 부족합니다(N%). 계속할까요?" [계속] [취소]; "음악 파일을 읽을 수 없습니다. 음악
 * 없이 만들까요?" [음악 없이 만들기] [취소]; "영상을 만들지 못했습니다. 다시 만들까요?" [다시 만들기] [닫기].
 */
@Composable
fun VideoDialogs(
    dialog: VideoDialog?,
    onConfirmBattery: () -> Unit,
    onWithoutMusic: () -> Unit,
    onRetry: () -> Unit,
    onCloseUnfinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is VideoDialog.Message -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(dialog.text) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
        )
        is VideoDialog.LowBattery -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text("배터리가 부족합니다(${dialog.percent}%). 계속할까요?") },
            confirmButton = { TextButton(onClick = onConfirmBattery) { Text("계속") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
        VideoDialog.MusicUnreadable -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text("음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?") },
            confirmButton = { TextButton(onClick = onWithoutMusic) { Text("음악 없이 만들기") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
        // Dismissed without an answer: offered again at the next start.
        is VideoDialog.Unfinished -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text("영상을 만들지 못했습니다. 다시 만들까요?") },
            confirmButton = { TextButton(onClick = onRetry) { Text("다시 만들기") } },
            dismissButton = { TextButton(onClick = onCloseUnfinished) { Text("닫기") } },
        )
    }
}

/** V1.3.1 spec §1.4, verbatim. */
internal fun dataNoticeText(mb: Int) = "위성 지도 데이터 약 $mb MB가 듭니다 (Wi-Fi 권장)"

private val LENGTHS = listOf(
    "짧게(약 30초)" to VideoLength.SHORT,
    "보통(약 1분)" to VideoLength.NORMAL,
    "길게(약 2분)" to VideoLength.LONG,
)

/** The first ride day's colour (the video's [기본]). */
private val DEFAULT_SWATCH = Brush.horizontalGradient(
    List(2) { Color(android.graphics.Color.parseColor(RideColors.colorAt(0))) },
)

/** Blue to red, the heart-rate bands. */
private val HEART_SWATCH = Brush.horizontalGradient(
    listOf(HrBand.BLUE, HrBand.GREEN, HrBand.YELLOW, HrBand.ORANGE, HrBand.RED).map { Color(android.graphics.Color.parseColor(it.colorHex)) },
)

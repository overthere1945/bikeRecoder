package com.cowork.bikerecoder.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.ShareApps
import com.cowork.bikerecoder.share.ShareItem
import com.cowork.bikerecoder.share.ShareMode
import com.cowork.bikerecoder.share.ShareTarget

/** Test tags of the share sheet. */
object ShareTags {
    const val SHEET = "share-sheet"
    const val TEXT = "share-text"
    const val PROGRESS = "share-progress"

    fun button(target: ShareTarget) = "share-button-${target.name}"
}

/**
 * The "활동 공유하기" sheet (share cards spec §1; V1.5.0 spec §1.2) that opens fully: the mode, "보낼 것" (a plain row of
 * chips until the strip of share cards spec §1.2 comes), the mood chips, the text box with [처음 글로] and the stale line,
 * the "지나간 길" chips, the error with [다시 시도], and the buttons in two rows — those whose app is missing
 * ([ShareApps.available]) hidden, the others dimmed per [ShareSheetState.enabled]. While a press waits for its cards the
 * buttons give way to "카드를 만드는 중…" and [취소].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    state: ShareSheetState,
    apps: ShareApps,
    onEdit: (String) -> Unit,
    onReset: () -> Unit,
    onMood: (Mood) -> Unit,
    onPress: (ShareTarget) -> Unit,
    onMode: (ShareMode) -> Unit,
    onTap: (ShareItem) -> Unit,
    onPlace: (String) -> Unit,
    onCancelMaking: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
                .testTag(ShareTags.SHEET),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "활동 공유하기",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            ModeRow(state.mode, onMode)
            WhatIsSent(state, onTap)
            MoodChips(state.moods, onMood)
            TextBox(state, onEdit, onReset)
            PlaceChips(state, onPlace)
            ErrorLine(state.error, onRetry)
            if (state.waiting) MakingRow(onCancelMaking) else ShareButtons(state, apps, onPress)
        }
    }
}

/** One line of chips that scrolls sideways (spec §3.3); a chip is on while its hashtag stands in the text. */
@Composable
private fun MoodChips(moods: Set<Mood>, onMood: (Mood) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Mood.entries) { mood ->
            FilterChip(
                selected = mood in moods,
                onClick = { onMood(mood) },
                label = { Text("${mood.emoji} ${mood.word}") },
                modifier = Modifier.semantics { contentDescription = "감정: ${mood.word}" },
            )
        }
    }
}

/** The text box (up to 2,000 characters, the view model cuts), [처음 글로] at its bottom right and the stale line. */
@Composable
private fun TextBox(state: ShareSheetState, onEdit: (String) -> Unit, onReset: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = onEdit,
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag(ShareTags.TEXT),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.stale) {
                    Text(
                        "기록이 바뀌어 숫자가 다를 수 있습니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onReset, enabled = state.text != state.autoText) { Text("처음 글로") }
        }
    }
}

/** [게시물 4:5] [스토리 9:16] (share cards spec §1.1). */
@Composable
private fun ModeRow(mode: ShareMode, onMode: (ShareMode) -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == ShareMode.FEED, onClick = { onMode(ShareMode.FEED) }, label = { Text("게시물 4:5") })
        FilterChip(selected = mode == ShareMode.STORY, onClick = { onMode(ShareMode.STORY) }, label = { Text("스토리 9:16") })
    }
}

/**
 * "보낼 것" (share cards spec §1.2) as chips numbered in pick order; cards are dimmed on an unfinished trip, under
 * "여행을 마치면 카드를 만들 수 있습니다".
 */
@Composable
private fun WhatIsSent(state: ShareSheetState, onTap: (ShareItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("보낼 것", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp))
        if (!state.tripFinished) {
            Text(
                ShareViewModel.MSG_UNFINISHED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.items) { item ->
                val number = state.selection.chosen.indexOf(item) + 1
                FilterChip(
                    selected = number > 0,
                    onClick = { onTap(item) },
                    enabled = state.tripFinished || state.keyOf(item) == null,
                    label = { Text(if (number > 0) "${itemName(item)} · $number" else itemName(item)) },
                )
            }
        }
    }
}

private fun itemName(item: ShareItem): String = when (item) {
    ShareItem.SatelliteCard -> "위성 카드"
    ShareItem.PhotoCard -> "사진 카드"
    is ShareItem.Photo -> "사진 ${item.index}"
    is ShareItem.Video -> "영상"
}

/** "지나간 길" chips (share cards spec §4.5); no row without place tags. */
@Composable
private fun PlaceChips(state: ShareSheetState, onPlace: (String) -> Unit) {
    if (state.placeTags.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("지나간 길", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.placeTags) { tag ->
                FilterChip(selected = state.placeOn(tag), onClick = { onPlace(tag) }, label = { Text(tag) })
            }
        }
    }
}

/** Why the last press did not send, in the error colour, with [다시 시도] (share cards spec §6). */
@Composable
private fun ErrorLine(error: String?, onRetry: () -> Unit) {
    if (error == null) return
    Row(modifier = Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("다시 시도") }
    }
}

/** In place of the buttons while a press waits for its cards (share cards spec §3.1). */
@Composable
private fun MakingRow(onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("카드를 만드는 중…", style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(ShareTags.PROGRESS))
        TextButton(onClick = onCancel) { Text("취소") }
    }
}

/** The mode's two rows of share cards spec §1.3, in columns of four; hidden ones leave no gap. */
@Composable
private fun ShareButtons(state: ShareSheetState, apps: ShareApps, onPress: (ShareTarget) -> Unit) {
    // Looked up once per sheet: the package manager is asked, and the icons are drawn into bitmaps.
    val shown = remember(apps) { ShareTarget.entries.filter(apps::available).toSet() }
    val icons = remember(apps) {
        listOf(ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY)
            .associateWith { target -> apps.icon(target)?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap() }
    }
    Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rowsOf(state.mode)) {
            val targets = row.filter { it in shown }
            if (targets.isEmpty()) continue
            Row(modifier = Modifier.fillMaxWidth()) {
                for (index in 0 until COLUMNS) {
                    val target = targets.getOrNull(index)
                    if (target == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        ShareButton(
                            target = target,
                            appIcon = icons[target],
                            enabled = state.enabled(target),
                            onClick = { onPress(target) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** A round icon (the app's own for Facebook and Instagram, spec §1.2) over its name; dimmed when not [enabled]. */
@Composable
private fun ShareButton(
    target: ShareTarget,
    appIcon: ImageBitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DIMMED)
            .padding(vertical = 6.dp)
            .testTag(ShareTags.button(target)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (appIcon != null) {
                Image(appIcon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(materialIcon(target), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label(target), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

private fun label(target: ShareTarget): String = when (target) {
    ShareTarget.FACEBOOK -> "페이스북"
    ShareTarget.INSTAGRAM -> "인스타그램"
    ShareTarget.STORY -> "스토리"
    ShareTarget.EMAIL -> "이메일"
    ShareTarget.COPY -> "복사"
    ShareTarget.SAVE -> "저장"
    ShareTarget.MORE -> "더 보기"
    ShareTarget.STICKER -> "스티커"
}

/** The Material icons of spec §1.2; the app buttons fall back to the paper plane when their icon cannot be read. */
private fun materialIcon(target: ShareTarget): ImageVector = when (target) {
    ShareTarget.EMAIL -> Icons.Filled.Email
    ShareTarget.COPY -> Icons.Filled.ContentCopy
    ShareTarget.SAVE -> Icons.Filled.Download
    ShareTarget.MORE -> Icons.Filled.MoreHoriz
    ShareTarget.STICKER -> Icons.Filled.AutoAwesome
    ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY -> Icons.Filled.Share
}

/** Feed: [페이스북] [인스타그램] [이메일] [더 보기]; story: [페이스북] [스토리] [이메일] [더 보기]; then [복사] [저장] [스티커]. */
private fun rowsOf(mode: ShareMode) = listOf(
    listOf(
        ShareTarget.FACEBOOK,
        if (mode == ShareMode.FEED) ShareTarget.INSTAGRAM else ShareTarget.STORY,
        ShareTarget.EMAIL,
        ShareTarget.MORE,
    ),
    listOf(ShareTarget.COPY, ShareTarget.SAVE, ShareTarget.STICKER),
)
private const val COLUMNS = 4
private const val DIMMED = 0.38f

/** The app icons' bitmap side in px (56 dp at up to xxxhdpi). */
private const val ICON_PX = 224

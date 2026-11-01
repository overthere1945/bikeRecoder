package com.cowork.bikerecoder.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
    const val MODE_FEED = "share-mode-feed"
    const val MODE_STORY = "share-mode-story"
    const val PREVIEW = "share-preview"

    /** The card-shaped frame of a photo's preview. */
    const val PREVIEW_FRAME = "share-preview-frame"

    fun button(target: ShareTarget) = "share-button-${target.name}"

    /** The [i]th item (from 0) of the "보낼 것" strip. */
    fun item(i: Int) = "share-item-$i"

    /** The "지나간 길" chip of [tag]. */
    fun place(tag: String) = "share-place-$tag"
}

/**
 * The "활동 공유하기" sheet (share cards spec §1; V1.5.0 spec §1.2) that opens fully: the mode, the "보낼 것" strip (a long
 * press opens [SharePreview]), the mood chips, the text box with [처음 글로] and the stale line, the "지나간 길" chips, the
 * error with [다시 시도], and the buttons in two rows — those whose app is missing ([ShareApps.available]) hidden, the
 * others dimmed per [ShareSheetState.enabled]. While a press waits for its cards the buttons give way to
 * "카드를 만드는 중…" and [취소].
 *
 * [thumbnail] loads an item's strip picture (null: none), once per picture while the sheet is open ([pictureKey]);
 * [preview] its picture at a width in px each time the preview shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    state: ShareSheetState,
    apps: ShareApps,
    thumbnail: suspend (ShareItem, ShareSheetState) -> ImageBitmap?,
    preview: suspend (ShareItem, ShareSheetState, Int) -> ImageBitmap?,
    onEdit: (String) -> Unit,
    onReset: () -> Unit,
    onMood: (Mood) -> Unit,
    onPress: (ShareTarget) -> Unit,
    onMode: (ShareMode) -> Unit,
    onTap: (ShareItem) -> Unit,
    onPhotoCard: (Long) -> Unit,
    onPlace: (String) -> Unit,
    onCancelMaking: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentThumbnail by rememberUpdatedState(thumbnail)
    val pictures = remember(state.tripId) { SharePictureCache { item, s -> currentThumbnail(item, s) } }
    var previewing by remember(state.tripId) { mutableStateOf<ShareItem?>(null) }
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
            Strip(state, pictures, onTap, onLongPress = { previewing = it })
            MoodChips(state.moods, onMood)
            TextBox(state, onEdit, onReset)
            PlaceChips(state, onPlace)
            ErrorLine(state.error, onRetry)
            if (state.waiting) MakingRow(onCancelMaking) else ShareButtons(state, apps, onPress)
        }
    }
    // An item that left the strip meanwhile (an unreadable photo, spec §6) closes its preview.
    previewing?.takeIf { it in state.items }?.let { item ->
        SharePreview(
            item = item,
            state = state,
            pictures = pictures,
            preview = preview,
            onPhotoCard = onPhotoCard,
            onClose = { previewing = null },
        )
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

/** [게시물 4:5] [스토리 9:16] as one segmented row (share cards spec §1.1). */
@Composable
private fun ModeRow(mode: ShareMode, onMode: (ShareMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        MODES.forEachIndexed { i, (value, label, tag) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onMode(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = MODES.size),
                modifier = Modifier.testTag(tag),
            ) {
                Text(label)
            }
        }
    }
}

private val MODES = listOf(
    Triple(ShareMode.FEED, "게시물 4:5", ShareTags.MODE_FEED),
    Triple(ShareMode.STORY, "스토리 9:16", ShareTags.MODE_STORY),
)

/**
 * "보낼 것" (share cards spec §1.2): a sideways row of 72 dp squares — the picture, a number in pick order (a check in
 * story mode), a spinner while a card is drawn and a warning when it failed. The cards are dimmed on an unfinished trip,
 * under "여행을 마치면 카드를 만들 수 있습니다". A long press opens the preview.
 */
@Composable
private fun Strip(
    state: ShareSheetState,
    pictures: SharePictureCache,
    onTap: (ShareItem) -> Unit,
    onLongPress: (ShareItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            itemsIndexed(state.items, key = { _, item -> itemKey(item) }) { i, item ->
                StripItem(
                    index = i,
                    item = item,
                    state = state,
                    picture = pictures.picture(item, state),
                    onTap = { onTap(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@Composable
private fun StripItem(
    index: Int,
    item: ShareItem,
    state: ShareSheetState,
    picture: ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val number = state.selection.chosen.indexOf(item) + 1
    // A card cannot be drawn on an unfinished trip: neither picked nor previewed.
    val enabled = state.tripFinished || state.keyOf(item) == null
    val shape = MaterialTheme.shapes.small
    val name = itemName(item) + if (number > 0) ", ${number}번째" else ""
    Box(
        modifier = Modifier
            .size(ITEM_SIZE)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (number > 0) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLongPress)
            .alpha(if (enabled) 1f else DIMMED)
            .semantics {
                contentDescription = name
                selected = number > 0
            }
            .testTag(ShareTags.item(index)),
    ) {
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                placeholderIcon(item),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (item is ShareItem.Video && picture != null) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        when (state.cardOf(item)) {
            CardStatus.Rendering -> CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.Center), strokeWidth = 2.dp)
            is CardStatus.Failed -> Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Unit
        }
        if (number > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                val onBadge = MaterialTheme.colorScheme.onPrimary
                if (state.mode == ShareMode.STORY) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = onBadge, modifier = Modifier.size(14.dp))
                } else {
                    Text("$number", color = onBadge, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** The name the screen reader says (share cards spec §1.2). */
internal fun itemName(item: ShareItem): String = when (item) {
    ShareItem.SatelliteCard -> "위성 카드"
    ShareItem.PhotoCard -> "사진 카드"
    is ShareItem.Photo -> "사진 ${item.index}"
    is ShareItem.Video -> "영상"
}

private fun itemKey(item: ShareItem): String = when (item) {
    ShareItem.SatelliteCard -> "satellite"
    ShareItem.PhotoCard -> "photo-card"
    is ShareItem.Photo -> "photo-${item.mediaId}"
    is ShareItem.Video -> "video"
}

/** Shown while an item has no picture yet. */
private fun placeholderIcon(item: ShareItem): ImageVector = when (item) {
    ShareItem.SatelliteCard -> Icons.Filled.Map
    ShareItem.PhotoCard, is ShareItem.Photo -> Icons.Filled.Image
    is ShareItem.Video -> Icons.Filled.Videocam
}

/**
 * What an item's picture is, so it is loaded once while the sheet is open: a card's drawn file (null while it is not
 * drawn), a photo's id, the video's uri.
 */
internal fun pictureKey(item: ShareItem, state: ShareSheetState): String? = when (item) {
    ShareItem.SatelliteCard, ShareItem.PhotoCard -> (state.cardOf(item) as? CardStatus.Ready)?.file?.uri
    is ShareItem.Photo -> "photo:${item.mediaId}"
    is ShareItem.Video -> item.candidate.uri
}

/** The strip pictures loaded by [load] so far, by [pictureKey] (null: could not be read; not asked again). */
internal class SharePictureCache(private val load: suspend (ShareItem, ShareSheetState) -> ImageBitmap?) {
    private val loaded = mutableStateMapOf<String, ImageBitmap?>()

    /** [item]'s picture once loaded; the first call for a picture starts loading it. */
    @Composable
    fun picture(item: ShareItem, state: ShareSheetState): ImageBitmap? {
        val key = pictureKey(item, state)
        val current by rememberUpdatedState(state)
        LaunchedEffect(key) {
            if (key != null && key !in loaded) loaded[key] = load(item, current)
        }
        return key?.let { loaded[it] }
    }
}

/** "지나간 길" chips (share cards spec §4.5); no row without place tags. */
@Composable
private fun PlaceChips(state: ShareSheetState, onPlace: (String) -> Unit) {
    if (state.placeTags.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("지나간 길", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.placeTags) { tag ->
                FilterChip(
                    selected = state.placeOn(tag),
                    onClick = { onPlace(tag) },
                    label = { Text(tag) },
                    modifier = Modifier.testTag(ShareTags.place(tag)),
                )
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
private val ITEM_SIZE = 72.dp

/** The app icons' bitmap side in px (56 dp at up to xxxhdpi). */
private const val ICON_PX = 224

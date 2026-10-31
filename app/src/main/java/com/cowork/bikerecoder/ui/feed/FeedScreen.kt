package com.cowork.bikerecoder.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.ui.history.CONFIRM_DELETE_TRIP
import com.cowork.bikerecoder.ui.history.HistoryDialog
import com.cowork.bikerecoder.ui.history.HistoryDialogs
import com.cowork.bikerecoder.video.TripMedia

/**
 * The summary tab (V1.4.0 spec §2): the strip of this week, then the trips as big cards by month, and last "짧은 기록 N개 숨김".
 * [scrollToTop] counts the taps on the tab that is already open: each new value scrolls the list to the top. [onShare]: a card's
 * paper plane, the share sheet of its trip (V1.5.0 spec §1.1).
 */
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    thumbnails: PhotoThumbnails,
    onOpenTrip: (Long) -> Unit,
    onOpenVideo: (TripMedia) -> Unit,
    onOpenStatsWeek: () -> Unit,
    onStartRecording: () -> Unit,
    onSetGoals: () -> Unit,
    scrollToTop: Int,
    onShare: (tripId: Long) -> Unit = {},
) {
    val weekStrip by viewModel.weekStrip.collectAsStateWithLifecycle()
    val months by viewModel.months.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val hiddenShortCount by viewModel.hiddenShortCount.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    // Back from a trip whose segments were deleted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    FeedContent(
        weekStrip = weekStrip,
        months = months,
        ready = ready,
        hiddenShortCount = hiddenShortCount,
        dialog = dialog,
        photo = thumbnails::thumbnail,
        onOpenTrip = onOpenTrip,
        onOpenVideo = onOpenVideo,
        onOpenStatsWeek = onOpenStatsWeek,
        onStartRecording = onStartRecording,
        onSetGoals = onSetGoals,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismiss = viewModel::dismiss,
        onRequestCleanup = viewModel::requestCleanup,
        onConfirmCleanup = viewModel::confirmCleanup,
        onCardShown = viewModel::onCardShown,
        onCardHidden = viewModel::onCardHidden,
        scrollToTop = scrollToTop,
        onShare = onShare,
    )
}

/** The stateless feed: [FeedScreen] with the state and the callbacks spelled out. No strip while [weekStrip] is null (not read yet). */
@Composable
fun FeedContent(
    weekStrip: WeekStripState?,
    months: List<FeedMonth>,
    ready: Boolean,
    hiddenShortCount: Int,
    dialog: HistoryDialog?,
    photo: suspend (mediaId: Long, sizePx: Int) -> ImageBitmap?,
    onOpenTrip: (Long) -> Unit,
    onOpenVideo: (TripMedia) -> Unit,
    onOpenStatsWeek: () -> Unit,
    onStartRecording: () -> Unit,
    onSetGoals: () -> Unit,
    onRequestDelete: (Long) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
    onRequestCleanup: () -> Unit,
    onConfirmCleanup: () -> Unit,
    onCardShown: (Long) -> Unit = {},
    onCardHidden: (Long) -> Unit = {},
    scrollToTop: Int = 0,
    onShare: (tripId: Long) -> Unit = {},
) {
    FeedList(
        weekStrip, months, ready, hiddenShortCount, photo, onOpenTrip, onOpenVideo, onOpenStatsWeek,
        onStartRecording, onSetGoals, onRequestDelete, onRequestCleanup, onCardShown, onCardHidden, scrollToTop, onShare,
    )
    HistoryDialogs(dialog, CONFIRM_DELETE_TRIP, onConfirmDelete, onDismiss, onConfirmCleanup)
}

@Composable
private fun FeedList(
    weekStrip: WeekStripState?,
    months: List<FeedMonth>,
    ready: Boolean,
    hiddenShortCount: Int,
    photo: suspend (Long, Int) -> ImageBitmap?,
    onOpenTrip: (Long) -> Unit,
    onOpenVideo: (TripMedia) -> Unit,
    onOpenStatsWeek: () -> Unit,
    onStartRecording: () -> Unit,
    onSetGoals: () -> Unit,
    onRequestDelete: (Long) -> Unit,
    onRequestCleanup: () -> Unit,
    onCardShown: (Long) -> Unit,
    onCardHidden: (Long) -> Unit,
    scrollToTop: Int,
    onShare: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    TrackVisibleCards(listState, onCardShown, onCardHidden)
    val firstScrollToTop = remember { scrollToTop }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != firstScrollToTop) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag(FeedTags.LIST),
    ) {
        if (weekStrip != null) item(key = "weekStrip") { WeekStrip(weekStrip, onOpenStatsWeek, onSetGoals) }
        for (month in months) {
            item(key = "month:${month.header}") {
                Text(
                    month.header,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(month.cards, key = { it.tripId }) { card ->
                FeedCard(
                    card,
                    photo,
                    onClick = { onOpenTrip(card.tripId) },
                    onLongClick = { onRequestDelete(card.tripId) },
                    onOpenVideo = onOpenVideo,
                    onShare = { onShare(card.tripId) },
                )
            }
        }
        if (ready && months.isEmpty()) {
            item(key = "empty") { EmptyFeed(onStartRecording) }
        }
        if (hiddenShortCount > 0) {
            item(key = "hiddenShort") { HiddenShortRow(hiddenShortCount, onRequestCleanup) }
        }
    }
}

/**
 * Tells [onShown] / [onHidden] which trip cards enter and leave the list's visible items (spec §2.4: the pictures of the cards
 * on view are made first, and the waiting for one that left is dropped). Everything still on view is let go of when the list
 * leaves the screen.
 */
@Composable
private fun TrackVisibleCards(listState: LazyListState, onShown: (Long) -> Unit, onHidden: (Long) -> Unit) {
    val shown = remember { LinkedHashSet<Long>() }
    val currentShown by rememberUpdatedState(onShown)
    val currentHidden by rememberUpdatedState(onHidden)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet() }
            .collect { now ->
                for (tripId in now - shown) {
                    shown += tripId
                    currentShown(tripId)
                }
                for (tripId in shown - now) {
                    shown -= tripId
                    currentHidden(tripId)
                }
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            shown.toList().forEach { currentHidden(it) }
            shown.clear()
        }
    }
}

/** Spec §2.2: no trip at all. [기록 시작] only goes to the map tab; it does not start a recording. */
@Composable
private fun EmptyFeed(onStartRecording: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag(FeedTags.EMPTY),
    ) {
        Text("첫 주행을 기록해 보세요", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onStartRecording) { Text("기록 시작") }
    }
}

/** "짧은 기록 N개 숨김" [정리] at the bottom of the list (spec §6.10). */
@Composable
private fun HiddenShortRow(count: Int, onRequestCleanup: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "짧은 기록 ${count}개 숨김",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequestCleanup) { Text("정리") }
    }
}

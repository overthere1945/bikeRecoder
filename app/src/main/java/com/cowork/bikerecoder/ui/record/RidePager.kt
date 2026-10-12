package com.cowork.bikerecoder.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The two ride pages (the map, the numbers) of the record and guidance screens. Their pagers take no drags themselves
 * (userScrollEnabled = false): MapLibre's view never asks its parents not to intercept, so a scrolling pager would
 * claim every horizontal drag on the map and cancel it. Pages turn by swiping the areas marked [swipeTurnsPages] or by
 * tapping the [PageDots].
 */

internal const val RIDE_PAGE_COUNT = 2
internal const val RIDE_MAP_PAGE = 0

/** A horizontal swipe here turns the page (one page per swipe). */
internal fun Modifier.swipeTurnsPages(pager: PagerState, scope: CoroutineScope): Modifier = pointerInput(pager) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            val target = when {
                total < -SWIPE_THRESHOLD_PX -> pager.currentPage + 1
                total > SWIPE_THRESHOLD_PX -> pager.currentPage - 1
                else -> pager.currentPage
            }.coerceIn(0, RIDE_PAGE_COUNT - 1)
            if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
        },
    ) { change, dx ->
        total += dx
        change.consume()
    }
}

/** One tappable dot per page ("지도", "숫자"), the current one highlighted. */
@Composable
internal fun PageDots(current: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(RIDE_PAGE_COUNT) { page ->
            val name = if (page == RIDE_MAP_PAGE) "지도" else "숫자"
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onSelect(page) }
                    .semantics { contentDescription = name },
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (page == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
            }
        }
    }
}

private const val SWIPE_THRESHOLD_PX = 48f

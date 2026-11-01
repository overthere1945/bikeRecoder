package com.cowork.bikerecoder.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.activity.ActivityType

/** The activity types' colours, badge colours and icons (V1.9.0 spec §2.1): the only place the app defines them. */
object ActivityStyle {

    /** The badge's rounded corners. */
    val BADGE_CORNER: Dp = 10.dp

    /** How far the next badge of a mixed trip overlaps the one before it. */
    val BADGE_OVERLAP: Dp = 8.dp

    /** The activity's colour (light theme): selected chips, map lines. */
    fun color(type: ActivityType): Color = Color(argb(type))

    /**
     * The colour of text and icons on a [color] fill (the map screen's type button, the selected chips): white. Its contrast on
     * the four fills is 3.4–3.9 : 1 (see ActivityStyleTest), enough for bold text, not for AA body text.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onColor(type: ActivityType): Color = Color.White

    /** `#RRGGBB` of [color], for the map's line layers. */
    fun hex(type: ActivityType): String = "#%06X".format(argb(type) and 0xFFFFFF)

    /** The badge's (background, content) colours. */
    fun badgeColors(type: ActivityType, dark: Boolean): Pair<Color, Color> {
        val (light, night) = when (type) {
            ActivityType.BIKE -> (0xFFFAECE7 to 0xFF993C1D) to (0xFF712B13 to 0xFFF5C4B3)
            ActivityType.WALK -> (0xFFE1F5EE to 0xFF0F6E56) to (0xFF085041 to 0xFF9FE1CB)
            ActivityType.RUN -> (0xFFFBEAF0 to 0xFF993556) to (0xFF72243E to 0xFFF4C0D1)
            ActivityType.HIKE -> (0xFFEAF3DE to 0xFF3B6D11) to (0xFF27500A to 0xFFC0DD97)
        }
        val (background, content) = if (dark) night else light
        return Color(background) to Color(content)
    }

    /**
     * Whether a theme whose surface is [surface] is dark. Badges follow the app's theme, not the system setting: the
     * app may stay light while the phone is in dark mode.
     */
    fun isDark(surface: Color): Boolean = surface.luminance() < 0.5f

    fun icon(type: ActivityType): ImageVector = when (type) {
        ActivityType.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
        ActivityType.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        ActivityType.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
        ActivityType.HIKE -> Icons.Filled.Hiking
    }

    /** The screen reader's name of the activity ([ActivityText.name]). */
    fun name(type: ActivityType): String = ActivityText.name(type)

    private fun argb(type: ActivityType): Long = when (type) {
        ActivityType.BIKE -> 0xFFD85A30
        ActivityType.WALK -> 0xFF1D9E75
        ActivityType.RUN -> 0xFFD4537E
        ActivityType.HIKE -> 0xFF639922
    }
}

/**
 * The activity's icon on its badge colour in a rounded square of [size] (34 dp on cards, 24 dp in list rows, 18 dp in
 * chips). [contentDescription]: what a screen reader says (the activity's name; null where the name is already read).
 */
@Composable
fun ActivityBadge(
    type: ActivityType,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = ActivityStyle.name(type),
) {
    val (background, content) = ActivityStyle.badgeColors(type, ActivityStyle.isDark(MaterialTheme.colorScheme.surface))
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(ActivityStyle.BADGE_CORNER))
            .background(background),
    ) {
        Icon(
            ActivityStyle.icon(type),
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(size * ICON_RATIO),
        )
    }
}

/** A mixed trip's badges ([types] in order, at most three): each next one overlaps the one before by 8 dp. */
@Composable
fun ActivityBadges(types: List<ActivityType>, size: Dp, modifier: Modifier = Modifier) {
    val shown = types.take(MAX_BADGES)
    val step = size - ActivityStyle.BADGE_OVERLAP
    Box(modifier.size(width = size + step * (shown.size - 1).coerceAtLeast(0), height = size)) {
        shown.forEachIndexed { i, type ->
            // Drawn in order: a later badge lies over the right edge of the one before.
            ActivityBadge(type, size, Modifier.offset(x = step * i))
        }
    }
}

/**
 * The activity chips [자전거][걷기][달리기][등산] with a leading [전체] ([includeAll]) for the stats tab (spec §4.1; the map screen has
 * the type button since V1.9.1): only the [selected] one is filled with its activity colour. [selected] null is [전체], which is filled
 * with the theme's primary colour (it stands for no activity). [onSelect] gets the tapped activity, null for [전체].
 * Tags: [ActivityChipTags.chip].
 */
@Composable
fun ActivityFilterChips(
    selected: ActivityType?,
    onSelect: (ActivityType?) -> Unit,
    modifier: Modifier = Modifier,
    includeAll: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        if (includeAll) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(ActivityText.STATS_ALL) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .semantics { contentDescription = ActivityText.STATS_ALL }
                    .testTag(ActivityChipTags.chip(null)),
            )
        }
        ActivityType.entries.forEach { type -> ActivityChip(type, type == selected) { onSelect(type) } }
    }
}

@Composable
private fun ActivityChip(type: ActivityType, selected: Boolean, onClick: () -> Unit) {
    val name = ActivityStyle.name(type)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(name) },
        leadingIcon = { ActivityBadge(type, CHIP_BADGE, contentDescription = null) },
        colors = FilterChipDefaults.filterChipColors(
            // Readable on the map.
            containerColor = MaterialTheme.colorScheme.surface,
            selectedContainerColor = ActivityStyle.color(type),
            selectedLabelColor = Color.White,
        ),
        modifier = Modifier
            .semantics { contentDescription = name }
            .testTag(ActivityChipTags.chip(type)),
    )
}

/** Test tags of the activity chips. */
object ActivityChipTags {
    /** The chip of [type]; null is [전체]. */
    fun chip(type: ActivityType?) = "activity-chip-${type?.name ?: "ALL"}"
}

private const val MAX_BADGES = 3
private const val ICON_RATIO = 0.65f
private val CHIP_BADGE = 18.dp

package com.cowork.bikerecoder.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.activity.ActivityType

/**
 * The map screen's activity type button (V1.9.1 §A), left of [● 기록 시작]: a pill filled with the [selected] activity's colour,
 * its icon in a small light circle, its name and a ▾. A tap opens [ActivityTypeSheet]; a row there calls [onSelect] and closes
 * the sheet. A screen reader says "활동 종류, 걷기. 눌러서 바꾸기" ([ActivityText.typeButtonDescription]) as a button.
 * The button is as high as the row it stands in (give it [Modifier.fillMaxHeight][androidx.compose.foundation.layout.fillMaxHeight]
 * there). Tags: [ActivityTypeTags].
 */
@Composable
fun ActivityTypeButton(selected: ActivityType, onSelect: (ActivityType) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val badge = ActivityStyle.badgeColors(selected, dark = false)
    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(50),
        color = ActivityStyle.color(selected),
        contentColor = ActivityStyle.onColor(selected),
        shadowElevation = 6.dp,
        modifier = modifier
            .semantics {
                role = Role.Button
                contentDescription = ActivityText.typeButtonDescription(selected)
            }
            .testTag(ActivityTypeTags.BUTTON),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            // The same light colours as the badge, in a circle.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(TYPE_BUTTON_ICON_CIRCLE).clip(CircleShape).background(badge.first),
            ) {
                Icon(ActivityStyle.icon(selected), contentDescription = null, tint = badge.second, modifier = Modifier.size(20.dp))
            }
            Text(ActivityStyle.name(selected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
    if (open) {
        ActivityTypeSheet(selected, onSelect = onSelect, onDismiss = { open = false })
    }
}

/**
 * The sheet "활동 종류" with a row for each activity (V1.9.1 §A): its badge, its name and a ✓ on the [selected] one, whose row is
 * tinted with the activity's light badge colour. A row (at least 56 dp high, read as a selected radio button) calls [onSelect],
 * then [onDismiss]; so does a swipe down or a tap outside. Tags: [ActivityTypeTags].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityTypeSheet(selected: ActivityType, onSelect: (ActivityType) -> Unit, onDismiss: () -> Unit) {
    val dark = ActivityStyle.isDark(MaterialTheme.colorScheme.surface)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().testTag(ActivityTypeTags.SHEET)) {
            Text(
                ActivityText.TYPE_SHEET_TITLE,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ActivityType.entries.forEach { type ->
                val current = type == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(if (current) ActivityStyle.badgeColors(type, dark).first else Color.Transparent)
                        .selectable(
                            selected = current,
                            role = Role.RadioButton,
                            onClick = {
                                onSelect(type)
                                onDismiss()
                            },
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag(ActivityTypeTags.row(type)),
                ) {
                    ActivityBadge(type, SHEET_BADGE, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        ActivityStyle.name(type),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (current) Icon(Icons.Filled.Check, contentDescription = null, tint = ActivityStyle.badgeColors(type, dark).second)
                }
            }
        }
    }
}

/** Test tags of the activity type button and its sheet. */
object ActivityTypeTags {
    const val BUTTON = "activity-type-button"
    const val SHEET = "activity-type-sheet"

    /** The sheet's row of [type]. */
    fun row(type: ActivityType) = "activity-type-row-${type.name}"
}

private val TYPE_BUTTON_ICON_CIRCLE = 28.dp
private val SHEET_BADGE = 40.dp

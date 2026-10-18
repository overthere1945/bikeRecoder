package com.cowork.bikerecoder.ui.favorite

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind

object FavoriteTags {
    const val ROW = "favorite-row"

    fun chip(id: Long) = "favorite-chip-$id"
}

/**
 * The saved places as chips: home, work, then the custom ones, scrolling sideways. Nothing is drawn without
 * favorites. A tap is [onSelect]; a long press opens the menu: [이름 바꾸기] (custom ones only) and [삭제].
 */
@Composable
fun FavoriteChips(
    favorites: List<Favorite>,
    onSelect: (Favorite) -> Unit,
    onRename: (Favorite) -> Unit,
    onDelete: (Favorite) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favorites.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(FavoriteTags.ROW),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        favorites.forEach { favorite ->
            FavoriteChip(favorite, onSelect = onSelect, onRename = onRename, onDelete = onDelete)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(
    favorite: Favorite,
    onSelect: (Favorite) -> Unit,
    onRename: (Favorite) -> Unit,
    onDelete: (Favorite) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        // FilterChip does not receive long presses, hence Surface + combinedClickable.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier
                .testTag(FavoriteTags.chip(favorite.id))
                .combinedClickable(
                    onClickLabel = "목적지로 설정",
                    onClick = { onSelect(favorite) },
                    onLongClickLabel = "메뉴",
                    onLongClick = { menuOpen = true },
                ),
        ) {
            Text(
                favorite.chipLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.heightIn(min = 20.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // Home and work have fixed names; only custom favorites can be renamed.
            if (favorite.kind == FavoriteKind.CUSTOM) {
                DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = {
                    menuOpen = false
                    onRename(favorite)
                })
            }
            DropdownMenuItem(text = { Text("삭제") }, onClick = {
                menuOpen = false
                onDelete(favorite)
            })
        }
    }
}

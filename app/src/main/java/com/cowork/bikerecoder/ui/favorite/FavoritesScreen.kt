package com.cowork.bikerecoder.ui.favorite

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.ui.common.GlyphIcon

object FavoritesTags {
    /** [＋ 즐겨찾기 추가], the first line. */
    const val ADD = "favorites-add"
    const val EMPTY = "favorites-empty"

    fun row(id: Long) = "favorites-row-$id"

    /** The [⋮] of a row. */
    fun menu(id: Long) = "favorites-menu-$id"
}

/**
 * "즐겨찾기" (spec §8.2): [＋ 즐겨찾기 추가] first, then the custom [favorites] (the caller passes custom ones only, in
 * creation order) with name and address, or "등록한 즐겨찾기가 없습니다". A tap is [onSelect]; [⋮] or a long press opens
 * [이름 바꾸기] [삭제].
 */
@Composable
fun FavoritesScreen(
    favorites: List<Favorite>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (Favorite) -> Unit,
    onRename: (Favorite) -> Unit,
    onDelete: (Favorite) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Text("즐겨찾기", style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "＋ 즐겨찾기 추가",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FavoritesTags.ADD)
                            .clickable(role = Role.Button, onClick = onAdd)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    HorizontalDivider()
                }
                if (favorites.isEmpty()) {
                    item {
                        Text(
                            "등록한 즐겨찾기가 없습니다",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp).testTag(FavoritesTags.EMPTY),
                        )
                    }
                }
                items(favorites, key = { it.id }) { favorite ->
                    FavoriteRow(favorite, onSelect = onSelect, onRename = onRename, onDelete = onDelete)
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    favorite: Favorite,
    onSelect: (Favorite) -> Unit,
    onRename: (Favorite) -> Unit,
    onDelete: (Favorite) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FavoritesTags.row(favorite.id))
            .combinedClickable(
                onClickLabel = "목적지로 설정",
                onClick = { onSelect(favorite) },
                onLongClickLabel = "메뉴",
                onLongClick = { menuOpen = true },
            )
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(favorite.name, style = MaterialTheme.typography.bodyLarge)
            favorite.address?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(FavoritesTags.menu(favorite.id))) {
                GlyphIcon("⋮", "메뉴")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = {
                    menuOpen = false
                    onRename(favorite)
                })
                DropdownMenuItem(text = { Text("삭제") }, onClick = {
                    menuOpen = false
                    onDelete(favorite)
                })
            }
        }
    }
}

package com.cowork.bikerecoder.ui.favorite

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.cowork.bikerecoder.favorite.FavoriteKind

object FavoriteTags {
    const val ROW = "favorite-row"

    fun chip(id: Long) = "favorite-chip-$id"

    /** The [★ 즐겨찾기] chip: opens the favorites screen. */
    const val ALL = "favorite-all"

    /** The [🏠 집 등록] / [🏢 회사 등록] chip. */
    fun register(kind: FavoriteKind) = "favorite-register-${kind.name.lowercase()}"
}

/**
 * The chip row (spec §8.1): home, work, then [★ 즐겨찾기], scrolling sideways. A home or work tap is [onSelect]; a long
 * press opens its menu, [삭제] only. Custom favorites are not chips: [★ 즐겨찾기] (always shown, no menu) is
 * [onOpenFavorites], their screen. With [onRegister] (the map main, spec §7.4) a missing home or work is a
 * [🏠 집 등록] / [🏢 회사 등록] chip in its place, with no menu. The row is only as wide as its chips (the map around it
 * keeps its pans and long presses); each chip is a 48 dp touch target.
 */
@Composable
fun FavoriteChips(
    favorites: List<Favorite>,
    onSelect: (Favorite) -> Unit,
    onDelete: (Favorite) -> Unit,
    onOpenFavorites: () -> Unit,
    modifier: Modifier = Modifier,
    onRegister: ((FavoriteKind) -> Unit)? = null,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).testTag(FavoriteTags.ROW),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Home → work → [★ 즐겨찾기]: a register chip stands where its favorite would be.
        listOf(FavoriteKind.HOME, FavoriteKind.WORK).forEach { kind ->
            val saved = favorites.filter { it.kind == kind }
            if (saved.isEmpty()) {
                if (onRegister != null) RegisterChip(kind, onRegister)
            } else {
                saved.forEach { favorite -> FavoriteChip(favorite, onSelect = onSelect, onDelete = onDelete) }
            }
        }
        AllFavoritesChip(onOpenFavorites)
    }
}

/** Outlined, in the muted text color, so it reads as an empty slot rather than a saved place. */
@Composable
private fun RegisterChip(kind: FavoriteKind, onRegister: (FavoriteKind) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 2.dp,
        modifier = Modifier
            .testTag(FavoriteTags.register(kind))
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = { onRegister(kind) }),
    ) {
        Text(
            if (kind == FavoriteKind.HOME) "🏠 집 등록" else "🏢 회사 등록",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.heightIn(min = 20.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AllFavoritesChip(onOpenFavorites: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier
            .testTag(FavoriteTags.ALL)
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onOpenFavorites),
    ) {
        Text(
            "★ 즐겨찾기",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.heightIn(min = 20.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** A home or work chip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(
    favorite: Favorite,
    onSelect: (Favorite) -> Unit,
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
                // Riders tap with gloves.
                .minimumInteractiveComponentSize()
                .combinedClickable(
                    role = Role.Button,
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
        // Home and work have fixed names: no [이름 바꾸기] (custom ones are renamed on the favorites screen).
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("삭제") }, onClick = {
                menuOpen = false
                onDelete(favorite)
            })
        }
    }
}

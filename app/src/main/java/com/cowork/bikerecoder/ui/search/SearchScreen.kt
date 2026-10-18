package com.cowork.bikerecoder.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.ui.common.GlyphIcon
import com.cowork.bikerecoder.ui.common.PlaceActionSheet

object SearchTags {
    const val QUERY = "search-query"
    const val RESULTS = "search-results"
    const val RESULTS_AREA = "search-results-area"
    const val RECENT_HEADER = "search-recent-header"
    const val RECENT_CLEAR = "search-recent-clear"

    /** [📍 현재 위치], the first line of the results area in the register mode. */
    const val CURRENT_LOCATION = "search-current-location"

    /** [🗺 지도에서 선택], the first line of the results area (after [CURRENT_LOCATION]). */
    const val MAP_PICK = "search-map-pick"
    fun recent(index: Int) = "search-recent-$index"
    fun recentRemove(index: Int) = "search-recent-remove-$index"
}

/**
 * Hides the keyboard and releases focus when the user starts dragging the list. State-driven scrolls (scrollToItem)
 * leave it up; semantics scrolls (accessibility, and the test API's performScrollTo*) arrive as user input and hide it.
 */
@Composable
internal fun rememberHideKeyboardOnDrag(): NestedScrollConnection {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return remember(keyboard, focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available != Offset.Zero) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return Offset.Zero
            }
        }
    }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onSetDestination: (name: String, point: GeoPoint) -> Unit,
    onAddWaypoint: (name: String, point: GeoPoint) -> Unit,
    /** The favorite chips, shown under the search row while the query is empty. */
    favoriteChips: @Composable () -> Unit = {},
    /** [즐겨찾기에 저장] on the result sheet; null hides it. */
    onSaveFavorite: ((FavoritePlace) -> Unit)? = null,
    /**
     * The register mode ([SearchScreenState.register]): a tapped place is saved as that home or work, or named as a
     * custom favorite, with no sheet.
     */
    onRegister: (FavoritePlace, FavoriteKind) -> Unit = { _, _ -> },
    /**
     * [🗺 지도에서 선택] (spec §8.5): opens the pick screen; its result comes back through [SearchViewModel.onMapPicked].
     * Null hides the line.
     */
    onPickOnMap: (() -> Unit)? = null,
    /** [📍 현재 위치] (spec §8.3, the register mode): register the current location. Null hides the line. */
    onCurrentLocation: (() -> Unit)? = null,
    /** The current location's address is being looked up: that line shows "현재 위치를 확인하는 중…" with a spinner. */
    locatingCurrent: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    // One path for a tapped search result or recent place; it is remembered as a recent place either way.
    val onPlaceTap: (Place) -> Unit = { place ->
        viewModel.select(place)
        state.register?.let { kind -> onRegister(place.toFavoritePlace(), kind) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                TextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = {
                        Text(
                            when (state.register) {
                                FavoriteKind.HOME -> "집 검색"
                                FavoriteKind.WORK -> "회사 검색"
                                FavoriteKind.CUSTOM -> "즐겨찾기 검색"
                                null -> "장소, 주소 검색"
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* results follow typing */ }),
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag(SearchTags.QUERY),
                )
            }
            // The register mode has no chips: it is choosing a place, not going to one.
            if (state.query.isEmpty() && state.register == null) {
                favoriteChips()
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().imePadding().testTag(SearchTags.RESULTS_AREA)) {
                // Under the chips (none in the register mode), above the recent places or the results; shown with a
                // query too.
                if (onCurrentLocation != null) {
                    if (locatingCurrent) {
                        LoadingLine("현재 위치를 확인하는 중…", SearchTags.CURRENT_LOCATION)
                    } else {
                        ActionLine("📍 현재 위치", SearchTags.CURRENT_LOCATION, onCurrentLocation)
                    }
                }
                if (onPickOnMap != null) ActionLine("🗺 지도에서 선택", SearchTags.MAP_PICK, onPickOnMap)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { SearchResultsArea(state, onPlaceTap, viewModel) }
            }
        }
    }

    state.selected?.let { place ->
        PlaceActionSheet(
            title = place.name,
            // A place picked on the map is named by its address: not shown twice.
            subtitle = place.address.ifBlank { null }?.takeIf { it != place.name },
            onSetDestination = {
                viewModel.dismissSelection()
                onSetDestination(place.name, place.point)
            },
            onAddWaypoint = {
                viewModel.dismissSelection()
                onAddWaypoint(place.name, place.point)
            },
            onDismiss = viewModel::dismissSelection,
            onSaveFavorite = onSaveFavorite?.let { save ->
                {
                    viewModel.dismissSelection()
                    save(place.toFavoritePlace())
                }
            },
        )
    }
}

/** One full-width tappable line at the top of the results area, with its divider. */
@Composable
private fun ActionLine(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
    HorizontalDivider()
}

/** [ActionLine]'s place while its action runs: a spinner and [text], not tappable. */
@Composable
private fun LoadingLine(text: String, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider()
}

/** The recent places while the query is empty, else the search state for it. */
@Composable
private fun BoxScope.SearchResultsArea(state: SearchScreenState, onPlaceTap: (Place) -> Unit, viewModel: SearchViewModel) {
    if (state.query.isEmpty()) {
        RecentPlaces(
            places = state.recent,
            onTap = onPlaceTap,
            onRemove = viewModel::removeRecent,
            onClear = viewModel::clearRecent,
        )
    } else when (val result = state.result) {
        SearchUiState.Idle -> Unit
        SearchUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(24.dp))
        is SearchUiState.Error -> Text(
            result.message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
        is SearchUiState.Results -> if (result.places.isEmpty()) {
            Text("검색 결과가 없습니다", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(rememberHideKeyboardOnDrag()).testTag(SearchTags.RESULTS),
            ) {
                items(result.places) { place ->
                    PlaceRow(place, onClick = { onPlaceTap(place) })
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun Place.toFavoritePlace() = FavoritePlace(name, point, address.ifBlank { null })

/** "최근 검색" (spec §7.3): nothing at all while the list is empty. Rows show no distance (it was measured then). */
@Composable
private fun RecentPlaces(places: List<Place>, onTap: (Place) -> Unit, onRemove: (Place) -> Unit, onClear: () -> Unit) {
    if (places.isEmpty()) return
    LazyColumn(modifier = Modifier.fillMaxSize().nestedScroll(rememberHideKeyboardOnDrag())) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)
                    // The title text belongs to the tagged node; [전체 삭제] stays its own clickable node.
                    .semantics(mergeDescendants = true) {}
                    .testTag(SearchTags.RECENT_HEADER),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("최근 검색", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, modifier = Modifier.testTag(SearchTags.RECENT_CLEAR)) { Text("전체 삭제") }
            }
        }
        itemsIndexed(places) { index, place ->
            PlaceRow(
                place.copy(distanceM = null),
                onClick = { onTap(place) },
                modifier = Modifier.testTag(SearchTags.recent(index)),
                trailing = {
                    IconButton(onClick = { onRemove(place) }, modifier = Modifier.testTag(SearchTags.recentRemove(index))) {
                        GlyphIcon("✕", "지우기")
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PlaceRow(
    place: Place,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
            if (place.address.isNotBlank() && place.address != place.name) {
                Text(place.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        place.distanceM?.let { Text(SummaryFormatter.distance(it.toDouble()), style = MaterialTheme.typography.labelLarge) }
        trailing?.invoke()
    }
}

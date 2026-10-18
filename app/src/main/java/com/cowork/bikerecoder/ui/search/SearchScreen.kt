package com.cowork.bikerecoder.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.ui.common.GlyphIcon
import com.cowork.bikerecoder.ui.common.PlaceActionSheet

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onSetDestination: (name: String, point: GeoPoint) -> Unit,
    onAddWaypoint: (name: String, point: GeoPoint) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                TextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text("장소, 주소 검색") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* results follow typing */ }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val result = state.result) {
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
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(result.places) { place ->
                                PlaceRow(place, onClick = { viewModel.select(place) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.selected?.let { place ->
        PlaceActionSheet(
            title = place.name,
            subtitle = place.address.ifBlank { null },
            onSetDestination = {
                viewModel.dismissSelection()
                onSetDestination(place.name, place.point)
            },
            onAddWaypoint = {
                viewModel.dismissSelection()
                onAddWaypoint(place.name, place.point)
            },
            onDismiss = viewModel::dismissSelection,
        )
    }
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
            if (place.address.isNotBlank()) {
                Text(place.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        place.distanceM?.let { Text(SummaryFormatter.distance(it.toDouble()), style = MaterialTheme.typography.labelLarge) }
    }
}

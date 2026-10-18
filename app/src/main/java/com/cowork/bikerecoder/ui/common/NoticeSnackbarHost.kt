package com.cowork.bikerecoder.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.cowork.bikerecoder.ride.ScreenNotice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull

/**
 * The recording's on-screen notices ([com.cowork.bikerecoder.ride.RideController.notice]), used by the record and the
 * guidance screens. [notice]가 오면 먼저 onShown(id)을 부르고 그 글자를 SnackbarDuration.Short(≈ 4초)로 한 번 보인다.
 * Reporting it shown clears [notice] (null), which does not cut the snackbar short; a newer notice replaces it.
 */
@Composable
fun NoticeSnackbarHost(notice: ScreenNotice?, onShown: (Long) -> Unit, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    val currentNotice by rememberUpdatedState(notice)
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(host) {
        snapshotFlow { currentNotice }
            .filterNotNull()
            .distinctUntilChangedBy { it.id }
            .collectLatest { shown ->
                currentOnShown(shown.id)
                host.showSnackbar(shown.text, duration = SnackbarDuration.Short)
            }
    }
    SnackbarHost(host, modifier = modifier)
}

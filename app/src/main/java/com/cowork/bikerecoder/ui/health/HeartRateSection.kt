package com.cowork.bikerecoder.ui.health

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.post.HealthAvailability
import com.cowork.bikerecoder.post.HeartRateSource
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ui.post.HeartRatePermissionPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val HEALTH_CONNECT_UNAVAILABLE = "Health Connect를 사용할 수 없습니다"

/** Whether the watch heart rate can be read now (spec §3.4, ruling R8). */
enum class HeartRateAccess {
    /** Not known yet. */
    CHECKING,

    /** Health Connect is there and heart rate may be read (ruling R6: READ_HEART_RATE). */
    GRANTED,

    /** Health Connect is there, the heart-rate permission is not. */
    NO_PERMISSION,

    /** Health Connect is missing or must be updated. */
    UNAVAILABLE,
}

/**
 * What the heart-rate parts of a screen need from the app: Health Connect ([source]), the "don't ask again" flag
 * ([settings]) and what to do once heart rate may be read ([onGranted], a post-processing pass).
 */
class HeartRateGate(val source: HeartRateSource, val settings: SettingsRepository, val onGranted: () -> Unit)

/** [value]: the access as last read by [refresh]. Without a gate it is always [HeartRateAccess.GRANTED]. */
@Stable
class HeartRateAccessState internal constructor(private val gate: HeartRateGate?) {
    var value by mutableStateOf(if (gate == null) HeartRateAccess.GRANTED else HeartRateAccess.CHECKING)
        private set

    /** Reads the access again; when heart rate may now be read and could not before, calls [HeartRateGate.onGranted]. */
    suspend fun refresh(): HeartRateAccess {
        val source = gate?.source ?: return value
        val now = try {
            when {
                source.availability() != HealthAvailability.AVAILABLE -> HeartRateAccess.UNAVAILABLE
                source.hasPermissions() -> HeartRateAccess.GRANTED
                else -> HeartRateAccess.NO_PERMISSION
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not check Health Connect", e)
            HeartRateAccess.UNAVAILABLE
        }
        val before = value
        value = now
        if (now == HeartRateAccess.GRANTED &&
            (before == HeartRateAccess.NO_PERMISSION || before == HeartRateAccess.UNAVAILABLE)
        ) {
            gate.onGranted()
        }
        return now
    }
}

/** The access for [gate], read whenever the screen is resumed (e.g. back from Health Connect or its permission screen). */
@Composable
fun rememberHeartRateAccess(gate: HeartRateGate?): HeartRateAccessState {
    val state = remember(gate) { HeartRateAccessState(gate) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(state) {
        val job = scope.launch { state.refresh() }
        onPauseOrDispose { job.cancel() }
    }
    return state
}

/**
 * The heart-rate part of a segment summary or detail (spec §3.3, §3.4) with its permission flow ([gate]; null = no
 * checks, the row as it is).
 */
@Composable
fun HeartRateSection(extras: SegmentExtras?, onRefetch: () -> Unit, gate: HeartRateGate?) {
    val access = rememberHeartRateAccess(gate)
    HeartRateSection(extras, access.value, onRefetch) {
        if (gate != null) HeartRatePermissionPrompt(gate, access)
    }
}

/**
 * [HeartRateRow] when heart rate may be read. Without the permission [permissionCard], with Health Connect missing
 * "Health Connect를 사용할 수 없습니다", in place of "심박 가져오는 중" (ruling R8: the row would wait for nothing);
 * heart rate already read (any other state) is still shown above them.
 */
@Composable
fun HeartRateSection(
    extras: SegmentExtras?,
    access: HeartRateAccess,
    onRefetch: () -> Unit,
    permissionCard: @Composable () -> Unit,
) {
    val waiting = extras == null || extras.hrStatus == HrStatus.PENDING
    if (access == HeartRateAccess.GRANTED || !waiting) HeartRateRow(extras, onRefetch)
    HeartRateNotice(access, permissionCard)
}

/**
 * Only the card or the line of [HeartRateSection], e.g. for the trip detail (spec §3.4); [access] is the screen's one
 * state (so the permission flow runs once and the screen can read it too).
 */
@Composable
fun HeartRateNotice(gate: HeartRateGate?, access: HeartRateAccessState) {
    if (gate == null) return
    HeartRateNotice(access.value) { HeartRatePermissionPrompt(gate, access) }
}

@Composable
private fun HeartRateNotice(access: HeartRateAccess, permissionCard: @Composable () -> Unit) {
    when (access) {
        HeartRateAccess.NO_PERMISSION -> permissionCard()
        HeartRateAccess.UNAVAILABLE -> Text(
            HEALTH_CONNECT_UNAVAILABLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HeartRateAccess.CHECKING, HeartRateAccess.GRANTED -> Unit
    }
}

private const val TAG = "HeartRateAccess"

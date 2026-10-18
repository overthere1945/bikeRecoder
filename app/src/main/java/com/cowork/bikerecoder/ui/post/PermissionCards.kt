package com.cowork.bikerecoder.ui.post

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.post.PhotoAccess
import com.cowork.bikerecoder.post.PhotoSource
import com.cowork.bikerecoder.ui.health.HeartRateAccess
import com.cowork.bikerecoder.ui.health.HeartRateAccessState
import com.cowork.bikerecoder.ui.health.HeartRateGate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

const val PHOTO_PROMPT_NONE = "주행 중 찍은 사진을 경로에 넣으려면 사진 접근을 허용해 주세요"
const val PHOTO_PROMPT_PARTIAL = "선택한 사진만 찾을 수 있습니다. 모든 사진을 허용하면 자동으로 찾습니다"
const val HEART_RATE_PROMPT = "워치 심박을 가져오려면 Health Connect 접근을 허용해 주세요"

/**
 * The photo card (spec §2.4). [PhotoAccess.NONE]: why, [허용] ([onAllow]) [나중에] ([onLater]).
 * [PhotoAccess.PARTIAL] ("선택한 사진만 허용"): only the selected photos are found, [설정 열기] ([onOpenSettings]) [닫기]
 * ([onLater]). Nothing with all photos allowed.
 */
@Composable
fun PhotoPermissionCard(access: PhotoAccess, onAllow: () -> Unit, onLater: () -> Unit, onOpenSettings: () -> Unit) {
    when (access) {
        PhotoAccess.FULL -> Unit
        PhotoAccess.NONE -> PromptCard(PHOTO_PROMPT_NONE, "허용" to onAllow, "나중에" to onLater)
        PhotoAccess.PARTIAL -> PromptCard(PHOTO_PROMPT_PARTIAL, "설정 열기" to onOpenSettings, "닫기" to onLater)
    }
}

/** The heart-rate card (spec §3.4): why, [허용] ([onAllow]) [나중에] ([onLater]). */
@Composable
fun HeartRatePermissionCard(onAllow: () -> Unit, onLater: () -> Unit) {
    PromptCard(HEART_RATE_PROMPT, "허용" to onAllow, "나중에" to onLater)
}

@Composable
private fun PromptCard(text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = secondary.second) { Text(secondary.first) }
                Button(onClick = primary.second) { Text(primary.first) }
            }
        }
    }
}

/** [value]: the photo access as last read by [refresh]. */
@Stable
class PhotoAccessState internal constructor(private val source: PhotoSource) {
    var value by mutableStateOf(source.access())
        private set

    /** Reads the access again; true when it now allows more than before. */
    fun refresh(): Boolean {
        val now = source.access()
        val more = accessRank(now) > accessRank(value)
        value = now
        return more
    }
}

/**
 * The photo access, read again whenever the screen comes back (e.g. from the app's settings page); more access than
 * before calls [onAccessGained] (a post-processing pass).
 */
@Composable
fun rememberPhotoAccess(photoSource: PhotoSource, onAccessGained: () -> Unit): PhotoAccessState {
    val state = remember(photoSource) { PhotoAccessState(photoSource) }
    val currentOnAccessGained by rememberUpdatedState(onAccessGained)
    LifecycleResumeEffect(state) {
        if (state.refresh()) currentOnAccessGained()
        onPauseOrDispose { }
    }
    return state
}

/**
 * The photo [허용] flow (spec §2.4): asks for [photoPermissionsFor] and, once photos may be read,
 * `ACCESS_MEDIA_LOCATION` (for the photos' GPS position), then [onAccessGained]. Still no access → [onRefused].
 * Returns what [허용] calls.
 */
@Composable
fun rememberPhotoPermissionRequest(
    access: PhotoAccessState,
    onAccessGained: () -> Unit,
    onRefused: () -> Unit,
): () -> Unit {
    val currentOnAccessGained by rememberUpdatedState(onAccessGained)
    val currentOnRefused by rememberUpdatedState(onRefused)
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        currentOnAccessGained()
    }
    val photosPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access.refresh()
        if (access.value == PhotoAccess.NONE) currentOnRefused() else mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    return remember(photosPermission) { { photosPermission.launch(photoPermissionsFor(Build.VERSION.SDK_INT)) } }
}

/** This app's page in the system settings, where "선택한 사진만" can become all photos. */
fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("PermissionCards", "No app details screen", e)
    }
}

/**
 * Health Connect's own settings, where this app's permissions can be given when Health Connect no longer shows its
 * permission screen. (Its per-app permission page needs `GRANT_RUNTIME_PERMISSIONS`, so this opens the settings home.)
 */
fun openHealthConnectSettings(context: Context) {
    try {
        context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        Log.w("PermissionCards", "No Health Connect settings screen", e)
    } catch (e: SecurityException) {
        Log.w("PermissionCards", "Health Connect settings screen refused", e)
    }
}

/**
 * The heart-rate [허용] flow (spec §3.4): asks Health Connect for [HeartRateGate.source]'s permissions; [access] is then
 * read again (which calls [HeartRateGate.onGranted] once heart rate may be read), still not allowed → [onRefused].
 * Returns what [허용] calls.
 */
@Composable
fun rememberHeartRatePermissionRequest(
    gate: HeartRateGate,
    access: HeartRateAccessState,
    onRefused: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnRefused by rememberUpdatedState(onRefused)
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { if (access.refresh() != HeartRateAccess.GRANTED) currentOnRefused() }
    }
    return remember(request, gate) {
        {
            try {
                request.launch(gate.source.permissions)
            } catch (e: ActivityNotFoundException) {
                Log.w("PermissionCards", "No Health Connect permission screen", e)
            }
        }
    }
}

/**
 * The photo card where it is needed, with its whole flow (spec §2.4, "처음 쓸 때"): shown while photo access is not
 * full and the card was not dismissed ([com.cowork.bikerecoder.data.AppSettings.photoPromptDismissed]).
 * [허용] runs [rememberPhotoPermissionRequest]. [나중에], [닫기] or a refusal dismiss it for good (settings
 * "사진·심박 연결" can ask again). The access is read again whenever the screen comes back ([rememberPhotoAccess]).
 */
@Composable
fun PhotoPermissionPrompt(photoSource: PhotoSource, settings: SettingsRepository, onAccessGained: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dismissedFlow = remember(settings) { settings.settings.map { it.photoPromptDismissed } }
    // Unknown until read: no card flashes up before the stored answer is known.
    val dismissed by dismissedFlow.collectAsStateWithLifecycle(initialValue = null)
    val access = rememberPhotoAccess(photoSource, onAccessGained)
    val dismiss: () -> Unit = { scope.launch { settings.update { it.copy(photoPromptDismissed = true) } } }
    val allow = rememberPhotoPermissionRequest(access, onAccessGained, onRefused = dismiss)

    if (dismissed != false || access.value == PhotoAccess.FULL) return
    PhotoPermissionCard(
        access = access.value,
        onAllow = allow,
        onLater = dismiss,
        onOpenSettings = { openAppDetailsSettings(context) },
    )
}

/**
 * The heart-rate card with its flow (spec §3.4, "처음 쓸 때"): shown while [access] is
 * [HeartRateAccess.NO_PERMISSION] and the card was not dismissed
 * ([com.cowork.bikerecoder.data.AppSettings.heartRatePromptDismissed]). [허용] runs
 * [rememberHeartRatePermissionRequest]. A refusal or [나중에] dismisses it for good (settings "사진·심박 연결" can ask
 * again).
 */
@Composable
fun HeartRatePermissionPrompt(gate: HeartRateGate, access: HeartRateAccessState) {
    val scope = rememberCoroutineScope()
    val dismissedFlow = remember(gate) { gate.settings.settings.map { it.heartRatePromptDismissed } }
    // Unknown until read: no card flashes up before the stored answer is known.
    val dismissed by dismissedFlow.collectAsStateWithLifecycle(initialValue = null)
    val dismiss: () -> Unit = { scope.launch { gate.settings.update { it.copy(heartRatePromptDismissed = true) } } }
    val allow = rememberHeartRatePermissionRequest(gate, access, onRefused = dismiss)

    if (dismissed != false || access.value != HeartRateAccess.NO_PERMISSION) return
    HeartRatePermissionCard(onAllow = allow, onLater = dismiss)
}

/** How much [access] allows: NONE < PARTIAL < FULL. */
private fun accessRank(access: PhotoAccess): Int = when (access) {
    PhotoAccess.NONE -> 0
    PhotoAccess.PARTIAL -> 1
    PhotoAccess.FULL -> 2
}

/**
 * What [허용] on the photo card asks for: on Android 14+ all or selected photos (the system offers both), on 13 all
 * photos, on 11–12 the storage permission that reads them (ruling R5).
 */
internal fun photoPermissionsFor(sdkInt: Int): Array<String> = when {
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

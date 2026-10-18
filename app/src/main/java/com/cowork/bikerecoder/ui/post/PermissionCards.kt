package com.cowork.bikerecoder.ui.post

import android.Manifest
import android.content.ActivityNotFoundException
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.post.PhotoAccess
import com.cowork.bikerecoder.post.PhotoSource
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

/**
 * The photo card where it is needed, with its whole flow (spec §2.4, "처음 쓸 때"): shown while photo access is not
 * full and the card was not dismissed ([com.cowork.bikerecoder.data.AppSettings.photoPromptDismissed]).
 * [허용] asks for [photoPermissionsFor] and, once photos may be read, `ACCESS_MEDIA_LOCATION` (for the photos' GPS
 * position), then [onAccessGained] (a post-processing pass). [나중에], [닫기] or a refusal dismiss it for good.
 * The access is read again whenever the screen comes back (e.g. from the app's settings page); more access than before
 * also calls [onAccessGained].
 */
@Composable
fun PhotoPermissionPrompt(photoSource: PhotoSource, settings: SettingsRepository, onAccessGained: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dismissedFlow = remember(settings) { settings.settings.map { it.photoPromptDismissed } }
    // Unknown until read: no card flashes up before the stored answer is known.
    val dismissed by dismissedFlow.collectAsStateWithLifecycle(initialValue = null)
    var access by remember { mutableStateOf(photoSource.access()) }
    val currentOnAccessGained by rememberUpdatedState(onAccessGained)

    LifecycleResumeEffect(photoSource) {
        val now = photoSource.access()
        if (accessRank(now) > accessRank(access)) currentOnAccessGained()
        access = now
        onPauseOrDispose { }
    }

    val dismiss: () -> Unit = { scope.launch { settings.update { it.copy(photoPromptDismissed = true) } } }
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        currentOnAccessGained()
    }
    val photosPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = photoSource.access()
        if (access == PhotoAccess.NONE) dismiss() else mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    if (dismissed != false || access == PhotoAccess.FULL) return
    PhotoPermissionCard(
        access = access,
        onAllow = { photosPermission.launch(photoPermissionsFor(Build.VERSION.SDK_INT)) },
        onLater = dismiss,
        onOpenSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w("PhotoPermissionPrompt", "No app details screen", e)
            }
        },
    )
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

package com.cowork.bikerecoder.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

private const val TOTAL_STEPS = 5

private class StepCopy(val title: String, val description: String, val button: String)

private fun copyFor(step: OnboardingStep): StepCopy? = when (step) {
    OnboardingStep.FINE_LOCATION -> StepCopy("정밀 위치", "현재 위치와 길안내에 필요합니다", "허용")
    OnboardingStep.BACKGROUND_LOCATION ->
        StepCopy("백그라운드 위치", "화면이 꺼져도 길안내를 계속하려면 '항상 허용'이 필요합니다", "허용")
    OnboardingStep.NOTIFICATIONS -> StepCopy("알림", "길안내 중 상태를 알림으로 보여줍니다", "허용")
    OnboardingStep.BATTERY -> StepCopy("배터리 최적화 제외", "장시간 안내 중 앱이 종료되지 않도록 합니다", "허용")
    OnboardingStep.SEGMENTS ->
        StepCopy("경로 데이터", "자전거 경로 계산용 지도 데이터(약 80MB)를 받습니다. 와이파이를 권장합니다", "다운로드")
    OnboardingStep.DONE -> null
}

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val step = ui.step
    val context = LocalContext.current

    // The user may come back from system Settings with a changed permission/battery state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(step, ui.skipLoaded) { if (ui.skipLoaded && step == OnboardingStep.DONE) onDone() }

    // A denied request shows the "open Settings" hint; it resets whenever the step changes.
    var denied by remember(step) { mutableStateOf(false) }
    val onResult = { granted: Boolean ->
        denied = !granted
        viewModel.refresh()
    }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult(it[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onResult(it)
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // No grant flag comes back; the refresh re-reads isIgnoringBatteryOptimizations.
        viewModel.refresh()
    }

    val copy = copyFor(step)
    Surface(modifier = Modifier.fillMaxSize()) {
        if (copy == null || !ui.skipLoaded) return@Surface
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "${step.ordinal + 1} / $TOTAL_STEPS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(copy.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(copy.description, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))

            if (step == OnboardingStep.SEGMENTS) {
                SegmentsActions(
                    download = ui.download,
                    buttonLabel = copy.button,
                    onDownload = viewModel::downloadSegments,
                )
            } else {
                Button(
                    onClick = {
                        when (step) {
                            OnboardingStep.FINE_LOCATION -> fineLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                            // Requested only after fine location; Android 11+ sends the user to Settings.
                            OnboardingStep.BACKGROUND_LOCATION ->
                                singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            OnboardingStep.NOTIFICATIONS ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            OnboardingStep.BATTERY -> batteryLauncher.launch(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                            else -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(copy.button) }

                if (denied) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "허용되지 않았습니다. 설정에서 직접 허용해 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("설정 열기")
                    }
                }
            }

            // Everything except the mandatory fine location can be postponed; the choice is persisted.
            if (step != OnboardingStep.FINE_LOCATION) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.skipStep(step) }, modifier = Modifier.fillMaxWidth()) {
                    Text("나중에")
                }
            }
        }
    }
}

@Composable
private fun SegmentsActions(
    download: SegmentDownload,
    buttonLabel: String,
    onDownload: () -> Unit,
) {
    when (download) {
        is SegmentDownload.Downloading -> {
            val fraction = if (download.totalBytes > 0) {
                (download.readBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatProgress(download.readBytes, download.totalBytes)} (${download.index}/${download.count})",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SegmentDownload.Failed -> {
            Text(
                "다운로드에 실패했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("다시 시도") }
        }
        SegmentDownload.Idle ->
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
    }
}

/** "{받은 MB} / {전체 MB}"; the total is omitted while the server has not told us the size. */
internal fun formatProgress(readBytes: Long, totalBytes: Long): String =
    if (totalBytes > 0) "${megabytes(readBytes)} / ${megabytes(totalBytes)}" else megabytes(readBytes)

private fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

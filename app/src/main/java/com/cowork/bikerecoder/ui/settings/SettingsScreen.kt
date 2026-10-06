package com.cowork.bikerecoder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.AppVersion
import com.cowork.bikerecoder.BuildConfig
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.offline.SegmentInfo
import com.cowork.bikerecoder.ui.common.GlyphIcon
import com.cowork.bikerecoder.ui.plan.labelKo

/** Test tags used by the Compose test. */
object SettingsTags {
    const val VOICE = "settings_voice"
    const val KEEP_SCREEN_ON = "settings_keep_screen_on"
    const val WIFI_ONLY = "settings_wifi_only"
    const val DELETE_ALL_MAPS = "settings_delete_all_maps"
    const val MAP_SIZE = "settings_map_size"
    const val CONFIRM = "settings_confirm"
    const val CANCEL = "settings_cancel"
    fun profile(profile: RouteProfile) = "settings_profile_${profile.name}"
    fun segmentCheck(name: String) = "settings_segment_check_$name"
    fun segmentDelete(name: String) = "settings_segment_delete_$name"
    fun segmentDownload(name: String) = "settings_segment_download_$name"
}

/** 출처 표기 (README 「라이선스와 출처 표기」와 같은 문구). */
internal val ATTRIBUTIONS = listOf(
    "지도: OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
    "지도 데이터: © OpenStreetMap contributors (ODbL)",
    "경로 탐색: BRouter © BRouter contributors (MIT License)",
)

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        versionDisplay = AppVersion.display(BuildConfig.VERSION_NAME),
        onBack = onBack,
        onSetProfile = viewModel::setProfile,
        onSetVoice = viewModel::setVoiceEnabled,
        onSetKeepScreenOn = viewModel::setKeepScreenOn,
        onSetWifiOnly = viewModel::setWifiOnlyOfflineMaps,
        onCheckUpdate = viewModel::checkUpdate,
        onDownload = viewModel::download,
        onDeleteSegment = viewModel::delete,
        onDeleteAllMaps = viewModel::deleteAllMaps,
    )
}

@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    versionDisplay: String,
    onBack: () -> Unit,
    onSetProfile: (RouteProfile) -> Unit,
    onSetVoice: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onCheckUpdate: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDeleteSegment: (String) -> Unit,
    onDeleteAllMaps: () -> Unit,
) {
    var confirmDeleteSegment by remember { mutableStateOf<SegmentInfo?>(null) }
    var confirmDeleteMaps by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Text("설정", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle("기본 경로 성향")
                Column(modifier = Modifier.selectableGroup()) {
                    RouteProfile.entries.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag(SettingsTags.profile(profile))
                                .selectable(
                                    selected = state.settings.defaultProfile == profile,
                                    role = Role.RadioButton,
                                    onClick = { onSetProfile(profile) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.settings.defaultProfile == profile, onClick = null)
                            Text(profile.labelKo(), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                HorizontalDivider()

                SwitchRow("음성 안내", null, state.settings.voiceEnabled, SettingsTags.VOICE, onSetVoice)
                SwitchRow("안내 중 화면 켜짐 유지", null, state.settings.keepScreenOn, SettingsTags.KEEP_SCREEN_ON, onSetKeepScreenOn)
                SwitchRow(
                    "와이파이에서만 지도 받기",
                    "꺼져 있으면 모바일 데이터로도 경로 주변 지도를 받습니다",
                    state.settings.wifiOnlyOfflineMaps,
                    SettingsTags.WIFI_ONLY,
                    onSetWifiOnly,
                )
                HorizontalDivider()

                SectionTitle("경로 데이터")
                state.segments.forEach { info ->
                    SegmentRow(
                        info = info,
                        status = state.statusOf(info.name),
                        onCheckUpdate = { onCheckUpdate(info.name) },
                        onDownload = { onDownload(info.name) },
                        onDelete = { confirmDeleteSegment = info },
                    )
                }
                HorizontalDivider()

                SectionTitle("내려받은 지도")
                Text(
                    when (val s = state.mapStorage) {
                        MapStorageState.Loading -> "용량 확인 중…"
                        MapStorageState.Unknown -> "용량을 확인하지 못했습니다"
                        is MapStorageState.Size -> "사용 중인 저장 공간 ${formatBytes(s.bytes)}"
                    },
                    modifier = Modifier.testTag(SettingsTags.MAP_SIZE),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.deleteMapsFailed) {
                    Text(
                        "일부 지도를 삭제하지 못했습니다. 다시 시도해 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(
                    onClick = { confirmDeleteMaps = true },
                    enabled = !state.deletingMaps,
                    modifier = Modifier.testTag(SettingsTags.DELETE_ALL_MAPS),
                ) { Text(if (state.deletingMaps) "삭제 중…" else "모두 삭제") }
                HorizontalDivider()

                SectionTitle("앱 정보")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("앱 버전", style = MaterialTheme.typography.bodyLarge)
                    Text(versionDisplay, style = MaterialTheme.typography.bodyLarge)
                }
                ATTRIBUTIONS.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    confirmDeleteSegment?.let { info ->
        AlertDialog(
            onDismissRequest = { confirmDeleteSegment = null },
            title = { Text("${info.name} 경로 데이터를 삭제할까요?") },
            text = {
                Text(
                    if (info.required) "필수 데이터입니다. 삭제하면 다시 받기 전까지 경로를 계산할 수 없습니다." else "필요하면 다시 받을 수 있습니다.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteSegment = null
                        onDeleteSegment(info.name)
                    },
                    modifier = Modifier.testTag(SettingsTags.CONFIRM),
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSegment = null }, modifier = Modifier.testTag(SettingsTags.CANCEL)) { Text("취소") }
            },
        )
    }
    if (confirmDeleteMaps) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMaps = false },
            title = { Text("내려받은 지도를 모두 삭제할까요?") },
            text = { Text("진행 중인 지도 내려받기도 중단됩니다. 삭제한 지도는 인터넷이 없으면 표시되지 않습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteMaps = false
                        onDeleteAllMaps()
                    },
                    modifier = Modifier.testTag(SettingsTags.CONFIRM),
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteMaps = false }, modifier = Modifier.testTag(SettingsTags.CANCEL)) { Text("취소") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SegmentRow(
    info: SegmentInfo,
    status: SegmentStatus,
    onCheckUpdate: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = status is SegmentStatus.Checking || status is SegmentStatus.Downloading
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${info.name}  ·  ${if (info.required) "필수" else "선택"}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            if (info.installed) "설치됨 · ${formatBytes(info.sizeBytes ?: 0L)}" else "설치되지 않음",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status) {
            SegmentStatus.Checking -> Text("확인 중…", style = MaterialTheme.typography.bodyMedium)
            SegmentStatus.UpdateAvailable -> Text("새 버전이 있습니다", style = MaterialTheme.typography.bodyMedium)
            SegmentStatus.UpToDate -> Text("새 업데이트가 없습니다", style = MaterialTheme.typography.bodyMedium)
            is SegmentStatus.Downloading -> Text(
                if (status.totalBytes > 0) "받는 중 ${formatBytes(status.readBytes)} / ${formatBytes(status.totalBytes)}"
                else "받는 중 ${formatBytes(status.readBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            SegmentStatus.Failed -> Text(
                "실패했습니다. 인터넷 연결을 확인해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            SegmentStatus.Idle -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info.installed) {
                if (status == SegmentStatus.UpdateAvailable) {
                    OutlinedButton(onClick = onDownload, enabled = !busy, modifier = Modifier.testTag(SettingsTags.segmentDownload(info.name))) {
                        Text("업데이트")
                    }
                } else {
                    OutlinedButton(onClick = onCheckUpdate, enabled = !busy, modifier = Modifier.testTag(SettingsTags.segmentCheck(info.name))) {
                        Text("업데이트 확인")
                    }
                }
                OutlinedButton(onClick = onDelete, enabled = !busy, modifier = Modifier.testTag(SettingsTags.segmentDelete(info.name))) {
                    Text("삭제")
                }
            } else {
                OutlinedButton(onClick = onDownload, enabled = !busy, modifier = Modifier.testTag(SettingsTags.segmentDownload(info.name))) {
                    Text("받기")
                }
            }
        }
    }
}

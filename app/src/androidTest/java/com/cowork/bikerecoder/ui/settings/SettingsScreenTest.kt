package com.cowork.bikerecoder.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.data.GuidanceRecording
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.importing.ImportJobState
import com.cowork.bikerecoder.offline.OfflineMapStorage
import com.cowork.bikerecoder.offline.SegmentRepository
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.HealthAvailability
import com.cowork.bikerecoder.post.PhotoAccess
import com.cowork.bikerecoder.ui.health.HeartRateAccess
import com.cowork.bikerecoder.ui.health.HeartRateGate
import com.cowork.bikerecoder.ui.importing.ImportTags
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeStorage : OfflineMapStorage {
        var deleteCalls = 0
        override suspend fun totalBytes(): Long = 5L * 1024 * 1024
        override suspend fun deleteAll() {
            deleteCalls++
        }
    }

    private val settings = MutableStateFlow(AppSettings())
    private val storage = FakeStorage()
    private lateinit var segmentDir: File
    private lateinit var viewModel: SettingsViewModel

    // Fakes instead of the gallery and Health Connect (no permission is asked: nothing taps [허용] or [설정 열기]).
    private val photos = FakePhotoSource(access = PhotoAccess.NONE)
    private val heartRate = FakeHeartRateSource(permitted = false)
    private val opened = mutableListOf<String>()

    // The GPX import (V1.6.0 spec §1.1, §4.3): the imported trips' count, the job, [모두 지우기].
    private val importedCount = MutableStateFlow(0)
    private val importState = MutableStateFlow<ImportJobState>(ImportJobState.Idle)
    private var importedDeletes = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        segmentDir = File(context.cacheDir, "settings-test-segments").apply {
            deleteRecursively()
            mkdirs()
            File(this, "E125_N35.rd5").writeBytes(ByteArray(2048))
        }
        viewModel = SettingsViewModel(
            settings = settings,
            updateSettings = { transform -> settings.value = transform(settings.value) },
            // A dead address, so nothing a test taps can reach the real server.
            segments = SegmentRepository(segmentDir, OkHttpClient(), "http://127.0.0.1:1/".toHttpUrl()),
            mapStorage = storage,
            importedCount = importedCount,
            importState = importState,
            deleteAllImported = {
                importedDeletes++
                importedCount.value.also { importedCount.value = 0 }
            },
        )
    }

    private fun show(onBack: (() -> Unit)? = {}) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gate = HeartRateGate(heartRate, SettingsRepository(context), onGranted = {})
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = onBack,
                    photoSource = photos,
                    heartRateGate = gate,
                    onPhotoAccessGained = {},
                    // Never the real system screens.
                    onOpenAppSettings = { opened += "app" },
                    onOpenHealthConnectSettings = { opened += "healthConnect" },
                    onOpenImport = { opened += "import" },
                )
            }
        }
    }

    @After
    fun tearDown() {
        segmentDir.deleteRecursively()
    }

    @Test
    fun showsVersionAndTogglesVoice() {
        show()
        composeRule.onNodeWithText("V1.5.0").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsTags.VOICE).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(SettingsTags.VOICE).performClick()
        composeRule.waitUntil(5_000) { !settings.value.voiceEnabled }
        composeRule.onNodeWithTag(SettingsTags.VOICE).performClick()
        composeRule.waitUntil(5_000) { settings.value.voiceEnabled }
    }

    @Test
    fun choosesProfileAndTogglesOtherSwitches() {
        show()
        listOf("자전거도로 최우선", "균형", "최단거리").forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }

        composeRule.onNodeWithTag(SettingsTags.profile(RouteProfile.SHORTEST)).performClick()
        composeRule.waitUntil(5_000) { settings.value.defaultProfile == RouteProfile.SHORTEST }

        composeRule.onNodeWithTag(SettingsTags.KEEP_SCREEN_ON).performClick()
        composeRule.waitUntil(5_000) { !settings.value.keepScreenOn }
        composeRule.onNodeWithTag(SettingsTags.WIFI_ONLY).performClick()
        composeRule.waitUntil(5_000) { settings.value.wifiOnlyOfflineMaps }
    }

    @Test
    fun recordingSwitchesToggle() {
        show()
        composeRule.onNodeWithTag(SettingsTags.AUTO_START_RECORDING).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(SettingsTags.AUTO_PAUSE).performScrollTo().assertIsOn()

        composeRule.onNodeWithTag(SettingsTags.AUTO_START_RECORDING).performClick()
        composeRule.waitUntil(5_000) { !settings.value.autoStartRecordingWithNavigation }
        composeRule.onNodeWithTag(SettingsTags.AUTO_PAUSE).performClick()
        composeRule.waitUntil(5_000) { !settings.value.autoPauseEnabled }
        assertEquals(false, settings.value.autoStartRecordingWithNavigation)
    }

    @Test
    fun guidanceRecordingChoices() {
        show()
        composeRule.onNodeWithText("안내를 새로 시작할 때 기록").performScrollTo().assertIsDisplayed()
        listOf("자동으로 이어서 기록", "자동으로 새 구간 나누기", "매번 물어보기").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithTag(SettingsTags.guidanceRecording(GuidanceRecording.ASK)).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.guidanceRecording(GuidanceRecording.SPLIT)).assertIsNotSelected()

        composeRule.onNodeWithText("자동으로 새 구간 나누기").performClick()
        composeRule.waitUntil(5_000) { settings.value.recordingOnNewGuidance == GuidanceRecording.SPLIT }
        composeRule.onNodeWithTag(SettingsTags.guidanceRecording(GuidanceRecording.SPLIT)).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.guidanceRecording(GuidanceRecording.ASK)).assertIsNotSelected()
    }

    @Test
    fun showsSegmentsMapSizeAndAttribution() {
        show()
        composeRule.onNodeWithText("E125_N35  ·  필수").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("설치됨 · 2 KB").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("E130_N35  ·  선택").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.segmentDownload("E130_N35")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.segmentCheck("E125_N35")).performScrollTo().assertIsDisplayed()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("사용 중인 저장 공간 5.0 MB").fetchSemanticsNodes().isNotEmpty()
        }
        ATTRIBUTIONS.forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun shareHashtagsRowShowsDefault() {
        show()
        composeRule.onNodeWithText("공유").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("기본 해시태그").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("#자전거 #라이딩").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shareHashtagsDialogNormalizes() {
        show()
        composeRule.onNodeWithTag(SettingsTags.SHARE_HASHTAGS).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTags.SHARE_HASHTAGS_INPUT).performTextReplacement("자출 한강")
        composeRule.onNodeWithTag(SettingsTags.CONFIRM).performClick()

        composeRule.waitUntil(5_000) { settings.value.shareHashtags == "#자출 #한강" }
        composeRule.onNodeWithText("#자출 #한강").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun longStoredHashtagsReopenIntact() {
        val long = (1..19).joinToString(" ") { "#태그%02d".format(it) } // 133 characters
        settings.value = settings.value.copy(shareHashtags = long)
        show()
        composeRule.onNodeWithTag(SettingsTags.SHARE_HASHTAGS).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTags.SHARE_HASHTAGS_INPUT).assertTextEquals(long)
        composeRule.onNodeWithTag(SettingsTags.CONFIRM).performClick()

        composeRule.waitUntil(5_000) { settings.value.shareHashtags == long }
    }

    @Test
    fun emptyShowsNone() {
        settings.value = settings.value.copy(shareHashtags = "")
        show()
        composeRule.onNodeWithTag(SettingsTags.SHARE_HASHTAGS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("없음").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun deleteAllMapsAsksForConfirmationFirst() {
        show()
        composeRule.onNodeWithTag(SettingsTags.DELETE_ALL_MAPS).performScrollTo().performClick()
        composeRule.onNodeWithText("내려받은 지도를 모두 삭제할까요?").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CANCEL).performClick()
        composeRule.waitForIdle()
        assertEquals(0, storage.deleteCalls)

        composeRule.onNodeWithTag(SettingsTags.DELETE_ALL_MAPS).performClick()
        composeRule.onNodeWithTag(SettingsTags.CONFIRM).performClick()
        composeRule.waitUntil(5_000) { storage.deleteCalls == 1 }
    }

    @Test
    fun deletingRequiredSegmentAsksForConfirmationAndRemovesTheFile() {
        show()
        composeRule.onNodeWithTag(SettingsTags.segmentDelete("E125_N35")).performScrollTo().performClick()
        composeRule.onNodeWithText("E125_N35 경로 데이터를 삭제할까요?").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CANCEL).performClick()
        composeRule.waitForIdle()
        assertEquals(true, File(segmentDir, "E125_N35.rd5").exists())

        composeRule.onNodeWithTag(SettingsTags.segmentDelete("E125_N35")).performClick()
        composeRule.onNodeWithTag(SettingsTags.CONFIRM).performClick()
        composeRule.waitUntil(5_000) { !File(segmentDir, "E125_N35.rd5").exists() }
    }

    @Test
    fun photoAndHeartRateConnectionRow() {
        show()
        composeRule.onNodeWithText("사진·심박 연결").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.PHOTO_STATUS).performScrollTo().assertTextEquals("허용 안 함")
        composeRule.onNodeWithTag(SettingsTags.PHOTO_ALLOW).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.PHOTO_OPEN_SETTINGS).assertDoesNotExist()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("허용 안 함").fetchSemanticsNodes().size == 2
        }
        composeRule.onNodeWithTag(SettingsTags.HEART_RATE_STATUS).performScrollTo().assertTextEquals("허용 안 함")
        composeRule.onNodeWithTag(SettingsTags.HEART_RATE_ALLOW).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun photoAndHeartRateConnectionRowWhenAllowed() {
        photos.access = PhotoAccess.FULL
        heartRate.permitted = true
        show()
        composeRule.onNodeWithTag(SettingsTags.PHOTO_STATUS).performScrollTo().assertTextEquals("모든 사진 허용")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("허용됨").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(SettingsTags.HEART_RATE_STATUS).performScrollTo().assertTextEquals("허용됨")
        composeRule.onNodeWithTag(SettingsTags.PHOTO_ALLOW).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.PHOTO_OPEN_SETTINGS).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.HEART_RATE_ALLOW).assertDoesNotExist()
    }

    @Test
    fun photoAndHeartRateConnectionRowSelectedPhotosOnlyWithoutHealthConnect() {
        photos.access = PhotoAccess.PARTIAL
        heartRate.availability = HealthAvailability.NOT_INSTALLED
        show()
        composeRule.onNodeWithTag(SettingsTags.PHOTO_STATUS).performScrollTo().assertTextEquals("선택한 사진만")
        composeRule.onNodeWithTag(SettingsTags.PHOTO_OPEN_SETTINGS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.PHOTO_ALLOW).assertDoesNotExist()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Health Connect를 사용할 수 없습니다").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SettingsTags.HEART_RATE_STATUS).performScrollTo()
            .assertTextEquals("Health Connect를 사용할 수 없습니다")
        composeRule.onNodeWithTag(SettingsTags.HEART_RATE_ALLOW).assertDoesNotExist()
    }

    /** As a screen of its own it has ←; as the [설정] tab (V1.4.0 spec §1.1) it has none, only the title. */
    @Test
    fun backArrowOnlyWithOnBack() {
        var backs = 0
        show(onBack = { backs++ })
        composeRule.onNodeWithContentDescription("뒤로").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun settingsTabHasNoBackArrow() {
        show(onBack = null)
        composeRule.onNodeWithText("설정").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("뒤로").assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.VOICE).performScrollTo().assertIsOn()
    }

    /** "기록 가져오기" sits under "공유" and above "앱 정보"; no imported row while there is none. */
    @Test
    fun importSectionBetweenShareAndAppInfo() {
        show()
        composeRule.onNodeWithText("기록 가져오기").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("지난 기록 가져오기 (GPX)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Relive·Strava 등에서 받은 GPX·ZIP 파일").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.SETTINGS_ROW).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(ImportTags.SETTINGS_IMPORTED).assertDoesNotExist()

        val share = composeRule.onNodeWithText("공유").getUnclippedBoundsInRoot().top
        val import = composeRule.onNodeWithText("기록 가져오기").getUnclippedBoundsInRoot().top
        val hashtags = composeRule.onNodeWithTag(SettingsTags.SHARE_HASHTAGS).getUnclippedBoundsInRoot().top
        val appInfo = composeRule.onNodeWithText("앱 정보").getUnclippedBoundsInRoot().top
        assertTrue("share $share < hashtags $hashtags < import $import < app info $appInfo", share < hashtags && hashtags < import && import < appInfo)
    }

    @Test
    fun importedCountAndDeleteAllConfirm() {
        importedCount.value = 3
        show()
        composeRule.onNodeWithTag(ImportTags.SETTINGS_IMPORTED).performScrollTo().assertTextEquals("가져온 기록 3개")
        composeRule.onNodeWithTag(ImportTags.SETTINGS_DELETE_ALL).performScrollTo().assertTextEquals("모두 지우기").performClick()
        composeRule.onNodeWithText("가져온 기록 3개를 지울까요? 이 휴대폰에서 기록한 주행은 남습니다.").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CANCEL).assertTextEquals("취소").performClick()
        composeRule.waitForIdle()
        assertEquals(0, importedDeletes)

        composeRule.onNodeWithTag(ImportTags.SETTINGS_DELETE_ALL).performClick()
        composeRule.onNodeWithTag(SettingsTags.CONFIRM).assertTextEquals("지우기").performClick()
        composeRule.waitUntil(5_000) { importedDeletes == 1 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(ImportTags.SETTINGS_IMPORTED).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, importedDeletes)
    }

    @Test
    fun importingShowsProgressAndDisablesDelete() {
        importedCount.value = 2
        importState.value = ImportJobState.Importing(12, 35)
        show()
        composeRule.onNodeWithText("가져오는 중 12/35").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Relive·Strava 등에서 받은 GPX·ZIP 파일").assertDoesNotExist()
        composeRule.onNodeWithTag(ImportTags.SETTINGS_DELETE_ALL).performScrollTo().assertIsNotEnabled()

        // The row opens the progress screen, not the file picker.
        composeRule.onNodeWithTag(ImportTags.SETTINGS_ROW).performScrollTo().performClick()
        assertEquals(listOf("import"), opened)

        importState.value = ImportJobState.Finished(imported = 35, skipped = 0, stopped = false)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Relive·Strava 등에서 받은 GPX·ZIP 파일").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ImportTags.SETTINGS_DELETE_ALL).performScrollTo().assertIsEnabled()
    }

    @Test
    fun connectionButtonsCallTheirActions() {
        val calls = mutableListOf<String>()
        var photo by mutableStateOf(PhotoAccess.NONE)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ConnectionSection(
                        photo = photo,
                        heartRate = HeartRateAccess.NO_PERMISSION,
                        onAllowPhotos = { calls += "allowPhotos" },
                        onOpenPhotoSettings = { calls += "openPhotoSettings" },
                        onAllowHeartRate = { calls += "allowHeartRate" },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(SettingsTags.PHOTO_ALLOW).performClick()
        composeRule.onNodeWithTag(SettingsTags.HEART_RATE_ALLOW).performClick()
        photo = PhotoAccess.PARTIAL
        composeRule.onNodeWithTag(SettingsTags.PHOTO_OPEN_SETTINGS).performClick()

        assertEquals(listOf("allowPhotos", "allowHeartRate", "openPhotoSettings"), calls)
        assertEquals(emptyList<String>(), opened)
    }
}

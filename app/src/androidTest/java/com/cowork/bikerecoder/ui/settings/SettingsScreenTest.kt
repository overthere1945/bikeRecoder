package com.cowork.bikerecoder.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.offline.OfflineMapStorage
import com.cowork.bikerecoder.offline.SegmentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        segmentDir = File(context.cacheDir, "settings-test-segments").apply {
            deleteRecursively()
            mkdirs()
            File(this, "E125_N35.rd5").writeBytes(ByteArray(2048))
        }
        val viewModel = SettingsViewModel(
            settings = settings,
            updateSettings = { transform -> settings.value = transform(settings.value) },
            // A dead address, so nothing a test taps can reach the real server.
            segments = SegmentRepository(segmentDir, OkHttpClient(), "http://127.0.0.1:1/".toHttpUrl()),
            mapStorage = storage,
        )
        composeRule.setContent { MaterialTheme { SettingsScreen(viewModel = viewModel, onBack = {}) } }
    }

    @After
    fun tearDown() {
        segmentDir.deleteRecursively()
    }

    @Test
    fun showsVersionAndTogglesVoice() {
        composeRule.onNodeWithText("V1.0.0").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsTags.VOICE).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(SettingsTags.VOICE).performClick()
        composeRule.waitUntil(5_000) { !settings.value.voiceEnabled }
        composeRule.onNodeWithTag(SettingsTags.VOICE).performClick()
        composeRule.waitUntil(5_000) { settings.value.voiceEnabled }
    }

    @Test
    fun choosesProfileAndTogglesOtherSwitches() {
        listOf("자전거도로 최우선", "균형", "최단거리").forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }

        composeRule.onNodeWithTag(SettingsTags.profile(RouteProfile.SHORTEST)).performClick()
        composeRule.waitUntil(5_000) { settings.value.defaultProfile == RouteProfile.SHORTEST }

        composeRule.onNodeWithTag(SettingsTags.KEEP_SCREEN_ON).performClick()
        composeRule.waitUntil(5_000) { !settings.value.keepScreenOn }
        composeRule.onNodeWithTag(SettingsTags.WIFI_ONLY).performClick()
        composeRule.waitUntil(5_000) { settings.value.wifiOnlyOfflineMaps }
    }

    @Test
    fun showsSegmentsMapSizeAndAttribution() {
        composeRule.onNodeWithText("E125_N35  ·  필수").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("설치됨 · 2 KB").assertIsDisplayed()
        composeRule.onNodeWithText("E130_N35  ·  선택").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.segmentDownload("E130_N35")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.segmentCheck("E125_N35")).assertIsDisplayed()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("사용 중인 저장 공간 5.0 MB").fetchSemanticsNodes().isNotEmpty()
        }
        ATTRIBUTIONS.forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun deleteAllMapsAsksForConfirmationFirst() {
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
        composeRule.onNodeWithTag(SettingsTags.segmentDelete("E125_N35")).performScrollTo().performClick()
        composeRule.onNodeWithText("E125_N35 경로 데이터를 삭제할까요?").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CANCEL).performClick()
        composeRule.waitForIdle()
        assertEquals(true, File(segmentDir, "E125_N35.rd5").exists())

        composeRule.onNodeWithTag(SettingsTags.segmentDelete("E125_N35")).performClick()
        composeRule.onNodeWithTag(SettingsTags.CONFIRM).performClick()
        composeRule.waitUntil(5_000) { !File(segmentDir, "E125_N35.rd5").exists() }
    }
}

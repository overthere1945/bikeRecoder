package com.cowork.bikerecoder.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.offline.SegmentInfo
import org.junit.Rule
import org.junit.Test

/** Which segment action the content offers for a given [SegmentStatus] (rendered from a fixed state). */
class SettingsSegmentActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val installed = SegmentInfo("E125_N35", required = true, installed = true, sizeBytes = 2048)

    private fun show(status: SegmentStatus?) {
        val state = SettingsUiState(
            segments = listOf(installed),
            segmentStatus = status?.let { mapOf(installed.name to it) } ?: emptyMap(),
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(
                    state = state, versionDisplay = "V1.0.0", onBack = {}, onSetProfile = { _: RouteProfile -> },
                    onSetVoice = {}, onSetKeepScreenOn = {}, onSetWifiOnly = {}, onCheckUpdate = {}, onDownload = {},
                    onDeleteSegment = {}, onDeleteAllMaps = {},
                )
            }
        }
    }

    private fun assertOnlyUpdateAction() {
        composeRule.onNodeWithTag(SettingsTags.segmentDownload("E125_N35")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.segmentCheck("E125_N35")).assertDoesNotExist()
    }

    @Test
    fun failedUpdateDownloadKeepsTheUpdateAction() {
        show(SegmentStatus.Failed)
        assertOnlyUpdateAction()
        composeRule.onNodeWithText("실패했습니다. 인터넷 연결을 확인해 주세요.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun runningUpdateDownloadShowsDisabledUpdateAction() {
        show(SegmentStatus.Downloading(1024, 2048))
        assertOnlyUpdateAction()
        composeRule.onNodeWithTag(SettingsTags.segmentDownload("E125_N35")).assertIsNotEnabled()
    }

    @Test
    fun failedCheckOffersCheckAgainAndSaysSo() {
        show(SegmentStatus.CheckFailed)
        composeRule.onNodeWithTag(SettingsTags.segmentCheck("E125_N35")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("업데이트를 확인하지 못했습니다. 인터넷 연결을 확인해 주세요.").assertIsDisplayed()
    }

    @Test
    fun idleInstalledOffersCheck() {
        show(null)
        composeRule.onNodeWithTag(SettingsTags.segmentCheck("E125_N35")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.segmentDownload("E125_N35")).assertDoesNotExist()
    }
}

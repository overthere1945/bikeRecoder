package com.cowork.bikerecoder.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.post.PhotoAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The photo card's two texts and buttons (spec §2.4) and the heart-rate card (spec §3.4). */
class PermissionCardsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun photoCard(access: PhotoAccess) {
        composeRule.setContent {
            MaterialTheme {
                PhotoPermissionCard(
                    access = access,
                    onAllow = { calls += "allow" },
                    onLater = { calls += "later" },
                    onOpenSettings = { calls += "settings" },
                )
            }
        }
    }

    @Test
    fun noAccessAsksToAllow() {
        photoCard(PhotoAccess.NONE)

        composeRule.onNodeWithText("주행 중 찍은 사진을 경로에 넣으려면 사진 접근을 허용해 주세요").assertIsDisplayed()
        composeRule.onNodeWithText("설정 열기").assertDoesNotExist()
        composeRule.onNodeWithText("허용").performClick()
        composeRule.onNodeWithText("나중에").performClick()

        assertEquals(listOf("allow", "later"), calls)
    }

    @Test
    fun selectedPhotosOnlyPointsToTheSettings() {
        photoCard(PhotoAccess.PARTIAL)

        composeRule.onNodeWithText("선택한 사진만 찾을 수 있습니다. 모든 사진을 허용하면 자동으로 찾습니다").assertIsDisplayed()
        composeRule.onNodeWithText("허용").assertDoesNotExist()
        composeRule.onNodeWithText("설정 열기").performClick()
        composeRule.onNodeWithText("닫기").performClick()

        assertEquals(listOf("settings", "later"), calls)
    }

    @Test
    fun fullAccessShowsNothing() {
        photoCard(PhotoAccess.FULL)

        composeRule.onNodeWithText("사진", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("허용").assertDoesNotExist()
    }

    @Test
    fun heartRateCardAsksToAllow() {
        composeRule.setContent {
            MaterialTheme {
                HeartRatePermissionCard(onAllow = { calls += "allow" }, onLater = { calls += "later" })
            }
        }

        composeRule.onNodeWithText("워치 심박을 가져오려면 Health Connect 접근을 허용해 주세요").assertIsDisplayed()
        composeRule.onNodeWithText("허용").performClick()
        composeRule.onNodeWithText("나중에").performClick()

        assertEquals(listOf("allow", "later"), calls)
    }
}

package com.cowork.bikerecoder.ui.pick

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** The pick screen without MapLibre: the map slot is an empty box, the card follows [MapPickState]. */
class MapPickScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val point = GeoPoint(35.115, 129.041)
    private var state by mutableStateOf(MapPickState())
    private var back = 0
    private var confirmed = 0

    private fun show(initial: MapPickState) {
        state = initial
        composeRule.setContent {
            MaterialTheme {
                MapPickScreen(
                    state = state,
                    onBack = { back++ },
                    onConfirm = { confirmed++ },
                    map = { modifier -> Box(modifier) },
                )
            }
        }
    }

    private fun address() = composeRule.onNodeWithTag(MapPickTags.ADDRESS)
    private fun confirm() = composeRule.onNodeWithTag(MapPickTags.CONFIRM)

    @Test
    fun titleBackAndThePinTipAtTheMapCenter() {
        show(MapPickState(center = point, address = "부산 동구 중앙대로 206"))
        composeRule.onNodeWithText("지도에서 선택").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("뒤로").performClick()
        assertEquals(1, back)

        val map = composeRule.onNodeWithTag(MapPickTags.MAP).fetchSemanticsNode().boundsInRoot
        val pin = composeRule.onNodeWithTag(MapPickTags.PIN).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val centerY = (map.top + map.bottom) / 2
        val centerX = (map.left + map.right) / 2
        assertTrue("pin tip y ${pin.bottom} must be the map center $centerY", abs(pin.bottom - centerY) <= 1.5f)
        assertTrue("pin x ${pin.center.x} must be the map center $centerX", abs(pin.center.x - centerX) <= 1.5f)
    }

    @Test
    fun aStoppedMapWithAnAddressShowsItAndConfirms() {
        show(MapPickState(center = point, address = "부산 동구 중앙대로 206"))
        address().assertTextEquals("부산 동구 중앙대로 206")
        confirm().assertIsEnabled().assertTextEquals("이 위치로 설정")
        confirm().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun whileTheAddressLoadsTheCardSaysSoAndTheButtonWaits() {
        show(MapPickState(center = point, loading = true))
        address().assertTextEquals("주소를 확인하는 중…")
        confirm().assertIsNotEnabled()
        confirm().performClick()
        assertEquals(0, confirmed)

        state = state.copy(loading = false, address = "부산 동구 중앙대로 206")
        composeRule.waitForIdle()
        confirm().assertIsEnabled()
    }

    @Test
    fun aFailedLookupShowsTheCoordinates() {
        show(MapPickState(center = point, address = null, loading = false))
        address().assertTextEquals("35.11500, 129.04100")
        confirm().assertIsEnabled()
    }

    @Test
    fun whileTheMapMovesTheButtonCannotBePressed() {
        show(MapPickState(center = point, address = "부산 동구 중앙대로 206"))
        state = state.copy(moving = true, address = null, loading = true)
        composeRule.waitForIdle()
        confirm().assertIsNotEnabled()
        confirm().performClick()
        assertEquals(0, confirmed)

        // Stopped: still disabled until its address lookup ends (here: failed → the coordinates).
        state = state.copy(moving = false)
        composeRule.waitForIdle()
        confirm().assertIsNotEnabled()
        state = state.copy(loading = false)
        composeRule.waitForIdle()
        confirm().assertIsEnabled()
    }

    @Test
    fun withoutACenterTheButtonCannotBePressed() {
        show(MapPickState())
        confirm().assertIsNotEnabled()
    }
}

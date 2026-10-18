package com.cowork.bikerecoder.ui.plan

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlanScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRouter : Router {
        val requests = mutableListOf<RouteRequest>()
        override suspend fun route(request: RouteRequest): RouteResult {
            requests += request
            val (distance, ascent, ratio) = when (request.profile) {
                RouteProfile.SHORTEST -> Triple(80_000.0, 600, 0.30)
                else -> Triple(87_400.0, 420, 0.781)
            }
            val points = listOf(request.start) + request.stops
            return RouteResult.Success(
                Route(
                    points = points,
                    cumulativeM = points.indices.map { it * distance / points.lastIndex },
                    instructions = emptyList(),
                    stopPointIndices = listOf(points.lastIndex),
                    summary = RouteSummary(distance, ascent, ratio, request.profile),
                ),
            )
        }
    }

    private val router = FakeRouter()
    private val viewModel = PlanViewModel(
        router = router,
        defaultProfile = flowOf(RouteProfile.CYCLEWAY_FIRST),
        locations = MutableStateFlow(LocationFix(GeoPoint(35.10, 129.00), 5f, null, null, System.currentTimeMillis())),
        segmentsReady = { true },
    )

    private fun stopNames(): List<String> {
        val nodes = composeRule.onAllNodesWithTag(PlanTags.STOP_NAME)
        val count = nodes.fetchSemanticsNodes().size
        return (0 until count).map { i ->
            nodes[i].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString("") { it.text }
        }
    }

    private fun waitForSummary(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun addReorderDeleteAndSwitchProfile() {
        composeRule.setContent {
            MaterialTheme {
                PlanScreen(
                    viewModel = viewModel,
                    map = { _, _ -> },
                    onBack = {},
                    onAddStop = {},
                    onStartNavigation = {},
                )
            }
        }

        // Add: destination plus two waypoints (waypoints go in front of the destination).
        composeRule.runOnIdle {
            viewModel.setDestination("부산역", GeoPoint(35.115, 129.04))
            viewModel.addWaypoint("서면", GeoPoint(35.157, 129.06))
            viewModel.addWaypoint("해운대", GeoPoint(35.163, 129.16))
        }
        composeRule.waitForIdle()
        assertEquals(listOf("서면", "해운대", "부산역"), stopNames())

        // Start stays disabled until the route is ready; then the summary shows both lines.
        waitForSummary("87.4km")
        composeRule.onNodeWithTag(PlanTags.START).assertIsEnabled()
        composeRule.onNodeWithText("약 5시간 50분", substring = true).assertExists()
        composeRule.onNodeWithText("오르막 420m · 자전거도로 78%").assertExists()

        // Reorder with the handle's accessibility action: move 해운대 up.
        val handle = composeRule.onNodeWithContentDescription("순서 변경: 해운대")
        val up = handle.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "위로 이동" }
        composeRule.runOnUiThread { up.action() }
        composeRule.waitForIdle()
        assertEquals(listOf("해운대", "서면", "부산역"), stopNames())

        // Delete the first stop with its ✕.
        composeRule.onNodeWithContentDescription("삭제: 해운대").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("서면", "부산역"), stopNames())

        // Switching the profile chip recomputes and updates the summary.
        composeRule.onNodeWithText("최단거리").performClick()
        waitForSummary("80.0km")
        composeRule.onNodeWithText("오르막 600m · 자전거도로 30%").assertExists()
        assertEquals(RouteProfile.SHORTEST, router.requests.last().profile)
        assertEquals(listOf(GeoPoint(35.157, 129.06), GeoPoint(35.115, 129.04)), router.requests.last().stops)
    }

    @Test
    fun startIsDisabledWithoutADestination() {
        composeRule.setContent {
            MaterialTheme {
                PlanScreen(viewModel = viewModel, map = { _, _ -> }, onBack = {}, onAddStop = {}, onStartNavigation = {})
            }
        }
        composeRule.onNodeWithTag(PlanTags.START).assertIsNotEnabled()
        composeRule.onNodeWithText("목적지를 정해 주세요").assertExists()
    }
}

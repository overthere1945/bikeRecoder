package com.cowork.bikerecoder.ui.plan

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
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
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.elevation.RideTimeEstimator
import com.cowork.bikerecoder.core.elevation.UsualSpeed
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.ui.elevation.ElevationHandoff
import com.cowork.bikerecoder.ui.elevation.ElevationTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlanScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRouter : Router {
        val requests = mutableListOf<RouteRequest>()

        /** A route with elevation ([hillyRoute]) instead of the plain one. */
        var elevated = false

        override suspend fun route(request: RouteRequest): RouteResult {
            requests += request
            if (elevated) return RouteResult.Success(hillyRoute(request))
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
    private val addressGate = CompletableDeferred<String?>()
    private val handoff = ElevationHandoff()
    private val viewModel = planViewModel()

    private fun planViewModel(usualSpeedMps: Double = UsualSpeed.DEFAULT_MPS) = PlanViewModel(
        router = router,
        defaultProfile = flowOf(RouteProfile.CYCLEWAY_FIRST),
        locations = MutableStateFlow(LocationFix(GeoPoint(35.10, 129.00), 5f, null, null, System.currentTimeMillis())),
        segmentsReady = { true },
        addressOf = { addressGate.await() },
        usualSpeed = { usualSpeedMps },
        handoff = handoff,
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

    /** The plan screen of [vm] with a grey box for the map and a destination behind a waypoint. */
    private fun showPlan(vm: PlanViewModel, onOpenElevation: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                PlanScreen(
                    viewModel = vm,
                    map = { _, modifier -> Box(modifier.background(Color(0xFFCFD8DC))) },
                    onBack = {},
                    onAddStop = {},
                    onStartNavigation = {},
                    onOpenElevation = onOpenElevation,
                )
            }
        }
        composeRule.runOnIdle {
            vm.setDestination("해운대", GeoPoint(35.163, 129.16))
            vm.addWaypoint("서면", GeoPoint(35.157, 129.06))
        }
    }

    /** V1.7.0 spec §2.5: the time at the usual speed corrected for the climbs, its line, and the chart that opens the map. */
    @Test
    fun summaryShowsUsualSpeedAndChart() {
        router.elevated = true
        val vm = planViewModel(usualSpeedMps = 18.4 / 3.6)
        var opened = 0
        showPlan(vm, onOpenElevation = { opened++ })
        waitForSummary("20.0km")

        val state = vm.state.value
        val profile = state.elevation!!
        val seconds = RideTimeEstimator.seconds(profile, 0.0, 20_000.0, 18.4 / 3.6)
        assertEquals(seconds, state.durationSeconds!!, 1e-6)
        assertTrue("the climbs take longer than the flat time", seconds > 20_000.0 / (18.4 / 3.6) + 60.0)
        composeRule.onNodeWithText("20.0km · ${SummaryFormatter.durationOf(seconds)}").assertIsDisplayed()
        composeRule.onNodeWithText("평소 속도 18 km/h 기준").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.NO_ELEVATION).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.CHART).assertIsDisplayed()
        assertEquals(100f, composeRule.onNodeWithTag(ElevationTags.CHART).getBoundsInRoot().let { (it.bottom - it.top).value }, 0.5f)

        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).performClick()
        composeRule.waitForIdle()
        assertEquals(1, opened)
        val (handed, path) = handoff.take()!!
        assertSame(profile, handed)
        assertEquals((state.route as RouteUiState.Ready).route.points, path)
    }

    /** A route without elevation: "고도 정보가 없습니다" in the chart's place, the time at the usual speed only. */
    @Test
    fun noElevationText() {
        val vm = planViewModel(usualSpeedMps = 18.0 / 3.6)
        var opened = 0
        showPlan(vm, onOpenElevation = { opened++ })
        waitForSummary("87.4km")

        composeRule.onNodeWithText("87.4km · 약 4시간 51분").assertIsDisplayed() // 87.4 km at 18 km/h = 291.3 min
        composeRule.onNodeWithText("평소 속도 18 km/h 기준").assertIsDisplayed()
        composeRule.onNodeWithText("고도 정보가 없습니다").assertIsDisplayed()
        composeRule.onNodeWithTag(ElevationTags.CHART).assertDoesNotExist()
        composeRule.onNodeWithTag(ElevationTags.SHOW_ON_MAP).assertDoesNotExist()
        assertEquals(0, opened)
    }

    /** While there is no route the chart's place stays empty (and the usual speed line is not shown). */
    @Test
    fun noRouteLeavesTheChartSlotEmpty() {
        composeRule.setContent {
            MaterialTheme {
                PlanScreen(viewModel = viewModel, map = { _, _ -> }, onBack = {}, onAddStop = {}, onStartNavigation = {})
            }
        }
        composeRule.onNodeWithTag(PlanTags.CHART_SLOT).assertExists()
        composeRule.onNodeWithTag(ElevationTags.CHART).assertDoesNotExist()
        composeRule.onNodeWithText("고도 정보가 없습니다").assertDoesNotExist()
        composeRule.onNodeWithTag(PlanTags.USUAL_SPEED).assertDoesNotExist()
    }

    /** Download/bikeRecoderTest/v170-plan.png: the plan preview with its time, usual speed line and chart. */
    @Test
    fun screenshot() {
        router.elevated = true
        showPlan(planViewModel(usualSpeedMps = 18.4 / 3.6))
        waitForSummary("20.0km")
        composeRule.onNodeWithTag(ElevationTags.CHART).assertIsDisplayed()
        screenshot("v170-plan")
    }

    /** The whole screen into Download/bikeRecoderTest/[name].png (an older file of the name is deleted first). */
    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val resolver = instrumentation.targetContext.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/bikeRecoderTest/"
        runCatching {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("$name.png", relative),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "PlanScreenTest"

        /**
         * 20 km from the start through the stops, a point every 500 m: rolling, with a 2 km climb at about 6% after 6 km
         * and a 1 km one at about 9% after 14 km; the waypoint at 10 km.
         */
        fun hillyRoute(request: RouteRequest): Route {
            val n = 41
            val from = request.start
            val to = request.stops.last()
            val points = (0 until n).map { i ->
                val t = i / (n - 1.0)
                GeoPoint(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t)
            }
            val elevations = (0 until n).map { i ->
                val km = i * 0.5
                val climb1 = 120.0 * ((km - 6.0) / 2.0).coerceIn(0.0, 1.0)
                val climb2 = 90.0 * (km - 14.0).coerceIn(0.0, 1.0)
                val descent = -150.0 * ((km - 16.0) / 4.0).coerceIn(0.0, 1.0)
                20.0 + 5.0 * kotlin.math.sin(km) + climb1 + climb2 + descent
            }
            return Route(
                points = points,
                cumulativeM = (0 until n).map { it * 500.0 },
                instructions = emptyList(),
                stopPointIndices = if (request.stops.size > 1) listOf(20, n - 1) else listOf(n - 1),
                summary = RouteSummary(20_000.0, 230, 0.64, request.profile),
                elevationsM = elevations,
            )
        }
    }

    private fun planWithLongPress(): GeoPoint {
        composeRule.setContent {
            MaterialTheme {
                PlanScreen(
                    viewModel = viewModel,
                    map = { _, _ -> },
                    onBack = {},
                    onAddStop = {},
                    onStartNavigation = {},
                    onSaveFavorite = {},
                )
            }
        }
        composeRule.runOnIdle {
            viewModel.setDestination("B", GeoPoint(35.115, 129.04))
            viewModel.addWaypoint("A", GeoPoint(35.157, 129.06))
        }
        composeRule.waitForIdle()
        assertEquals(listOf("A", "B"), stopNames())
        val pressedPoint = GeoPoint(35.2, 129.1)
        composeRule.runOnIdle { viewModel.onLongPress(pressedPoint) }
        return pressedPoint
    }

    @Test
    fun longPressSheetAddsAWaypointBeforeTheDestination() {
        planWithLongPress()
        composeRule.onNodeWithText("주소를 확인하는 중…").assertExists()
        composeRule.onNodeWithText("즐겨찾기에 저장").assertIsNotEnabled()

        addressGate.complete("부산 해운대구 우동")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("부산 해운대구 우동").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("즐겨찾기에 저장").assertIsEnabled()
        composeRule.onNodeWithText("경유지로 추가").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("A", "부산 해운대구 우동", "B"), stopNames())
        composeRule.onNodeWithText("경유지로 추가").assertDoesNotExist()
    }

    @Test
    fun longPressSetDestinationKeepsWaypoints() {
        planWithLongPress()
        addressGate.complete("부산 해운대구 우동")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("부산 해운대구 우동").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("목적지로 설정").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("A", "부산 해운대구 우동"), stopNames())
    }
}

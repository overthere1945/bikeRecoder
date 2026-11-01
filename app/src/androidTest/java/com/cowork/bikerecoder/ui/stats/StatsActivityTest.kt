package com.cowork.bikerecoder.ui.stats

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import com.cowork.bikerecoder.core.stats.StoredBestEffort
import com.cowork.bikerecoder.ui.activity.ActivityChipTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The stats tab's activity chips (V1.9.0 spec §4): the chip row, the hike's cells, the goal rows under [전체] and the record
 * subheadings. Today is fixed on Wednesday 2026-10-28. Screenshot `v190-stats-hike` (Download/bikeRecoderTest).
 */
class StatsActivityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRides(rides: List<RideStat>) : RideStatsSource {
        val flow = MutableStateFlow(rides)
        override fun observeRides(): Flow<List<RideStat>> = flow
        override fun observeBestEfforts(): Flow<List<StoredBestEffort>> = flowOf(emptyList())
    }

    private class FakeGoals(initial: Map<ActivityType, GoalKm>) : GoalSettings {
        val flow = MutableStateFlow(initial)
        override val goals: Flow<Map<ActivityType, GoalKm>> = flow
        override suspend fun setGoals(activity: ActivityType, weeklyKm: Int?, yearlyKm: Int?) {
            flow.value = flow.value + (activity to GoalKm(weeklyKm, yearlyKm))
        }
    }

    private class MemoryChip(initial: ActivityType? = null) : StatsChipSettings {
        val chip = MutableStateFlow(initial)
        override val activity: Flow<ActivityType?> = chip
        override suspend fun setActivity(type: ActivityType?) {
            chip.value = type
        }
    }

    private fun ride(
        id: Long,
        date: String,
        km: Double,
        minutes: Long,
        ascent: Double,
        activity: ActivityType,
        elapsedMinutes: Long = 0,
        maxAltitudeM: Double? = null,
    ) = RideStat(
        segmentId = id,
        tripId = id,
        startedAt = LocalDate.parse(date).atTime(8, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() + id,
        localDate = LocalDate.parse(date),
        distanceM = km * 1000.0,
        movingTimeMs = minutes * 60_000,
        ascentM = ascent,
        endedAt = 1L,
        activity = activity,
        elapsedMs = elapsedMinutes * 60_000,
        maxAltitudeM = maxAltitudeM,
    )

    /** October: two rides of each activity, so each has records; the hikes are 4 h 0 min and 3 h 10 min long. */
    private val rides = listOf(
        ride(1, "2026-10-06", 24.1, 80, 95.0, ActivityType.BIKE),
        ride(2, "2026-10-13", 36.0, 120, 205.0, ActivityType.BIKE),
        ride(3, "2026-10-07", 3.2, 45, 12.0, ActivityType.WALK),
        ride(4, "2026-10-14", 4.1, 55, 20.0, ActivityType.WALK),
        ride(5, "2026-10-08", 5.0, 28, 30.0, ActivityType.RUN),
        ride(6, "2026-10-21", 10.2, 55, 60.0, ActivityType.RUN),
        ride(7, "2026-10-10", 8.4, 190, 610.0, ActivityType.HIKE, elapsedMinutes = 240, maxAltitudeM = 954.0),
        ride(8, "2026-10-27", 6.1, 150, 430.0, ActivityType.HIKE, elapsedMinutes = 190, maxAltitudeM = 812.0),
    )

    private val cumulative = mutableListOf<Pair<PeriodFilter, ActivityType?>>()
    private lateinit var viewModel: StatsViewModel

    private fun show(
        chip: ActivityType? = null,
        goals: Map<ActivityType, GoalKm> = emptyMap(),
    ) {
        viewModel = StatsViewModel(
            FakeRides(rides), FakeGoals(goals), { LocalDate.of(2026, 10, 28) }, WarnLog { _, _ -> }, Dispatchers.Main, MemoryChip(chip),
        )
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.systemBarsPadding()) {
                    StatsScreen(
                        viewModel,
                        onOpenSegment = {},
                        onOpenCumulative = { filter, activity -> cumulative += filter to activity },
                        scrollToTop = 0,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun chip(type: ActivityType?) = composeRule.onNodeWithTag(ActivityChipTags.chip(type))

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(StatsTags.LIST).performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    @Test
    fun chipsStartOnAllAndFilterTheNumbers() {
        show()

        for (type in listOf(null) + ActivityType.entries) chip(type).assertIsDisplayed()
        chip(null).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.cell(StatCellKind.RIDES)).assertTextEquals("8회")

        chip(ActivityType.RUN).performClick()
        composeRule.waitForIdle()
        chip(ActivityType.RUN).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.cell(StatCellKind.RIDES)).assertTextEquals("2회")
        composeRule.onNodeWithTag(StatsTags.DISTANCE).assertTextEquals("15.2 km")
        // 83 min for 15.2 km: 5'28" a kilometre.
        composeRule.onNodeWithTag(StatsTags.cell(StatCellKind.PACE)).assertTextEquals("5'28\"/km")
    }

    @Test
    fun hikeCellsShowElapsedAndAscent() {
        show(chip = ActivityType.HIKE)

        chip(ActivityType.HIKE).assertIsSelected()
        composeRule.onNodeWithTag(StatsTags.cell(StatCellKind.RIDES)).assertTextEquals("2회")
        composeRule.onNodeWithTag(StatsTags.cell(StatCellKind.ELAPSED)).assertTextEquals("7시간 10분")
        composeRule.onNodeWithTag(StatsTags.cell(StatCellKind.ASCENT)).assertTextEquals("1,040 m")
    }

    @Test
    fun goalRowsPerActivityUnderAll() {
        show(goals = mapOf(ActivityType.BIKE to GoalKm(100, 2000), ActivityType.HIKE to GoalKm(null, 100)))

        composeRule.onNodeWithTag(StatsTags.goalRow(ActivityType.BIKE), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(StatsTags.goalRow(ActivityType.HIKE), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(StatsTags.goalRow(ActivityType.RUN), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(StatsTags.GOAL_SET).assertDoesNotExist()

        // A chip shows only its own goal; this one has none yet.
        chip(ActivityType.WALK).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(StatsTags.GOAL_SET).assertIsDisplayed()
    }

    @Test
    fun noGoalsShowsSetGoal() {
        show()
        composeRule.onNodeWithTag(StatsTags.GOAL_SET).assertIsDisplayed()
    }

    @Test
    fun recordSubheadingsUnderAll() {
        show()
        scrollTo(StatsTags.record(0))

        // Each activity's records come under its name; the weeks, months and streak of everything under "전체".
        val headings = viewModel.state.value!!.records.mapIndexedNotNull { index, row -> row.heading?.let { index to it } }
        assertEquals(listOf("자전거", "걷기", "달리기", "등산", "전체"), headings.map { it.second })
        for ((index, name) in headings) {
            composeRule.onNodeWithTag(StatsTags.LIST).performScrollToNode(hasTestTag(StatsTags.recordHeading(index)))
            composeRule.onNodeWithTag(StatsTags.recordHeading(index)).assertTextEquals(name)
        }
        composeRule.onNodeWithText("가장 긴 달리기").assertExists()
        composeRule.onNodeWithText("가장 높이 오른 등산").assertExists()
    }

    @Test
    fun cumulativeMapOpensWithTheChip() {
        show(chip = ActivityType.WALK)
        scrollTo(StatsTags.CUMULATIVE)
        composeRule.onNodeWithTag(StatsTags.CUMULATIVE).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(PeriodFilter(year = 2026, month = 10) to ActivityType.WALK), cumulative)
    }

    /** Download/bikeRecoderTest/v190-stats-hike.png. */
    @Test
    fun screenshotHike() {
        show(chip = ActivityType.HIKE, goals = mapOf(ActivityType.HIKE to GoalKm(30, 600)))
        composeRule.onNodeWithTag(StatsTags.kind(PeriodKind.MONTH)).assertIsSelected()
        screenshot("v190-stats-hike")
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
        const val TAG = "StatsActivityTest"
    }
}

package com.cowork.bikerecoder.ui.record

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.recording.RideStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The numbers page of each activity (V1.9.0 spec §3.2): run and walk paces, the hike's two main numbers and sunset line. */
class RideNumbersPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val run = RideStats.ZERO.copy(
        distanceM = 5_000.0,
        movingTimeMs = 1_660_000,
        elapsedMs = 1_700_000,
        avgSpeedMps = 5_000.0 / 1_660.0,
        recentSpeedMps = 1000.0 / 330.0,
        lastLapMovingTimeMs = 328_000,
        altitudeM = 41.0,
        ascentM = 30.0,
        descentM = 12.0,
    )

    private val hike = RideStats.ZERO.copy(
        distanceM = 4_200.0,
        movingTimeMs = 5_000_000,
        elapsedMs = 5_025_000,
        altitudeM = 812.4,
        ascentM = 520.0,
        descentM = 35.0,
    )

    private val zone = ZoneId.of("Asia/Seoul") // the sunset line is in Asia/Seoul, whatever the device zone
    private val sunset = ZonedDateTime.of(2026, 11, 1, 17, 32, 0, 0, zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun show(
        stats: RideStats,
        activity: ActivityType,
        sunsetMillis: Long? = null,
        nowMillis: Long = 0L,
        dimmed: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    RideNumbersPage(stats, activity, sunsetMillis, nowMillis, dimmed)
                }
            }
        }
    }

    @Test
    fun runShowsPaceCells() {
        show(run, ActivityType.RUN)

        composeRule.onNodeWithText("현재 페이스").assertIsDisplayed()
        composeRule.onNodeWithTag(NumbersTags.CURRENT).assertIsDisplayed()
        composeRule.onNodeWithText("5'30\"/km").assertIsDisplayed()
        composeRule.onNodeWithText("평균 페이스").assertIsDisplayed()
        composeRule.onNodeWithText("5'32\"/km").assertIsDisplayed()
        composeRule.onNodeWithText("직전 1 km").assertIsDisplayed()
        composeRule.onNodeWithText("5'28\"/km").assertIsDisplayed()
        composeRule.onNodeWithText("현재 속도").assertDoesNotExist()
    }

    @Test
    fun runBeforeTheFirstKilometreAndPaused() {
        show(run.copy(lastLapMovingTimeMs = null), ActivityType.RUN, dimmed = true)

        composeRule.onNodeWithText("직전 1 km").assertIsDisplayed()
        // Both the paused current pace and the missing last kilometre.
        composeRule.onAllNodesWithText("--'--\"/km").assertCountEquals(2)
        composeRule.onNodeWithText("5'30\"/km").assertDoesNotExist()
    }

    @Test
    fun walkShowsTheAverageSpeedInPlaceOfTheLastKilometre() {
        show(run, ActivityType.WALK)

        composeRule.onNodeWithText("현재 페이스").assertIsDisplayed()
        composeRule.onNodeWithText("평균 속도").assertIsDisplayed()
        composeRule.onNodeWithText("10.8km/h").assertIsDisplayed()
        composeRule.onNodeWithText("직전 1 km").assertDoesNotExist()
    }

    @Test
    fun bikeStaysAsItWas() {
        show(run.copy(currentSpeedMps = 5.0), ActivityType.BIKE)

        composeRule.onNodeWithText("현재 속도").assertIsDisplayed()
        composeRule.onNodeWithText("18.0km/h").assertIsDisplayed()
        composeRule.onNodeWithText("최고").assertIsDisplayed()
        composeRule.onNodeWithText("현재 페이스").assertDoesNotExist()
    }

    @Test
    fun hikeShowsTimeAndAltitudeAtOneSize() {
        show(hike, ActivityType.HIKE, sunset, sunset - 100 * minute)

        composeRule.onNodeWithText("산행 시간").assertIsDisplayed()
        composeRule.onNodeWithText("현재 고도").assertIsDisplayed()
        composeRule.onNodeWithText("1:23:45").assertIsDisplayed()
        composeRule.onNodeWithText("812m").assertIsDisplayed()
        composeRule.onNodeWithText("누적 상승").assertIsDisplayed()
        composeRule.onNodeWithText("누적 하강").assertIsDisplayed()
        // One line of the same style: the same height means the same font size.
        val time = composeRule.onNodeWithTag(NumbersTags.HIKE_TIME).getBoundsInRoot().height
        val altitude = composeRule.onNodeWithTag(NumbersTags.HIKE_ALTITUDE).getBoundsInRoot().height
        assertEquals(time, altitude)
    }

    @Test
    fun hikeSunsetLineIsNormalMoreThanAnHourBefore() {
        show(hike, ActivityType.HIKE, sunset, sunset - 100 * minute)

        composeRule.onNodeWithText("일몰 17:32 · 1시간 40분 남음").assertIsDisplayed()
        composeRule.onNodeWithTag(NumbersTags.SUNSET).assertIsDisplayed()
        composeRule.onNodeWithTag(NumbersTags.SUNSET_WARNING).assertDoesNotExist()
    }

    @Test
    fun hikeSunsetLineIsAWarningWithinAnHour() {
        show(hike, ActivityType.HIKE, sunset, sunset - 50 * minute)

        composeRule.onNodeWithText("일몰 17:32 · 50분 남음").assertIsDisplayed()
        composeRule.onNodeWithTag(NumbersTags.SUNSET_WARNING).assertIsDisplayed()
        composeRule.onNodeWithTag(NumbersTags.SUNSET).assertDoesNotExist()
    }

    @Test
    fun sunsetLineAfterSunset() {
        show(hike, ActivityType.HIKE, sunset, sunset + 10 * minute)

        composeRule.onNodeWithText("일몰 17:32 · 지남").assertIsDisplayed()
        composeRule.onNodeWithTag(NumbersTags.SUNSET_WARNING).assertIsDisplayed()
    }

    @Test
    fun noSunsetLineWithoutALocation() {
        show(hike, ActivityType.HIKE, sunsetMillis = null)

        composeRule.onNodeWithTag(NumbersTags.SUNSET).assertDoesNotExist()
        composeRule.onNodeWithTag(NumbersTags.SUNSET_WARNING).assertDoesNotExist()
        composeRule.onNodeWithText("일몰", substring = true).assertDoesNotExist()
    }

    /** Download/bikeRecoderTest/v190-hike-numbers.png. */
    @Test
    fun screenshotHike() {
        show(hike, ActivityType.HIKE, sunset, sunset - 50 * minute)
        screenshot("v190-hike-numbers")
    }

    /** Download/bikeRecoderTest/v190-run-numbers.png. */
    @Test
    fun screenshotRun() {
        show(run, ActivityType.RUN)
        screenshot("v190-run-numbers")
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
        const val TAG = "RideNumbersPageTest"
    }
}

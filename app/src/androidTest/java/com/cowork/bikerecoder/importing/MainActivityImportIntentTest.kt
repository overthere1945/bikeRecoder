package com.cowork.bikerecoder.importing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.cowork.bikerecoder.BikeApp
import com.cowork.bikerecoder.MainActivity
import com.cowork.bikerecoder.ui.importing.ImportTags
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Files shared or opened with the app (V1.6.0 spec §1.1) open the review list. The GPX files come from the test APK's assets,
 * copied to the app's cache and handed over as content URIs of the debug build's test FileProvider.
 *
 * The tests stop at the review: nothing is imported. Onboarding is passed the way a user who allowed the location and chose
 * "나중에" for the rest would (the earlier choices are put back afterwards); the 2001 rides overlap nothing on the phone.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityImportIntentTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<BikeApp>()
    private val dir = File(app.cacheDir, "import-test")
    private var skippedBefore: Set<OnboardingStep>? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() = runBlocking {
        val settings = app.container.settings
        skippedBefore = settings.settings.first().skippedOnboardingSteps
        // "나중에" on every step that has it; the location itself is granted above.
        val later = OnboardingStep.entries - OnboardingStep.FINE_LOCATION - OnboardingStep.DONE
        settings.update { it.copy(skippedOnboardingSteps = it.skippedOnboardingSteps + later) }
        dir.mkdirs()
        Unit
    }

    /** Every step runs even when an earlier one fails, so the onboarding choices are always put back and the files removed. */
    @After
    fun tearDown() = runBlocking {
        val steps = listOf<suspend () -> Unit>(
            { scenario?.close() },
            { app.container.importHandoff.take() },
            { dir.deleteRecursively() },
            { skippedBefore?.let { before -> app.container.settings.update { it.copy(skippedOnboardingSteps = before) } } },
        )
        val failures = steps.mapNotNull { step -> runCatching { step() }.exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
        Unit
    }

    /** A GPX of the test APK, as a content URI of the app's test FileProvider. */
    private fun fixture(name: String): Uri {
        val file = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("import/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.testfiles", file)
    }

    private fun launch(intent: Intent) {
        intent.setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scenario = ActivityScenario.launch(intent)
    }

    /** The review list is up with [rides] new rides from [files] files, each [rows] row shown and none imported. */
    private fun assertReview(rides: Int, files: Int, rows: List<String>) {
        val header = headerText(rides, rides)
        try {
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodes(hasText(header)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("No review list; the screen shows ${screenTexts()}", e)
        }
        composeRule.onNodeWithText(IMPORT_SCREEN_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(header).assertIsDisplayed()
        composeRule.onNodeWithText(filesText(files)).assertIsDisplayed()
        rows.forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        composeRule.onNodeWithTag(ImportTags.IMPORT).assertIsDisplayed()
        composeRule.onNodeWithText(importButtonText(rides)).assertIsDisplayed()
    }

    /** Every text on screen, to tell where the app went instead. */
    private fun screenTexts(): List<String> {
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
                node.children.flatMap(::texts)
        return composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap(::texts)
    }

    @Test
    fun sendIntentOpensImport() {
        val uri = fixture("intent_a.gpx")

        launch(Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri))

        assertReview(rides = 1, files = 1, rows = listOf(ImportTags.row(RideKey(0, 0))))
    }

    @Test
    fun viewIntentOpensImport() {
        val uri = fixture("intent_a.gpx")

        launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/gpx+xml"))

        assertReview(rides = 1, files = 1, rows = listOf(ImportTags.row(RideKey(0, 0))))
    }

    @Test
    fun sendMultipleOpensImportWithAll() {
        val uris = arrayListOf(fixture("intent_a.gpx"), fixture("intent_b.gpx"))

        launch(
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/gpx+xml").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris),
        )

        assertReview(rides = 2, files = 2, rows = listOf(ImportTags.row(RideKey(0, 0)), ImportTags.row(RideKey(1, 0))))
    }

    @Test
    fun gpxAndOctetStreamResolveToMainActivity() {
        val uri = Uri.parse("content://com.android.providers.downloads.documents/document/1")
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_VIEW)) {
            for (type in listOf("application/gpx+xml", "application/octet-stream")) {
                val intent = Intent(action).setDataAndType(uri, type)
                if (action == Intent.ACTION_SEND) intent.putExtra(Intent.EXTRA_STREAM, uri)

                @Suppress("DEPRECATION")
                val found = app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

                assertTrue(
                    "$action $type resolves to ${found.map { it.activityInfo.name }}",
                    found.any { it.activityInfo.packageName == app.packageName && it.activityInfo.name == MainActivity::class.java.name },
                )
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}

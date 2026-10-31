package com.cowork.bikerecoder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.cowork.bikerecoder.importing.importUris
import com.cowork.bikerecoder.nav.NavNotifications
import com.cowork.bikerecoder.ui.BikeNavHost
import com.cowork.bikerecoder.ui.LaunchRequest
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.VideoNotifications

class MainActivity : ComponentActivity() {
    private val launchRequest = mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BikeApp).container
        // On recreation the request was already handled (the intent still carries it).
        if (savedInstanceState == null) launchRequest.value = intent.launchRequest()
        // A fresh start offers again a video job a forced kill left unfinished (spec §3.3).
        val freshStart = savedInstanceState == null
        setContent {
            MaterialTheme {
                BikeNavHost(
                    container = container,
                    launchRequest = launchRequest.value,
                    onLaunchRequestHandled = { launchRequest.value = null },
                    offerUnfinishedVideo = freshStart,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The app came on screen: post-process ended rides (photos, heart rate, routes) — a no-op while one is running.
        (application as BikeApp).container.postProcessor.request()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.launchRequest()?.let { launchRequest.value = it }
    }

    private fun Intent.launchRequest(): LaunchRequest? {
        val tripId = getLongExtra(NavNotifications.EXTRA_RESUME_TRIP_ID, -1L)
        // Reopened from the recent apps with the notification's old intent: that preview was already shown.
        val fromHistory = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val previewUri = getStringExtra(VideoNotifications.EXTRA_PREVIEW_URI)?.takeUnless { fromHistory }
        val previewKind = MediaKind.entries.firstOrNull { it.name == getStringExtra(VideoNotifications.EXTRA_PREVIEW_KIND) }
        val importUris = sharedFiles(fromHistory)
        return when {
            importUris.isNotEmpty() -> LaunchRequest.Import(importUris)
            previewUri != null && previewKind != null -> LaunchRequest.Preview(
                previewUri,
                previewKind,
                downscaled = getBooleanExtra(VideoNotifications.EXTRA_PREVIEW_DOWNSCALED, false),
                satelliteFallback = getBooleanExtra(VideoNotifications.EXTRA_PREVIEW_SATELLITE_FALLBACK, false),
            )
            tripId >= 0 -> LaunchRequest.ResumeTrip(tripId)
            getBooleanExtra(NavNotifications.EXTRA_SHOW_NAVIGATION, false) -> LaunchRequest.ShowNavigation
            else -> null
        }
    }

    /** The files shared or opened with the app (V1.6.0 spec §1.1): see [importUris]. */
    private fun Intent.sharedFiles(fromHistory: Boolean): List<String> = importUris(
        action = action,
        // Each read only for its action: the other getter would log a type mismatch.
        stream = if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
        } else {
            null
        },
        streams = if (action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().mapNotNull { it?.toString() }
        } else {
            emptyList()
        },
        data = data?.toString(),
        clip = clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri?.toString() } }.orEmpty(),
        fromHistory = fromHistory,
    )
}

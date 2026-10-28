package com.cowork.bikerecoder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
        return when {
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
}

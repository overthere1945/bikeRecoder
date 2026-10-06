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

class MainActivity : ComponentActivity() {
    private val launchRequest = mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BikeApp).container
        // On recreation the request was already handled (the intent still carries it).
        if (savedInstanceState == null) launchRequest.value = intent.launchRequest()
        setContent {
            MaterialTheme {
                BikeNavHost(
                    container = container,
                    launchRequest = launchRequest.value,
                    onLaunchRequestHandled = { launchRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.launchRequest()?.let { launchRequest.value = it }
    }

    private fun Intent.launchRequest(): LaunchRequest? {
        val tripId = getLongExtra(NavNotifications.EXTRA_RESUME_TRIP_ID, -1L)
        return when {
            tripId >= 0 -> LaunchRequest.ResumeTrip(tripId)
            getBooleanExtra(NavNotifications.EXTRA_SHOW_NAVIGATION, false) -> LaunchRequest.ShowNavigation
            else -> null
        }
    }
}

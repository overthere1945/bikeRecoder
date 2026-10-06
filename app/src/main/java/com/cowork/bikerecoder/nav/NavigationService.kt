package com.cowork.bikerecoder.nav

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cowork.bikerecoder.BikeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Location foreground service: the process anchor while [NavigationController] guides. It owns no
 * navigation logic; it mirrors [NavigationController.ui] into the notification and stops itself once
 * guidance is no longer starting or running.
 *
 * Android 12+ forbids starting a location FGS from the background, and only one started while an activity
 * is visible gets while-in-use location (spec §2.2), so [start] must be called from the visible screen.
 * If the system kills the process, START_STICKY brings the service back with a null intent; it cannot
 * resume guidance from there, so it posts "안내가 중단되었습니다. 눌러서 재개" and stops.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mirror: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as BikeApp).container
        if (intent == null) {
            scope.launch {
                val tripId = try {
                    container.tripStore.activeTrip()?.id
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the interrupted trip", e)
                    null
                }
                if (tripId != null) NavNotifications.showInterrupted(this@NavigationService, tripId)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val controller = container.navigation
        NavNotifications.ensureChannel(this)
        try {
            ServiceCompat.startForeground(
                this,
                NavNotifications.PROGRESS_ID,
                NavNotifications.progress(this, controller.ui.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // Not allowed now (background start, location permission revoked): guidance still runs while
            // the screen is visible, just without screen-off protection.
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (mirror == null) {
            mirror = scope.launch {
                var shown: String? = null
                controller.ui.collect { state ->
                    when (state) {
                        is NavUiState.Starting, is NavUiState.Active -> {
                            val text = NavNotifications.contentText(state)
                            if (text != shown) {
                                shown = text
                                NavNotifications.notifyProgress(this@NavigationService, state)
                            }
                        }
                        NavUiState.Idle, is NavUiState.Failed, is NavUiState.Finished -> {
                            ServiceCompat.stopForeground(this@NavigationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NavigationService"

        /**
         * Call from the visible activity, right before [NavigationController.begin]. Guidance is starting
         * again, so an earlier "안내가 중단되었습니다" notification is withdrawn.
         */
        fun start(context: Context) {
            NavNotifications.cancelInterrupted(context)
            try {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException etc.: guide without the service.
                Log.w(TAG, "Could not start the navigation service", e)
            }
        }
    }
}

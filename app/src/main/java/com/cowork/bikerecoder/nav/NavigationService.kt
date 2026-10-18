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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The one ride foreground service (type location): the process anchor while [NavigationController] guides or
 * [com.cowork.bikerecoder.ride.RideController] records. It owns no guidance or recording logic; it mirrors both
 * states ([NavNotifications.rideNotice]) into the notification and stops itself once neither is starting or running
 * and the last announcement has been spoken (at most 5 s). A failed foreground start is reported through
 * [com.cowork.bikerecoder.nav.GuidanceServiceStatus].
 *
 * Android 12+ forbids starting a location FGS from the background, and only one started while an activity
 * is visible gets while-in-use location (spec §2.2), so [start] must be called from the visible screen.
 * If the system kills the process, START_STICKY brings the service back with a null intent; it cannot
 * resume from there, so it posts "주행이 중단되었습니다. 눌러서 재개" ([NavNotifications.interruptedNotice]) and stops.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mirror: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as BikeApp).container
        if (intent == null) {
            scope.launch {
                val notice = try {
                    val trip = container.tripStore.activeTrip()
                    val stops = trip?.let { container.tripManager.remainingStops(it.id).size } ?: 0
                    NavNotifications.interruptedNotice(trip, stops, container.rideStore.openSegments().size)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the interrupted ride", e)
                    null
                }
                if (notice != null) NavNotifications.showInterrupted(this@NavigationService, notice)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // The recording follows the guidance, so it must exist whenever the service runs (this creates both).
        container.ride
        NavNotifications.ensureChannel(this)
        val nav = container.navigationState
        val initial = NavNotifications.rideNotice(nav, container.rideState) ?: STARTING
        try {
            ServiceCompat.startForeground(
                this,
                NavNotifications.PROGRESS_ID,
                NavNotifications.progress(this, initial),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // Not allowed now (background start, location permission revoked): guidance and recording still run
            // while the screen is visible, just without screen-off protection — the guidance screen says so.
            Log.w(TAG, "startForeground failed", e)
            container.guidanceService.foregroundFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        container.guidanceService.foregroundStarted()

        if (mirror == null) {
            mirror = scope.launch {
                // Latest: a new start while waiting for the voice below cancels the pending stop.
                combine(container.navigationUi, container.rideUi, NavNotifications::rideNotice)
                    .distinctUntilChanged()
                    .collectLatest { shown ->
                        if (shown != null) {
                            NavNotifications.notifyProgress(this@NavigationService, shown)
                        } else {
                            // Stay in the foreground until "목적지에 도착했습니다" (or whatever is queued) is spoken.
                            awaitVoiceIdle(container.voice.speaking, VOICE_DRAIN_MAX_MS)
                            ServiceCompat.stopForeground(this@NavigationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
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

        /** The longest the service waits for the last announcement before it stops. */
        private const val VOICE_DRAIN_MAX_MS = 5_000L

        /** Only shown if nothing is starting by the time the service starts (it then stops right away). */
        private val STARTING = RideNotice("주행", "준비하는 중…")

        /**
         * Call from the visible activity, right before [NavigationController.begin] or
         * [com.cowork.bikerecoder.ride.RideController.startRecording] (both are Starting when they return). A ride is
         * starting again, so an earlier "주행이 중단되었습니다" notification is withdrawn.
         */
        fun start(context: Context) {
            NavNotifications.cancelInterrupted(context)
            // Also here, so guidance still starts its recording when the service below cannot start.
            (context.applicationContext as BikeApp).container.ride
            try {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException etc.: guide and record without the service.
                Log.w(TAG, "Could not start the ride service", e)
                (context.applicationContext as BikeApp).container.guidanceService.foregroundFailed()
            }
        }
    }
}

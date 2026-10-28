package com.cowork.bikerecoder.video

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The foreground service while [VideoJob] makes a video or image (spec §3.2): type `mediaProcessing` from Android 15,
 * `dataSync` below. It owns no job logic: it mirrors [VideoJob.state] into the ongoing notification, [취소] cancels
 * the job, and once the job has ended it posts the result ([VideoNotifications.showResult]) and stops itself. The job
 * runs in the app process either way; the service keeps that process alive with the screen off or the app left.
 * START_NOT_STICKY: after a kill the job is gone, and its pending record offers it again ([VideoJob.unfinished]).
 */
class VideoService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mirror: Job? = null

    /** The latest start: stopping with it never stops a service a newer start still needs. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = (application as BikeApp).container.videoJob
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            job.cancel()
            if (mirror == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        VideoNotifications.ensureChannel(this)
        val text = VideoNotifications.progressText(job.state.value) ?: STARTING
        try {
            // Not ServiceCompat: it masks the type to the API 34 set, which turns mediaProcessing (API 35) into none.
            startForeground(VideoNotifications.PROGRESS_ID, VideoNotifications.progress(this, text), foregroundType())
        } catch (e: Exception) {
            // Not allowed now (e.g. started from the background): the job still runs while the app is alive.
            Log.w(TAG, "startForeground failed", e)
            if (mirror == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        if (mirror == null) {
            mirror = scope.launch {
                var posted = text
                do {
                    // The first state without progress is how the job ended (re-posted only when the text changes).
                    val ended = job.state.first { state ->
                        val shown = VideoNotifications.progressText(state)
                        if (shown != null && shown != posted) {
                            VideoNotifications.notifyProgress(this@VideoService, shown)
                            posted = shown
                        }
                        shown == null
                    }
                    VideoNotifications.showResult(this@VideoService, ended)
                    // A next job may already have started (same instance): keep mirroring it.
                } while (VideoNotifications.progressText(job.state.value) != null)
                // On Main, so no start command comes between these lines.
                mirror = null
                ServiceCompat.stopForeground(this@VideoService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+: a `mediaProcessing` service may run 6 hours a day. Past that the job is cancelled and the service
     * leaves the foreground and stops at once (required within seconds); the job's cleanup goes on in the app scope.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time limit reached; cancelling the video job")
        (application as BikeApp).container.videoJob.cancel()
        mirror?.cancel()
        mirror = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VideoService"
        private const val ACTION_CANCEL = "com.cowork.bikerecoder.video.CANCEL"

        /** Only shown if the job ended before the service started (it then stops at once). */
        private const val STARTING = "영상 만드는 중 0%"

        private fun foregroundType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

        /** Called by [VideoJob] when a job starts (from the visible screen, so the foreground start is allowed). */
        fun start(context: Context) {
            VideoNotifications.cancelResult(context)
            try {
                ContextCompat.startForegroundService(context, Intent(context, VideoService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException etc.: the job runs without screen-off protection.
                Log.w(TAG, "Could not start the video service", e)
            }
        }

        /** [취소] on the ongoing notification. */
        fun cancelIntent(context: Context): PendingIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, VideoService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

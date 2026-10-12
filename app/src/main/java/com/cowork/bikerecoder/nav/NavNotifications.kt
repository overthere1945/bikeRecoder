package com.cowork.bikerecoder.nav

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cowork.bikerecoder.MainActivity
import com.cowork.bikerecoder.R
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.ride.RideUiState

/** What the ride service's ongoing notification says. */
data class RideNotice(val title: String, val text: String)

/** "주행이 중단되었습니다": tapping resumes guidance of [resumeTripId], or (null) opens the map main screen. */
data class InterruptedNotice(val resumeTripId: Long?)

/**
 * The ride notifications (guidance and recording share one foreground service): channel `navigation` (importance
 * LOW), the ongoing progress and "interrupted".
 */
object NavNotifications {
    const val CHANNEL_ID = "navigation"
    const val PROGRESS_ID = 1001
    private const val INTERRUPTED_ID = 1002

    /** MainActivity extra: resume guidance of this trip (from the "interrupted" notification). */
    const val EXTRA_RESUME_TRIP_ID = "resume_trip_id"

    /**
     * MainActivity extra: show the running ride (from the progress notification) — the guidance screen while guiding,
     * else the record screen, decided when tapped ([com.cowork.bikerecoder.ui.rideRoute]).
     */
    const val EXTRA_SHOW_NAVIGATION = "show_navigation"

    private val phrases = KoreanPhrases()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "주행", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** "{다음 안내} · 남은 {거리}" while guiding. */
    fun contentText(state: NavUiState): String = when (state) {
        is NavUiState.Active -> {
            val progress = state.state.progress
            val next = progress.nextInstruction
            val toNext = progress.distanceToNextInstructionM
            val nextText = if (next != null && toNext != null) {
                "${SummaryFormatter.distance(toNext)} 앞 ${phrases.turn(next.type, next.roundaboutExit)}"
            } else {
                "목적지까지"
            }
            "$nextText · 남은 ${SummaryFormatter.distance(progress.remainingM)}"
        }
        else -> "경로를 계산하는 중…"
    }

    /**
     * The ongoing notification for guidance [nav] and recording [ride]; null when neither is starting or running
     * (the service then stops).
     */
    fun rideNotice(nav: NavUiState, ride: RideUiState): RideNotice? {
        val guiding = isGuiding(nav)
        return when {
            guiding && ride is RideUiState.Active -> RideNotice("길 안내 중 · 기록 중", contentText(nav))
            guiding -> RideNotice("길 안내 중", contentText(nav))
            ride is RideUiState.Starting -> RideNotice("주행 기록 중", "기록을 준비하는 중…")
            ride is RideUiState.Active -> {
                val text = "${RideFormat.distance(ride.stats.distanceM)} · 이동 ${RideFormat.duration(ride.stats.movingTimeMs)}"
                RideNotice(if (ride.state is RecordingState.Paused) "일시정지됨" else "주행 기록 중", text)
            }
            else -> null
        }
    }

    fun isGuiding(nav: NavUiState): Boolean = nav is NavUiState.Starting || nav is NavUiState.Active

    /**
     * After the system killed the ride (START_STICKY restart): nothing to say without an active trip or an open
     * segment. An active trip with stops left resumes its guidance (and guidance auto-recording closes the open
     * segment and starts a new one); otherwise the tap opens the map main screen, which offers the interrupted
     * recording.
     */
    fun interruptedNotice(activeTrip: Trip?, remainingStops: Int, openSegments: Int): InterruptedNotice? {
        val trip = activeTrip?.takeIf { it.status == TripStatus.ACTIVE }
        if (trip == null && openSegments == 0) return null
        return InterruptedNotice(resumeTripId = trip?.id?.takeIf { remainingStops > 0 })
    }

    /** A tap shows the running guidance or recording ([EXTRA_SHOW_NAVIGATION]). */
    fun progress(context: Context, notice: RideNotice): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityIntent(context, PROGRESS_ID) { putExtra(EXTRA_SHOW_NAVIGATION, true) })
            .build()

    fun notifyProgress(context: Context, notice: RideNotice) = notify(context, PROGRESS_ID, progress(context, notice))

    /** "주행이 중단되었습니다. 눌러서 재개" (see [interruptedNotice]). */
    fun showInterrupted(context: Context, notice: InterruptedNotice) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle("주행이 중단되었습니다. 눌러서 재개")
            .setAutoCancel(true)
            .setContentIntent(
                activityIntent(context, INTERRUPTED_ID) {
                    notice.resumeTripId?.let { putExtra(EXTRA_RESUME_TRIP_ID, it) }
                },
            )
            .build()
        notify(context, INTERRUPTED_ID, notification)
    }

    fun cancelInterrupted(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INTERRUPTED_ID)
    }

    private fun activityIntent(context: Context, requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply(extras)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Whether a notification may be posted: the app's notifications must be enabled, and from Android 13
     * (API 33) the POST_NOTIFICATIONS runtime permission must be granted too (it does not exist before).
     */
    fun canPost(sdkInt: Int, notificationsEnabled: Boolean, postPermissionGranted: Boolean): Boolean =
        notificationsEnabled && (sdkInt < Build.VERSION_CODES.TIRAMISU || postPermissionGranted)

    /** Without POST_NOTIFICATIONS (skippable in onboarding) the notification is simply not shown. */
    private fun notify(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!canPost(Build.VERSION.SDK_INT, manager.areNotificationsEnabled(), granted)) return
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

package com.cowork.bikerecoder.nav

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cowork.bikerecoder.MainActivity
import com.cowork.bikerecoder.R
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.voice.KoreanPhrases

/** The guidance notifications: channel `navigation` (importance LOW), progress and "interrupted". */
object NavNotifications {
    const val CHANNEL_ID = "navigation"
    const val PROGRESS_ID = 1001
    private const val INTERRUPTED_ID = 1002

    /** MainActivity extra: resume guidance of this trip (from the "interrupted" notification). */
    const val EXTRA_RESUME_TRIP_ID = "resume_trip_id"

    /** MainActivity extra: show the running guidance (from the progress notification). */
    const val EXTRA_SHOW_NAVIGATION = "show_navigation"

    private val phrases = KoreanPhrases()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "길 안내", NotificationManager.IMPORTANCE_LOW)
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

    fun progress(context: Context, state: NavUiState): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle("길 안내 중")
            .setContentText(contentText(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityIntent(context, PROGRESS_ID) { putExtra(EXTRA_SHOW_NAVIGATION, true) })
            .build()

    fun notifyProgress(context: Context, state: NavUiState) = notify(context, PROGRESS_ID, progress(context, state))

    /** After the system killed guidance (START_STICKY restart): tap to resume [tripId] from the app. */
    fun showInterrupted(context: Context, tripId: Long) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle("안내가 중단되었습니다. 눌러서 재개")
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, INTERRUPTED_ID) { putExtra(EXTRA_RESUME_TRIP_ID, tripId) })
            .build()
        notify(context, INTERRUPTED_ID, notification)
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

    /** Without POST_NOTIFICATIONS (skippable in onboarding) the notification is simply not shown. */
    private fun notify(context: Context, id: Int, notification: Notification) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

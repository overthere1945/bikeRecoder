package com.cowork.bikerecoder.video

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

/**
 * The video job's notifications (spec §3.2): channel `video` (importance LOW). The ongoing "영상 만드는 중 N% · 약 M분
 * 남음" with [취소] while [VideoService] runs, then one result: "영상을 만들었습니다" (a tap opens the preview, see
 * [EXTRA_PREVIEW_URI]) or the failure's message ("기록이 시작되어 영상 만들기를 멈췄습니다", …). Nothing after [취소].
 */
object VideoNotifications {
    const val CHANNEL_ID = "video"
    const val PROGRESS_ID = 1101
    private const val RESULT_ID = 1102

    /**
     * MainActivity extras of the "영상을 만들었습니다" tap — open the preview of the made file (wired in MainActivity → BikeNavHost):
     * [EXTRA_PREVIEW_URI] gallery uri, [EXTRA_PREVIEW_KIND] [MediaKind] name, [EXTRA_PREVIEW_TRIP_ID],
     * [EXTRA_PREVIEW_MEDIA_ID] the `trip_media` id, [EXTRA_PREVIEW_DOWNSCALED] made at 720p,
     * [EXTRA_PREVIEW_SATELLITE_FALLBACK] made on the plain map instead of the satellite one (V1.3.1 spec §4).
     */
    const val EXTRA_PREVIEW_URI = "video_preview_uri"
    const val EXTRA_PREVIEW_KIND = "video_preview_kind"
    const val EXTRA_PREVIEW_TRIP_ID = "video_preview_trip_id"
    const val EXTRA_PREVIEW_MEDIA_ID = "video_preview_media_id"
    const val EXTRA_PREVIEW_DOWNSCALED = "video_preview_downscaled"
    const val EXTRA_PREVIEW_SATELLITE_FALLBACK = "video_preview_satellite_fallback"

    /** The ongoing text; null once the job has ended (the service then stops). */
    fun progressText(state: VideoJobState): String? = when (state) {
        is VideoJobState.Preparing -> if (state.kind == MediaKind.VIDEO) "영상 만드는 중 0%" else "이미지 만드는 중"
        is VideoJobState.Rendering -> "영상 만드는 중 ${state.percent}% · 약 ${state.minutesLeft}분 남음"
        VideoJobState.Encoding -> "영상 만드는 중 100%"
        else -> null
    }

    /** What is said when the job ends; null after [취소] (or while it runs). */
    fun resultText(state: VideoJobState): String? = when (state) {
        is VideoJobState.Done -> if (state.media.kind == MediaKind.VIDEO) "영상을 만들었습니다" else "이미지를 만들었습니다"
        is VideoJobState.Failed -> state.message
        else -> null
    }

    /** The result's second line: [VideoJob.MSG_SATELLITE_FALLBACK] when the plain map stood in (spec §4), else null. */
    fun resultDetail(state: VideoJobState): String? =
        if (state is VideoJobState.Done && state.satelliteFallback) VideoJob.MSG_SATELLITE_FALLBACK else null

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "영상 만들기", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The ongoing notification with [취소]; a tap opens the app. */
    fun progress(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_video)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityIntent(context, PROGRESS_ID) {})
            .addAction(0, "취소", VideoService.cancelIntent(context))
            .build()

    fun notifyProgress(context: Context, text: String) = notify(context, PROGRESS_ID, progress(context, text))

    /** "영상을 만들었습니다" (tap: preview; second line [resultDetail]) or the failure's message (tap: the app). */
    fun showResult(context: Context, state: VideoJobState) {
        val text = resultText(state) ?: return
        ensureChannel(context)
        val intent = activityIntent(context, RESULT_ID) {
            if (state is VideoJobState.Done) {
                putExtra(EXTRA_PREVIEW_URI, state.media.uri)
                putExtra(EXTRA_PREVIEW_KIND, state.media.kind.name)
                putExtra(EXTRA_PREVIEW_TRIP_ID, state.media.tripId)
                putExtra(EXTRA_PREVIEW_MEDIA_ID, state.media.id)
                putExtra(EXTRA_PREVIEW_DOWNSCALED, state.downscaled)
                putExtra(EXTRA_PREVIEW_SATELLITE_FALLBACK, state.satelliteFallback)
            }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_video)
            .setContentTitle(text)
            .setContentText(resultDetail(state))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        notify(context, RESULT_ID, notification)
    }

    /** A new job starts: the last one's result is withdrawn. */
    fun cancelResult(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
    }

    private fun activityIntent(context: Context, requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply(extras)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Without POST_NOTIFICATIONS (skippable in onboarding) nothing is shown. */
    private fun notify(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!manager.areNotificationsEnabled() || !granted) return
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

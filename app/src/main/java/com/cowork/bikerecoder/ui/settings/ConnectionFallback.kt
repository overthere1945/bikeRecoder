package com.cowork.bikerecoder.ui.settings

import com.cowork.bikerecoder.post.PhotoAccess
import com.cowork.bikerecoder.ui.health.HeartRateAccess

// "사진·심박 연결" (spec §5.1) is the way back after the cards were dismissed, so its [허용] is never a dead end: when
// the system no longer shows its permission screen (refused twice), the row opens the settings where it can be given.
// The cards keep their own flow.

/**
 * After the photo request from the row: photos are still not allowed ([access]) and Android will not show its dialog
 * again ([androidMayAskAgain] = `shouldShowRequestPermissionRationale`, false after "don't ask again" or a second
 * refusal) → open this app's settings page.
 */
internal fun opensAppSettingsAfterPhotoRequest(access: PhotoAccess, androidMayAskAgain: Boolean): Boolean =
    access == PhotoAccess.NONE && !androidMayAskAgain

/**
 * After the heart-rate request from the row: Health Connect is there but heart rate is still not allowed ([access]) →
 * open Health Connect's settings (its contract tells no "don't ask again" apart from a refusal).
 */
internal fun opensHealthConnectSettingsAfterRequest(access: HeartRateAccess): Boolean =
    access == HeartRateAccess.NO_PERMISSION

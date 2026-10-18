package com.cowork.bikerecoder.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Google Play 서비스 Fused Location 기반 [LocationSource] (고정밀, 1초 간격, 최소 이동거리 0).
 *
 * 호출자가 위치 권한(ACCESS_FINE_LOCATION)을 미리 확보해야 한다.
 * 권한이 없으면 flow가 [SecurityException]으로 종료된다.
 */
class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission") // 권한은 호출자 책임 — 없으면 SecurityException으로 flow를 닫는다.
    override fun fixes(): Flow<LocationFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) trySend(location.toFix())
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close(it) } // 비동기 실패(권한 회수, Play 서비스 없음 등)
        } catch (e: SecurityException) {
            close(e)
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toFix() = LocationFix(
        point = GeoPoint(latitude, longitude),
        accuracyM = if (hasAccuracy()) accuracy else 999f,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        timeMillis = time,
    )
}

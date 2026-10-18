package com.cowork.bikerecoder.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Looper
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Google Play 서비스 Fused Location 기반 [LocationSource] (고정밀, 1초 간격, 최소 이동거리 0).
 *
 * 고도는 API 34+에서 해발(MSL)로 변환한다. 변환기는 디스크의 지오이드 자료를 읽어 막힐 수 있으므로
 * 변환은 [Dispatchers.IO]에서 한다. 변환이 실패하거나 API 33 이하면 기기 고도(타원체 기준일 수 있음)를 그대로 쓴다.
 *
 * 호출자가 위치 권한(ACCESS_FINE_LOCATION)을 미리 확보해야 한다.
 * 권한이 없으면 flow가 [SecurityException]으로 종료된다.
 */
class FusedLocationSource(context: Context) : LocationSource {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override fun fixes(): Flow<LocationFix> {
        // 수집마다 새 변환기를 쓴다(수집은 flowOn으로 한 스레드에서 순서대로 map한다).
        val converter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) AltitudeConverter() else null
        return locations().map { it.toFix(converter) }.flowOn(Dispatchers.IO)
    }

    @SuppressLint("MissingPermission") // 권한은 호출자 책임 — 없으면 SecurityException으로 flow를 닫는다.
    private fun locations(): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) trySend(location)
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

    private fun Location.toFix(converter: AltitudeConverter?) = LocationFix(
        point = GeoPoint(latitude, longitude),
        accuracyM = if (hasAccuracy()) accuracy else 999f,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        timeMillis = time,
        altitudeM = mslAltitudeOrNull(converter),
        verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    )

    /** 해발 고도. 변환할 수 없으면(API 33 이하, 변환 실패) 기기 고도, 그것도 없으면 null. */
    private fun Location.mslAltitudeOrNull(converter: AltitudeConverter?): Double? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && converter != null) {
            try {
                converter.addMslAltitudeToLocation(appContext, this)
                if (hasMslAltitude()) return mslAltitudeMeters
            } catch (e: Exception) {
                // 디스크 읽기 실패·잘못된 좌표 — 아래에서 기기 고도로 대신한다.
            }
        }
        return if (hasAltitude()) altitude else null
    }
}

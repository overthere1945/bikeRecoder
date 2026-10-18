package com.cowork.bikerecoder.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** 기압(hPa) 공급원. */
interface PressureSource {
    fun pressures(): Flow<Float>
}

/**
 * 기기 기압계([Sensor.TYPE_PRESSURE]). 약 1초에 한 번 값을 준다 — 고도 보정이 한 번에 5초 창을 평균 내므로 더 잦을 필요가 없다.
 * 기압계가 없으면 [available]이 false이고 [pressures]는 값 없이 바로 끝난다.
 */
class BarometerSource(context: Context) : PressureSource {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val available: Boolean = sensor != null

    override fun pressures(): Flow<Float> {
        val pressureSensor = sensor ?: return emptyFlow()
        return callbackFlow {
            var lastEmittedNanos = Long.MIN_VALUE
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // 샘플링 주기는 힌트일 뿐이라 기기에 따라 더 자주 온다. 직접 1초 간격으로 줄인다(지터 여유 100ms).
                    if (lastEmittedNanos != Long.MIN_VALUE && event.timestamp - lastEmittedNanos < MIN_INTERVAL_NANOS) return
                    lastEmittedNanos = event.timestamp
                    trySend(event.values[0])
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            if (!sensorManager.registerListener(listener, pressureSensor, SAMPLING_PERIOD_US)) {
                close(IllegalStateException("pressure sensor could not be registered"))
            }
            awaitClose { sensorManager.unregisterListener(listener) }
        }
    }

    private companion object {
        const val SAMPLING_PERIOD_US = 1_000_000
        const val MIN_INTERVAL_NANOS = 900_000_000L
    }
}

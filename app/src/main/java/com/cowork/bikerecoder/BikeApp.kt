package com.cowork.bikerecoder

import android.app.Application
import org.maplibre.android.MapLibre

class BikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}

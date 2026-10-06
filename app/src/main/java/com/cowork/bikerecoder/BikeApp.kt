package com.cowork.bikerecoder

import android.app.Application
import org.maplibre.android.MapLibre

class BikeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
    }
}

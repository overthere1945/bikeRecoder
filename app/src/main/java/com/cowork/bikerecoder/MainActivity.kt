package com.cowork.bikerecoder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.OpenFreeMapSource
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // TEMPORARY (Task 15 manual check): Task 17 replaces this with the nav graph.
        val points = listOf(
            GeoPoint(37.5663, 126.9779), GeoPoint(37.5700, 126.9830), GeoPoint(37.5750, 126.9870),
            GeoPoint(37.5800, 126.9850),
        )
        val route = Route(
            points = points,
            cumulativeM = GeoMath.cumulativeDistances(points),
            instructions = emptyList(),
            stopPointIndices = emptyList(),
            summary = RouteSummary(0.0, 0, 0.0, RouteProfile.BALANCED),
        )
        val tileSource = OpenFreeMapSource(OkHttpClient())
        setContent {
            BikeMap(
                tileSource = tileSource,
                overlay = MapOverlay(route, emptyList(), null),
                cameraMode = CameraMode.FREE,
                onLongPress = {},
                onUserGesture = {},
                modifier = Modifier.fillMaxSize(),
                initialCenter = GeoPoint(37.5663, 126.9779),
                initialZoom = 13.0,
            )
        }
    }
}

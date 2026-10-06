package com.cowork.bikerecoder.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Waits for a Wi-Fi-like (not metered) connection. Open so tests can substitute it. */
open class NetworkWaiter(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Returns at once if an unmetered network with internet is available; cancellable while waiting. */
    open suspend fun awaitUnmetered() {
        suspendCancellableCoroutine { cont ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!cont.isActive) return
                    unregister(this)
                    cont.resume(Unit)
                }
            }
            connectivity.registerNetworkCallback(request, callback)
            cont.invokeOnCancellation { unregister(callback) }
        }
    }

    private fun unregister(callback: ConnectivityManager.NetworkCallback) {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered (resumed and cancelled at the same time).
        }
    }
}

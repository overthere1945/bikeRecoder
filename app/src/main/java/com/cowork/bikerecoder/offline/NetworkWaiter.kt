package com.cowork.bikerecoder.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Waits for a Wi-Fi-like (not metered) connection, or tells whether any is up. Open so tests can substitute it. */
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

    /** Whether some network with validated internet is up now (metered or not). */
    open fun isOnline(): Boolean {
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Whether some network with validated internet is up, now and from now on (the feed's pictures try again when it comes
     * back); distinct values only, the first is [isOnline] at the time of collecting.
     */
    open fun online(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(isOnline())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { unregister(callback) }
    }.distinctUntilChanged()

    /** Whether the active network is cellular and not Wi-Fi (the video sheet's data notice, V1.3.1 spec §1.4). */
    open fun isCellular(): Boolean {
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun unregister(callback: ConnectivityManager.NetworkCallback) {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered (resumed and cancelled at the same time).
        }
    }
}

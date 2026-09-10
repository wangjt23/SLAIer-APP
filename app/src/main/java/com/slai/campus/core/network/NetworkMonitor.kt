package com.slai.campus.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Distinguishes "offline" from "the school server is unhappy".
 *
 * This matters because the two produce different user-facing messages and different retry policies:
 * offline keeps showing the cache and does not burn a WorkManager retry, whereas a server error
 * should back off.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    private val context: Context
) {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    val isOnline: Boolean
        get() {
            val manager = connectivityManager ?: return true
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    /** True when a network exists at all, even if it has not been validated (captive portal). */
    val hasNetwork: Boolean
        get() {
            val manager = connectivityManager ?: return true
            val network = manager.activeNetwork ?: return false
            return manager.getNetworkCapabilities(network) != null
        }

    fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
        connectivityManager?.registerDefaultNetworkCallback(callback)
    }

    fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    /** Convenience for `Network`-shaped callbacks that only care about "any network". */
    fun registerAvailabilityCallback(onAvailable: () -> Unit, onLost: () -> Unit) {
        registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
            override fun onLost(network: Network) = onLost()
        })
    }
}

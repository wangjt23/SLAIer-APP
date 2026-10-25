package com.slai.campus.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
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

    /**
     * A cold stream of changes to the default connection. A revision only wakes a protected-endpoint
     * probe: neither Wi-Fi nor Android's validation proves that the campus server can be reached.
     * Cancelling collection unregisters the callback, including when the app leaves the foreground.
     */
    @OptIn(FlowPreview::class)
    val networkChanges: Flow<Long>
        get() = callbackFlow {
            val manager = connectivityManager
            if (manager == null) {
                awaitClose { }
                return@callbackFlow
            }
            val initial = manager.activeNetwork
            val tracker = DefaultNetworkRevision(initial,
                initial?.let(manager::getNetworkCapabilities)?.connectionSignature())
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    tracker.available(network)?.let { trySend(it) }
                }

                override fun onLost(network: Network) {
                    tracker.lost(network)?.let { trySend(it) }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    tracker.capabilities(network, capabilities.connectionSignature())?.let { trySend(it) }
                }
            }
            val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
            if (!registered) close()
            awaitClose {
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }.debounce(500)

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

/** Ignore bandwidth, signal strength, and duplicate callback reports. */
private fun NetworkCapabilities.connectionSignature(): Int =
    (if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 1 else 0) or
        (if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) or
        (if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 4 else 0) or
        (if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 8 else 0) or
        (if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 16 else 0) or
        (if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) 32 else 0)

/** Pure transition tracker; a lost callback for the previous default must not erase the new one. */
internal class DefaultNetworkRevision<T>(
    private var current: T? = null,
    private var signature: Int? = null
) {
    private var revision = 0L

    @Synchronized fun available(network: T): Long? {
        if (current == network) return null
        current = network
        signature = null
        return ++revision
    }

    @Synchronized fun lost(network: T): Long? {
        if (current != network) return null
        current = null
        signature = null
        return ++revision
    }

    @Synchronized fun capabilities(network: T, signature: Int): Long? {
        if (current != network || this.signature == signature) return null
        this.signature = signature
        return ++revision
    }
}

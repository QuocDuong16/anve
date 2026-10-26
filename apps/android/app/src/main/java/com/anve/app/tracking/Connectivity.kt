package com.anve.app.tracking

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Connectivity is an operational input; acquisition never depends on it. */
interface ConnectivityMonitor {
    fun isOnline(): Boolean
    fun observe(): Flow<Boolean>
}

class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val active = manager?.activeNetwork ?: return false
        return manager.getNetworkCapabilities(active).isUsable()
    }

    override fun observe(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(networkCapabilities.isUsable())
            }
        }
        manager?.registerDefaultNetworkCallback(callback)
        trySend(isOnline())
        awaitClose { manager?.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

private fun NetworkCapabilities?.isUsable(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

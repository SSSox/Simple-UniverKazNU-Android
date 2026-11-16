package com.univerflow.kaznu.main

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network

/**
 * Reports, on the screen's main thread, that a default network became available while the watch is registered
 * (between onStart and onStop): a page that failed for want of it can be loaded again shortly.
 */
class NetworkWatch(private val activity: Activity, private val onNetwork: () -> Unit) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register() {
        val connectivity = activity.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activity.runOnUiThread { onNetwork() }
            }
        }
        try { connectivity.registerDefaultNetworkCallback(callback); this.callback = callback }
        catch (_: RuntimeException) { /* Too many callbacks or no permission: the backoff alone still retries. */ }
    }

    fun unregister() {
        val callback = callback ?: return
        this.callback = null
        try { activity.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        catch (_: RuntimeException) { }
    }
}

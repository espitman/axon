package com.axon.bridge.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkInfoProvider(
    private val context: Context? = null
) {
    fun localIpAddress(): String {
        return wifiIpAddress().ifBlank { interfaceIpAddress() }
    }

    private fun wifiIpAddress(): String {
        val appContext = context ?: return ""
        return runCatching {
            val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = connectivityManager.activeNetwork ?: return@runCatching ""
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return@runCatching ""
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@runCatching ""

            connectivityManager.getLinkProperties(activeNetwork)
                ?.linkAddresses
                ?.asSequence()
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { it.isUsableLanAddress() }
                ?.hostAddress
                .orEmpty()
        }.getOrDefault("")
    }

    private fun interfaceIpAddress(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { networkInterface ->
                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        networkInterface.name.startsWith("wlan", ignoreCase = true)
                }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isUsableLanAddress() }
                ?.hostAddress
                .orEmpty()
        }.getOrDefault("")
    }

    private fun Inet4Address.isUsableLanAddress(): Boolean {
        val host = hostAddress.orEmpty()
        return !isLoopbackAddress &&
            !isAnyLocalAddress &&
            !host.startsWith("169.254.")
    }
}

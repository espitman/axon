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

    fun localSubnetCandidates(): List<String> {
        val localIps = buildList {
            wifiIpAddress().takeIf { it.isNotBlank() }?.let(::add)
            addAll(interfaceIpAddresses())
        }.distinct()
        return localIps
            .flatMap { localIp ->
                val parts = localIp.split(".")
                if (parts.size != 4) {
                    emptyList()
                } else {
                    val prefix = parts.take(3).joinToString(".")
                    (1..254).map { "$prefix.$it" }.filterNot { it == localIp }
                }
            }
            .distinct()
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
        return interfaceIpAddresses().firstOrNull().orEmpty()
    }

    private fun interfaceIpAddresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { networkInterface ->
                    networkInterface.isUp &&
                        !networkInterface.isLoopback
                }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isUsableLanAddress() }
                .mapNotNull { it.hostAddress }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun Inet4Address.isUsableLanAddress(): Boolean {
        val host = hostAddress.orEmpty()
        return !isLoopbackAddress &&
            !isAnyLocalAddress &&
            !host.startsWith("169.254.")
    }
}

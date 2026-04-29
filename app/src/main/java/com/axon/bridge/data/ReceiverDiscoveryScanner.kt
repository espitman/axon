package com.axon.bridge.data

import android.content.Context
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.DiscoveredReceiver
import com.axon.bridge.domain.DiscoveryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class ReceiverDiscoveryScanner(context: Context) {
    private val settings = AxonSettings(context.applicationContext)
    private val networkInfoProvider = NetworkInfoProvider(context.applicationContext)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun scan(port: Int = BridgeTransport.DEFAULT_PORT): List<DiscoveredReceiver> {
        val savedReceiverIp = settings.serverIp.trim()
        val candidates = buildList {
            if (savedReceiverIp.isNotBlank()) {
                add(savedReceiverIp)
            }
            addAll(networkInfoProvider.localSubnetCandidates())
        }.distinct()
        if (candidates.isEmpty()) return emptyList()
        DiagnosticsLog.add("Receiver scan: ${networkInfoProvider.localIpAddress().ifBlank { "no local IP" }} -> ${candidates.size} hosts")

        val results = mutableListOf<DiscoveredReceiver>()
        withContext(Dispatchers.IO) {
            candidates.chunked(CONCURRENCY).forEach { chunk ->
                val found = coroutineScope {
                    chunk.map { ip ->
                        async { probe(ip, port) }
                    }.awaitAll().filterNotNull()
                }
                results += found
            }
        }

        DiagnosticsLog.add("Receiver scan finished: ${results.size} found")
        return results.distinctBy { it.ip }.sortedBy { it.ip }
    }

    private fun probe(ip: String, port: Int): DiscoveredReceiver? {
        return runCatching {
            val connection = (URL("http://$ip:$port${BridgeTransport.DISCOVERY_PATH}")
                .openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val response = json.decodeFromString<DiscoveryResponse>(text)
                if (response.app != "Axon" || response.role != BridgeRole.Sink) return null
                DiscoveredReceiver(
                    ip = ip,
                    deviceName = response.deviceName.ifBlank { "Receiver" },
                    port = response.port
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val CONCURRENCY = 64
        const val TIMEOUT_MS = 700
    }
}

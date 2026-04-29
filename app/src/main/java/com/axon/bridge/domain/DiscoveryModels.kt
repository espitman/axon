package com.axon.bridge.domain

data class DiscoveredReceiver(
    val ip: String,
    val deviceName: String,
    val port: Int
)

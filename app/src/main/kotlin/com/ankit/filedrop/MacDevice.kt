package com.ankit.filedrop

data class MacDevice(
    val deviceName: String,
    val ipAddress: String,
    val isResolving: Boolean = false
)

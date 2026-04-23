package com.ankit.filedrop

data class QuickDropDevice(
    val deviceName: String,
    val ipAddress: String,
    val isResolving: Boolean = false
)

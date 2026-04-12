package com.ankit.filedrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MacDropDiscovery(context: Context) {
    private val TAG = "MacDropDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryScope: CoroutineScope? = null

    private val _devices = MutableStateFlow<List<MacDevice>>(emptyList())
    val devices: StateFlow<List<MacDevice>> = _devices

    fun startDiscovery() {
        stopDiscovery()
        discoveryScope = CoroutineScope(Dispatchers.Main + Job())

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "🔍 Discovery started for type: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "📡 Service found: ${serviceInfo.serviceName}")
                if (isMacReceiver(serviceInfo.serviceName)) {
                    Log.d(TAG, "🎯 MacDrop candidate found, adding to list and resolving...")
                    
                    // Add to list as "resolving"
                    val newDevice = MacDevice(serviceInfo.serviceName, "", isResolving = true)
                    _devices.value = _devices.value + newDevice
                    
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.e(TAG, "📤 Service lost: ${serviceInfo.serviceName}")
                _devices.value = _devices.value.filter { it.deviceName != serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "⏹️ Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ Start discovery failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ Stop discovery failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        discoveryScope?.launch {
            delay(15000) // Slightly longer discovery
            if (discoveryListener != null) {
                Log.w(TAG, "🕒 Discovery cycle ended")
                // We don't stop discovery anymore, we let it run to maintain the live list
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ Resolve failed: $errorCode for ${serviceInfo.serviceName}")
                // Remove if resolution fails to keep list clean
                _devices.value = _devices.value.filter { it.deviceName != serviceInfo.serviceName }
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val ip = resolvedInfo.host.hostAddress ?: ""
                val name = resolvedInfo.serviceName
                Log.d(TAG, "✅ Service resolved: $name at $ip")

                // Update the matching device in the list
                _devices.value = _devices.value.map {
                    if (it.deviceName == name) {
                        it.copy(ipAddress = ip, isResolving = false)
                    } else it
                }
            }
        }
        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    fun stopDiscovery() {
        discoveryScope?.cancel()
        discoveryScope = null
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
    }

    private fun isMacReceiver(serviceName: String): Boolean {
        return serviceName.contains("MacDrop") && !serviceName.startsWith("MacDrop-Android-")
    }
}

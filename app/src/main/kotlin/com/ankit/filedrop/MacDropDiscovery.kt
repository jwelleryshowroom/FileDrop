package com.ankit.filedrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*

class MacDropDiscovery(context: Context) {
    private val TAG = "MacDropDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryScope: CoroutineScope? = null

    fun startDiscovery(
        onMacFound: (ip: String, deviceName: String) -> Unit,
        onTimeout: () -> Unit
    ) {
        stopDiscovery()
        discoveryScope = CoroutineScope(Dispatchers.Main + Job())

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "🔍 Discovery started for type: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "📡 Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.contains("MacDrop")) {
                    Log.d(TAG, "🎯 MacDrop candidate found, resolving: ${serviceInfo.serviceName}")
                    resolveService(serviceInfo, onMacFound)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.e(TAG, "📤 Service lost: ${serviceInfo.serviceName}")
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
            delay(10000)
            if (discoveryListener != null) {
                Log.w(TAG, "🕒 Discovery timeout reached")
                stopDiscovery()
                onTimeout()
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, onMacFound: (ip: String, deviceName: String) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ Resolve failed: $errorCode for ${serviceInfo.serviceName}")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "✅ Service resolved: ${resolvedInfo.host.hostAddress}")
                val ip = resolvedInfo.host.hostAddress ?: ""
                val deviceName = resolvedInfo.serviceName
                onMacFound(ip, deviceName)
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
}

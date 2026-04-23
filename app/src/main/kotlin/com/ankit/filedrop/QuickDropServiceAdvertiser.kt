package com.ankit.filedrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class QuickDropServiceAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("macdrop-lock").apply {
        setReferenceCounted(false)
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isStarted = false

    fun start(port: Int = 8000) {
        if (registrationListener != null) {
            Log.d(TAG, "⚠️ Advertiser already running, skipping start")
            return
        }

        Log.d(TAG, "🚀 START CALLED")

        if (!multicastLock.isHeld) {
            multicastLock.acquire()
            Log.d(TAG, "📡 Multicast lock acquired")
        }

        val serviceInfo = NsdServiceInfo().apply {
            // Sanitize: max 20 chars, alphanumeric only to avoid DNS limit errors
            val safeModel = Build.MODEL.take(20).replace("[^A-Za-z0-9]".toRegex(), "")
            serviceName = "QuickDrop-$safeModel"
            serviceType = "_http._tcp." // Reverted to trailing dot for Mac Bonjour strictness
            setPort(port)
            
            // 🔥 Android 12+ Explicit Network Binding (Hardens discovery on multi-interface devices)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNetwork: Network? = connectivityManager.activeNetwork
                    if (activeNetwork != null) {
                        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                            network = activeNetwork
                            Log.d(TAG, "📡 Service explicitly bonded to active Wi-Fi network")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Failed to bind service to specific network", e)
                }
            }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isStarted = true
                Log.d(TAG, "✅ mDNS REGISTERED: ${info.serviceName} on port ${info.port}")
                Log.d(TAG, "🔥 ACTUALLY VISIBLE ON NETWORK")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ mDNS REGISTRATION FAILED: $errorCode")
                isStarted = false
                registrationListener = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Unregistered mDNS service: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        try {
            Log.d(TAG, "📡 Advertising as: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start mDNS advertiser", e)
            releaseMulticastLock()
            registrationListener = null
        }
    }

    fun stop() {
        val listener = registrationListener
        registrationListener = null
        isStarted = false

        if (listener != null) {
            try {
                nsdManager.unregisterService(listener)
                // Wait for NSD state to actually flush
                try {
                    Thread.sleep(300)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Unable to stop mDNS advertiser", e)
            }
        }

        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        if (multicastLock.isHeld) {
            multicastLock.release()
        }
    }

    private companion object {
        private const val TAG = "QuickDropAdvertiser"
    }
}

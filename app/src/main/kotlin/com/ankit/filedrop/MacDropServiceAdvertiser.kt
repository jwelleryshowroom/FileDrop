package com.ankit.filedrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class MacDropServiceAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("macdrop-lock").apply {
        setReferenceCounted(false)
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isStarted = false

    fun start(port: Int = 8000) {
        if (isStarted) return

        if (!multicastLock.isHeld) {
            multicastLock.acquire()
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "MacDrop-Android-${Build.MODEL.replace("[^A-Za-z0-9-]".toRegex(), "-")}"
            serviceType = "_http._tcp."
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Registered mDNS service: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS registration failed: $errorCode")
                stop()
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Unregistered mDNS service: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            isStarted = true
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
        private const val TAG = "MacDropAdvertiser"
    }
}

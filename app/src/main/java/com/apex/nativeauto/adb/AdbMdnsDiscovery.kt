package com.apex.nativeauto.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class AdbMdnsDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val tag = "Apex_mDNS"

    interface DiscoveryCallback {
        fun onPortDiscovered(port: Int, isPairingService: Boolean)
    }

    fun discoverServices(callback: DiscoveryCallback) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(tag, "بدأ البحث التلقائي عن منافذ التصحيح...")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_adb-tls-pairing._tcp")) {
                    resolve(service) { port -> callback.onPortDiscovered(port, true) }
                } else if (service.serviceType.contains("_adb-tls-connect._tcp")) {
                    resolve(service) { port -> callback.onPortDiscovered(port, false) }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(tag, "فشل تشغيل mDNS: ${e.message}")
        }
    }

    private fun resolve(serviceInfo: NsdServiceInfo, onResolved: (Int) -> Unit) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "فشل تحديد المنفذ: $errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo) {
                Log.i(tag, "تم العثور على المنفذ: ${service.port}")
                onResolved(service.port)
            }
        })
    }
}

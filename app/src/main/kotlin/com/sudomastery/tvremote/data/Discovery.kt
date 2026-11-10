package com.sudomastery.tvremote.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

data class FoundTv(val name: String, val host: String)

/** Finds TVs advertising the remote service (`_androidtvremote2._tcp`) on the local network. */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    fun scan(): Flow<List<FoundTv>> = callbackFlow {
        val lock = Any()
        val found = LinkedHashMap<String, FoundTv>()
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // Older Android versions allow one resolve at a time, so resolve sequentially.
        fun resolveNext() {
            val info = synchronized(lock) {
                if (resolving) return
                pending.removeFirstOrNull()?.also { resolving = true } ?: return
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.ipv4()
                    synchronized(lock) {
                        if (host != null) found[serviceInfo.serviceName] = FoundTv(serviceInfo.serviceName, host)
                        resolving = false
                        trySend(found.values.toList())
                    }
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { pending.addLast(serviceInfo) }
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    found.remove(serviceInfo.serviceName)
                    trySend(found.values.toList())
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private fun NsdServiceInfo.ipv4(): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) hostAddresses else listOfNotNull(@Suppress("DEPRECATION") host)
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}

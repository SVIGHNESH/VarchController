package dev.varch.controller.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

data class Found(val name: String, val endpoint: Endpoint)

/** Finds varchd instances advertised over mDNS. All callbacks arrive on the main thread. */
class Discovery(context: Context, private val onChange: (List<Found>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) = post { if (listener === this) listener = null }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) = post {
                queue.addLast(info)
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) = post {
                if (found.remove(info.serviceName) != null) publish()
            }

            // Results that arrive after stop() must not repopulate the list.
            private fun post(block: () -> Unit) {
                main.post { if (listener === this) block() }
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(l) }
        queue.clear()
        if (found.isNotEmpty()) {
            found.clear()
            publish()
        }
    }

    // NsdManager only resolves one service at a time on older releases.
    private fun resolveNext() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        val owner = listener
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = done(null)
            override fun onServiceResolved(info: NsdServiceInfo) = done(info)

            private fun done(resolved: NsdServiceInfo?) {
                main.post {
                    resolving = false
                    if (owner === listener) {
                        val host = resolved?.let(::address)
                        if (resolved != null && host != null) {
                            found[resolved.serviceName] = Found(resolved.serviceName, Endpoint(host, resolved.port))
                            publish()
                        }
                        resolveNext()
                    }
                }
            }
        })
    }

    private fun address(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
    }

    private fun publish() = onChange(found.values.toList())

    private companion object {
        const val SERVICE_TYPE = "_varch._tcp"
    }
}

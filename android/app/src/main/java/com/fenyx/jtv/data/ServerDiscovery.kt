package com.fenyx.jtv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.fenyx.jtv.sync.LanAddress
import com.fenyx.jtv.sync.LanSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

/**
 * Finds JTV servers on the home network: probes `GET http://<ip>:29180/jtv-server` on every host of this
 * device's /24 and, in parallel, browses NSD for `_jtv-server._tcp`. Whole scan ends after ~4 s.
 */
object ServerDiscovery {
    const val PROBE_PORT = 29180
    private const val SERVICE_TYPE = "_jtv-server._tcp"

    data class Server(val name: String, val url: String, val lite: Boolean)

    /** Server URLs seen by the last scan in this process; "jtv" code-only mode tries these first. */
    @Volatile var lastFound: List<String> = emptyList()
        private set

    /** A probe reply (or NSD record) as a server entry, or null when it isn't a JTV server. */
    fun toServer(ip: String, app: String, kind: String, name: String, port: Int, https: Boolean): Server? {
        if (app != "jtv-server" || port !in 1..65535) return null
        return Server(name.trim().ifBlank { ip }, "${if (https) "https" else "http"}://$ip:$port", kind == "lite")
    }

    /** Every other host of the /24 that holds [ip] (a bigger subnet is still only scanned on its own /24). */
    fun hostsIn24(ip: Inet4Address): List<String> {
        val b = ip.address.map { it.toInt() and 0xFF }
        return (1..254).filter { it != b[3] }.map { "${b[0]}.${b[1]}.${b[2]}.$it" }
    }

    fun merge(list: List<Server>, s: Server): List<Server> = if (list.any { it.url == s.url }) list else list + s

    /** The servers found so far, re-emitted each time a new one turns up; completes when the scan ends. */
    fun scan(context: Context): Flow<List<Server>> = channelFlow {
        val app = context.applicationContext
        val lock = Mutex()
        var found = emptyList<Server>()
        suspend fun add(s: Server?) {
            s ?: return
            lock.withLock {
                val next = merge(found, s)
                if (next !== found) { found = next; lastFound = next.map { it.url }; send(next) }
            }
        }
        withTimeoutOrNull(4_000) {
            launch { browseNsd(app) { add(it) } }
            val gate = Semaphore(48)
            ownAddresses(app).flatMap(::hostsIn24).distinct().forEach { h -> launch { gate.withPermit { add(probe(h)) } } }
        }
    }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION") // allNetworks: we want Wi-Fi AND Ethernet, not just the default network
    private fun ownAddresses(ctx: Context): List<Inet4Address> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }.flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .mapNotNull { it.address as? Inet4Address }
            .filter { LanAddress.isPrivate(it) && !it.isLoopbackAddress }
    }

    private fun probe(ip: String): Server? = runCatching {
        val c = URL("http://$ip:$PROBE_PORT/jtv-server").openConnection() as HttpURLConnection
        c.connectTimeout = 350
        c.readTimeout = 800
        try {
            if (c.responseCode != 200) return null
            // Read at most 8 KB: anything on the LAN can answer on this port.
            val body = c.inputStream.use { s ->
                val b = ByteArray(8192); var n = 0
                while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
                String(b, 0, n, Charsets.UTF_8)
            }
            val o = JSONObject(body)
            toServer(ip, o.optString("app"), o.optString("kind"), o.optString("name"), o.optInt("port"), o.optBoolean("https"))
        } finally { c.disconnect() }
    }.getOrNull()

    /** Browses `_jtv-server._tcp` for 3 s. A found host is probed for its full info, else its TXT record is used. */
    private suspend fun browseNsd(ctx: Context, onFound: suspend (Server?) -> Unit) = coroutineScope {
        val mgr = ctx.getSystemService(NsdManager::class.java) ?: return@coroutineScope
        val mcast = runCatching {
            ctx.getSystemService(WifiManager::class.java)?.createMulticastLock("jtv-server-scan")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { queue.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) { queue.trySend(si) }
            override fun onServiceLost(si: NsdServiceInfo) {}
        }
        try {
            runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { queue.close() }
            withTimeoutOrNull(3_000) {
                for (si in queue) { // one resolve at a time: older Android fails parallel resolves
                    val r = withTimeoutOrNull(2_000) { LanSync.resolve(mgr, si) } ?: continue
                    onFound(fromNsd(r))
                }
            }
        } finally {
            runCatching { mgr.stopServiceDiscovery(listener) }
            runCatching { mcast?.release() }
        }
    }

    @Suppress("DEPRECATION")
    private fun fromNsd(si: NsdServiceInfo): Server? {
        val host = (if (Build.VERSION.SDK_INT >= 34) si.hostAddresses.firstOrNull { it is Inet4Address } else si.host)
            as? Inet4Address ?: return null
        if (!LanAddress.isPrivate(host)) return null
        val ip = host.hostAddress ?: return null
        val txt = si.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        return probe(ip) ?: toServer(ip, "jtv-server", txt["kind"].orEmpty(), txt["name"].orEmpty(), si.port, false)
    }
}

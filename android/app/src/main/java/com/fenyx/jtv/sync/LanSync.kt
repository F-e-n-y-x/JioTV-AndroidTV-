package com.fenyx.jtv.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.fenyx.jtv.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Device-to-device sync over the home Wi-Fi, no server needed.
 *
 * While the app is in the foreground every JTV device (TV, phone, tablet) runs a [MiniHttpServer] on an
 * ephemeral port and advertises it with NSD as `_jtv._tcp` (TXT: id, name, kind, ver). Devices pair once
 * with a 4-digit code shown on the other screen and then share a random 128-bit key; every later call
 * carries `Authorization: Bearer <key>`.
 *
 * Protocol (JSON bodies):
 *  - `GET  /info`            -> {id, name, kind, ver}                      (no key)
 *  - `POST /pair/start`      {id, name, kind} -> {requestId}               (no key; other screen shows code)
 *  - `POST /pair/confirm`    {requestId, id, code} -> {key, id, name, kind} | 403 {error, left} | 410
 *  - `POST /pair/cancel`     {requestId}                                   (no key)
 *  - `GET  /favorites`       -> {ids, updatedAt}
 *  - `PUT  /favorites`       {ids, updatedAt} -> {applied, ids, updatedAt} (newer one wins)
 *  - `POST /play`            {channelId}  (the receiving device opens the player)
 *  - `POST /unpair`          (the caller is forgotten here too)
 */
@SuppressLint("StaticFieldLeak") // holds the Application context only
object LanSync {

    private const val TAG = "LanSync"
    const val SERVICE_TYPE = "_jtv._tcp"
    private const val PUSH_DEBOUNCE_MS = 2_000L

    /** A JTV device seen on the network right now. */
    data class Found(val id: String, val name: String, val kind: String, val version: String, val host: InetAddress, val port: Int)

    /** For the UI: a paired device, or one we could pair with. */
    data class Device(val id: String, val name: String, val kind: String, val paired: Boolean, val reachable: Boolean, val lastSync: Long)

    /** The pairing dialog on THIS device (someone asked to pair with us). */
    data class PairPrompt(val requestId: String, val fromName: String, val code: String, val paired: Boolean = false)

    sealed interface PairResult {
        data class Paired(val peerId: String, val peerName: String) : PairResult
        data class WrongCode(val attemptsLeft: Int) : PairResult
        data object Cancelled : PairResult
        data object Failed : PairResult
    }

    private lateinit var app: Context
    private lateinit var store: PeerStore
    private lateinit var settings: SettingsManager
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── State the UI reads ──
    private val _found = MutableStateFlow<Map<String, Found>>(emptyMap())
    private val _pairPrompt = MutableStateFlow<PairPrompt?>(null)
    val pairPrompt: StateFlow<PairPrompt?> = _pairPrompt.asStateFlow()
    private val _playRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Channel ids a paired device asked us to play. Navigation turns these into a Player entry. */
    val playRequests: SharedFlow<String> = _playRequests.asSharedFlow()
    private val _status = MutableStateFlow<String?>(null)
    /** Last "Sync now" result, in plain words. */
    val status: StateFlow<String?> = _status.asStateFlow()

    lateinit var devices: StateFlow<List<Device>> private set
    lateinit var deviceName: StateFlow<String> private set
    lateinit var autoSync: StateFlow<Boolean> private set

    /** Paired TVs that are on the network now ("Play on <TV>"). */
    lateinit var playTargets: StateFlow<List<Device>> private set

    private var selfId = ""
    private var selfName = Build.MODEL ?: "Device"
    private lateinit var selfKind: String
    private var version = ""

    // ── Running state (foreground only) ──
    private var session: CoroutineScope? = null
    private var server: MiniHttpServer? = null
    private var port = 0
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serviceToId = HashMap<String, String>()
    private val pulledThisSession = HashSet<String>()
    @Volatile private var pending: PairingSession? = null
    private var promptJob: Job? = null
    @Volatile private var lastPairStart = 0L

    /** Call once from Application.onCreate (main thread). */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = PeerStore(app)
        settings = SettingsManager(app)
        selfKind = when {
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> "tv"
            app.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
        version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""

        val defaultName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "JTV device"
        deviceName = store.nameFlow.map { it ?: defaultName }.stateIn(appScope, SharingStarted.Eagerly, defaultName)
        autoSync = store.autoSyncFlow.stateIn(appScope, SharingStarted.Eagerly, true)
        devices = combine(store.peersFlow, _found) { peers, found ->
            val paired = peers.map { p ->
                val f = found[p.id]
                Device(p.id, f?.name ?: p.name, f?.kind ?: p.kind, paired = true, reachable = f != null, lastSync = p.lastSync)
            }
            val ids = peers.map { it.id }.toSet()
            paired + found.values.filter { it.id !in ids }.sortedBy { it.name }
                .map { Device(it.id, it.name, it.kind, paired = false, reachable = true, lastSync = 0L) }
        }.stateIn(appScope, SharingStarted.Eagerly, emptyList())
        playTargets = devices.map { list -> list.filter { it.paired && it.reachable && it.kind == "tv" } }
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = start()
            override fun onStop(owner: LifecycleOwner) = stop()
        })
    }

    // ───────────────────────── Lifecycle ─────────────────────────

    @OptIn(FlowPreview::class)
    private fun start() {
        if (session != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = scope
        scope.launch {
            try {
                selfId = store.deviceId()
                selfName = deviceName.value
                val srv = MiniHttpServer(::route)
                port = srv.start(scope)
                server = srv
                Log.i(TAG, "listening on port $port as $selfKind")
                withContext(Dispatchers.Main) { register(); discover() }
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.javaClass.simpleName}")
            }
        }
        // Local favourites changed -> push to every paired device in reach (debounced).
        scope.launch {
            settings.favoritesSnapshotFlow.distinctUntilChanged().drop(1).debounce(PUSH_DEBOUNCE_MS).collect { snap ->
                if (autoSync.value) pushToAll(snap)
            }
        }
        // Re-advertise when the user renames this device.
        scope.launch {
            deviceName.drop(1).distinctUntilChanged().collect { n ->
                selfName = n
                withContext(Dispatchers.Main) { unregister(); register() }
            }
        }
    }

    private fun stop() {
        val scope = session ?: return
        session = null
        unregister()
        stopDiscovery()
        server?.stop()
        server = null
        scope.cancel()
        _found.value = emptyMap()
        synchronized(serviceToId) { serviceToId.clear() }
        synchronized(pulledThisSession) { pulledThisSession.clear() }
        pending = null
        _pairPrompt.value = null
    }

    // ───────────────────────── NSD ─────────────────────────

    private fun nsd(): NsdManager? = nsd ?: (app.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.also { nsd = it }

    private fun ownServiceName() = "JTV-" + selfId.take(12)

    private fun register() {
        val mgr = nsd() ?: return
        if (port == 0 || regListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = ownServiceName()
            serviceType = SERVICE_TYPE
            port = this@LanSync.port
            setAttribute("id", selfId)
            setAttribute("name", selfName.take(60))
            setAttribute("kind", selfKind)
            setAttribute("ver", version.take(20))
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {}
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { Log.w(TAG, "register failed $code"); regListener = null }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        regListener = l
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { regListener = null }
    }

    private fun unregister() {
        val l = regListener ?: return
        regListener = null
        runCatching { nsd()?.unregisterService(l) }
    }

    private fun discover() {
        val mgr = nsd() ?: return
        if (discListener != null) return
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = runCatching {
            wifi?.createMulticastLock("jtv-sync")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        val scope = session ?: return
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        // Resolve one service at a time: older Android versions fail parallel resolves.
        scope.launch {
            for (si in resolveQueue) {
                val r = withTimeoutOrNull(6_000) { resolve(mgr, si) } ?: continue
                onResolved(si.serviceName, r)
            }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                if (si.serviceName == ownServiceName()) return
                resolveQueue.trySend(si)
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                val id = synchronized(serviceToId) { serviceToId.remove(si.serviceName) } ?: return
                _found.update { it - id }
                synchronized(pulledThisSession) { pulledThisSession.remove(id) }
            }
        }
        discListener = l
        runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { discListener = null }
    }

    private fun stopDiscovery() {
        discListener?.let { l -> runCatching { nsd()?.stopServiceDiscovery(l) } }
        discListener = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(mgr: NsdManager, si: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val ok = runCatching {
                mgr.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(s: NsdServiceInfo) { if (cont.isActive) cont.resume(s) }
                })
            }.isSuccess
            if (!ok && cont.isActive) cont.resume(null)
        }

    @Suppress("DEPRECATION")
    private fun onResolved(serviceName: String, si: NsdServiceInfo) {
        val attrs = si.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        val id = attrs["id"]?.takeIf { it.isNotBlank() } ?: return
        if (id == selfId) return
        val host = if (Build.VERSION.SDK_INT >= 34) {
            si.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: si.hostAddresses.firstOrNull()
        } else si.host
        host ?: return
        if (!LanAddress.isPrivate(host)) return
        val f = Found(id, attrs["name"].orEmpty().ifBlank { "JTV device" }, attrs["kind"].orEmpty().ifBlank { "phone" },
            attrs["ver"].orEmpty(), host, si.port)
        synchronized(serviceToId) { serviceToId[serviceName] = id }
        _found.update { it + (id to f) }
        // A paired device came into reach: sync once per foreground session (app start / resume).
        val first = synchronized(pulledThisSession) { pulledThisSession.add(id) }
        if (first) session?.launch {
            store.peers().firstOrNull { it.id == id }?.let { peer ->
                store.rename(peer.id, f.name, f.kind)
                if (autoSync.value) syncWith(peer, f)
            }
        }
    }

    // ───────────────────────── Server side ─────────────────────────

    private fun json(status: Int, o: JSONObject) = MiniHttpServer.Response(status, o.toString())
    private fun err(status: Int, msg: String) = json(status, JSONObject().put("error", msg))

    private fun info() = JSONObject().put("id", selfId).put("name", selfName).put("kind", selfKind).put("ver", version)

    private suspend fun route(req: MiniHttpServer.Request): MiniHttpServer.Response {
        val body = runCatching { if (req.body.isBlank()) JSONObject() else JSONObject(req.body) }.getOrNull()
            ?: return err(400, "bad json")
        when (req.method to req.path) {
            "GET" to "/info" -> return json(200, info())
            "POST" to "/pair/start" -> return pairStart(body)
            "POST" to "/pair/confirm" -> return pairConfirm(body)
            "POST" to "/pair/cancel" -> {
                val p = pending
                if (p != null && p.requestId == body.optString("requestId")) { p.cancel(); pending = null; _pairPrompt.value = null }
                return json(200, JSONObject())
            }
        }
        // Everything else needs a paired device's key.
        val key = SyncCrypto.bearer(req.header("authorization")) ?: return err(401, "not paired")
        val peer = store.peers().firstOrNull { SyncCrypto.sameKey(it.key, key) } ?: return err(401, "not paired")
        return when (req.method to req.path) {
            "GET" to "/favorites" -> json(200, snapshotJson(settings.favoritesSnapshotFlow.first()))
            "PUT" to "/favorites" -> {
                val remote = parseSnapshot(body) ?: return err(400, "bad favourites")
                val applied = autoSync.value && settings.applySyncedFavorites(remote.ids, remote.updatedAt)
                if (applied) store.markSynced(peer.id, System.currentTimeMillis())
                json(200, snapshotJson(settings.favoritesSnapshotFlow.first()).put("applied", applied))
            }
            "POST" to "/play" -> {
                val ch = body.optString("channelId").takeIf { it.isNotBlank() && it.length <= 64 } ?: return err(400, "no channel")
                _playRequests.tryEmit(ch)
                json(200, JSONObject().put("ok", true))
            }
            "POST" to "/unpair" -> { store.remove(peer.id); json(200, JSONObject()) }
            else -> err(404, "not found")
        }
    }

    private fun pairStart(body: JSONObject): MiniHttpServer.Response {
        val fromId = body.optString("id").take(64)
        val fromName = body.optString("name").take(60).ifBlank { "a device" }
        if (fromId.isBlank()) return err(400, "no id")
        val now = System.currentTimeMillis()
        // One new pairing request every few seconds at most, so nobody can flood the screen with dialogs.
        if (now - lastPairStart < 3_000) return err(429, "slow down")
        lastPairStart = now
        val cur = pending
        if (cur != null && !cur.cancelled && !cur.isExpired(now) && cur.fromId != fromId) return err(409, "busy")
        val s = PairingSession(SyncCrypto.randomHex128(), fromId, fromName, body.optString("kind").take(10), SyncCrypto.pairCode(), now)
        pending = s
        _pairPrompt.value = PairPrompt(s.requestId, fromName, s.code)
        promptJob?.cancel()
        promptJob = session?.launch {
            delay(PairingSession.TTL_MS)
            if (pending === s) { pending = null; _pairPrompt.value = null }
        }
        return json(200, JSONObject().put("requestId", s.requestId))
    }

    private suspend fun pairConfirm(body: JSONObject): MiniHttpServer.Response {
        val s = pending?.takeIf { it.requestId == body.optString("requestId") && it.fromId == body.optString("id") }
            ?: return err(410, "cancelled")
        return when (val r = s.check(body.optString("code"), System.currentTimeMillis())) {
            is PairingSession.Check.Wrong -> json(403, JSONObject().put("error", "wrong code").put("left", r.attemptsLeft))
            PairingSession.Check.Cancelled, PairingSession.Check.Expired -> {
                pending = null; _pairPrompt.value = null
                err(410, "cancelled")
            }
            PairingSession.Check.Ok -> {
                val key = SyncCrypto.randomHex128()
                store.upsert(Peer(s.fromId, s.fromName, s.fromKind.ifBlank { "phone" }, key, 0L))
                pending = null
                _pairPrompt.value = PairPrompt(s.requestId, s.fromName, s.code, paired = true)
                promptJob?.cancel()
                promptJob = session?.launch { delay(5_000); if (_pairPrompt.value?.requestId == s.requestId) _pairPrompt.value = null }
                json(200, info().put("key", key))
            }
        }
    }

    /** The user pressed Cancel on the pairing dialog (or OK on "Paired"). */
    fun dismissPairPrompt() {
        pending?.cancel()
        pending = null
        _pairPrompt.value = null
    }

    // ───────────────────────── Client side ─────────────────────────

    private fun found(id: String) = _found.value[id]

    /** Asks [deviceId] to show a code. Returns the request id, or null if it can't be reached / is busy. */
    suspend fun requestPair(deviceId: String): String? {
        val f = found(deviceId) ?: return null
        val r = SyncClient.call(f.host, f.port, "POST", "/pair/start", body = JSONObject()
            .put("id", selfId).put("name", selfName).put("kind", selfKind))
        return r.json?.optString("requestId")?.takeIf { r.ok && it.isNotBlank() }
    }

    suspend fun confirmPair(deviceId: String, requestId: String, code: String): PairResult {
        val f = found(deviceId) ?: return PairResult.Failed
        val r = SyncClient.call(f.host, f.port, "POST", "/pair/confirm", body = JSONObject()
            .put("requestId", requestId).put("id", selfId).put("code", code))
        return when {
            r.ok && r.json != null -> {
                val key = r.json.optString("key")
                val id = r.json.optString("id")
                if (key.length != 32 || id != deviceId) return PairResult.Failed
                val name = r.json.optString("name").ifBlank { f.name }
                store.upsert(Peer(id, name, r.json.optString("kind").ifBlank { f.kind }, key, 0L))
                synchronized(pulledThisSession) { pulledThisSession.add(id) }
                PairResult.Paired(id, name)
            }
            r.status == 403 -> PairResult.WrongCode(r.json?.optInt("left", 0) ?: 0)
            r.status == 410 -> PairResult.Cancelled
            else -> PairResult.Failed
        }
    }

    fun cancelPair(deviceId: String, requestId: String) {
        val f = found(deviceId) ?: return
        appScope.launch { SyncClient.call(f.host, f.port, "POST", "/pair/cancel", body = JSONObject().put("requestId", requestId)) }
    }

    /**
     * First sync after pairing ("Combine both lists?"): this device's order first, then the other
     * device's extras, stamped newer than both so it wins everywhere.
     */
    suspend fun combineFavorites(peerId: String): Boolean {
        val peer = store.peers().firstOrNull { it.id == peerId } ?: return false
        val f = found(peerId) ?: return false
        val r = SyncClient.call(f.host, f.port, "GET", "/favorites", peer.key)
        val theirs = r.json?.let(::parseSnapshot)?.takeIf { r.ok } ?: return false
        val mine = settings.favoritesSnapshotFlow.first()
        val merged = FavoritesSnapshot(
            FavoritesSync.union(mine.ids, theirs.ids),
            FavoritesSync.nextStamp(maxOf(mine.updatedAt, theirs.updatedAt), System.currentTimeMillis()),
        )
        settings.applySyncedFavorites(merged.ids, merged.updatedAt)
        val put = SyncClient.call(f.host, f.port, "PUT", "/favorites", peer.key, snapshotJson(merged))
        if (put.ok) store.markSynced(peerId, System.currentTimeMillis())
        return put.ok
    }

    /** Two-way: whichever side changed last wins. Unreachable devices are skipped silently. */
    private suspend fun syncWith(peer: Peer, f: Found): Boolean {
        val r = SyncClient.call(f.host, f.port, "GET", "/favorites", peer.key)
        val theirs = r.json?.let(::parseSnapshot)?.takeIf { r.ok } ?: return false
        val mine = settings.favoritesSnapshotFlow.first()
        when {
            FavoritesSync.remoteWins(mine, theirs) -> settings.applySyncedFavorites(theirs.ids, theirs.updatedAt)
            FavoritesSync.remoteWins(theirs, mine) -> if (!push(peer, f, mine)) return false
        }
        store.markSynced(peer.id, System.currentTimeMillis())
        return true
    }

    private suspend fun push(peer: Peer, f: Found, snap: FavoritesSnapshot): Boolean {
        val r = SyncClient.call(f.host, f.port, "PUT", "/favorites", peer.key, snapshotJson(snap))
        if (!r.ok) return false
        // They had something newer (or auto sync is off there): take theirs if it is newer.
        r.json?.let(::parseSnapshot)?.let { theirs ->
            if (FavoritesSync.remoteWins(snap, theirs)) settings.applySyncedFavorites(theirs.ids, theirs.updatedAt)
        }
        if (r.json?.optBoolean("applied") == true) store.markSynced(peer.id, System.currentTimeMillis())
        return true
    }

    private suspend fun pushToAll(snap: FavoritesSnapshot) {
        val found = _found.value
        for (peer in store.peers()) {
            val f = found[peer.id] ?: continue
            push(peer, f, snap)
        }
    }

    /** "Sync now": two-way sync with every paired device in reach. Sets [status]. */
    fun syncNow() {
        appScope.launch {
            _status.value = "Syncing…"
            val found = _found.value
            val peers = store.peers()
            val inReach = peers.filter { found[it.id] != null }
            val ok = inReach.count { syncWith(it, found.getValue(it.id)) }
            _status.value = when {
                peers.isEmpty() -> "No paired devices"
                inReach.isEmpty() -> "No paired device in reach"
                ok == 0 -> "Could not sync, try again"
                else -> "Synced"
            }
            delay(4_000)
            _status.value = null
        }
    }

    /** "Play on <TV>". Shows a short message either way. */
    fun playOn(deviceId: String, channelId: String) {
        appScope.launch {
            val peer = store.peers().firstOrNull { it.id == deviceId }
            val f = found(deviceId)
            val name = f?.name ?: peer?.name ?: "the TV"
            val ok = peer != null && f != null &&
                SyncClient.call(f.host, f.port, "POST", "/play", peer.key, JSONObject().put("channelId", channelId)).ok
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (ok) "Playing on $name" else "Could not reach $name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Forget a paired device here, and tell it to forget us too if it is in reach. */
    fun forget(deviceId: String) {
        appScope.launch {
            val peer = store.peers().firstOrNull { it.id == deviceId } ?: return@launch
            found(deviceId)?.let { f -> SyncClient.call(f.host, f.port, "POST", "/unpair", peer.key) }
            store.remove(deviceId)
        }
    }

    fun setDeviceName(name: String) { appScope.launch { store.setName(name) } }
    fun setAutoSync(on: Boolean) { appScope.launch { store.setAutoSync(on) } }

    // ───────────────────────── JSON ─────────────────────────

    private fun snapshotJson(s: FavoritesSnapshot) =
        JSONObject().put("ids", JSONArray(s.ids)).put("updatedAt", s.updatedAt)

    private fun parseSnapshot(o: JSONObject): FavoritesSnapshot? {
        val arr = o.optJSONArray("ids") ?: return null
        if (arr.length() > 5_000) return null
        val ids = (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() && it.length <= 64 } }
        return FavoritesSnapshot(ids, o.optLong("updatedAt", 0L))
    }
}

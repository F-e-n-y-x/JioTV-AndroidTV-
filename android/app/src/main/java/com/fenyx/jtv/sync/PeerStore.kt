package com.fenyx.jtv.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fenyx.jtv.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A device this one is paired with. [key] is the shared 128-bit secret (hex); never logged. */
data class Peer(val id: String, val name: String, val kind: String, val key: String, val lastSync: Long) {
    override fun toString() = "Peer(id=$id, name=$name, kind=$kind)"
}

/** LAN-sync state in the app's DataStore: this device's id and name, paired peers, the auto-sync switch. */
class PeerStore(private val context: Context) {

    private companion object {
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val DEVICE_NAME = stringPreferencesKey("sync_device_name")
        val PEERS = stringPreferencesKey("sync_peers")
        val AUTO_SYNC = booleanPreferencesKey("sync_auto_favorites")
    }

    val peersFlow: Flow<List<Peer>> = context.dataStore.data.map { decode(it[PEERS]) }
    val nameFlow: Flow<String?> = context.dataStore.data.map { it[DEVICE_NAME] }
    val autoSyncFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_SYNC] ?: true }

    /** A random id made once per install. */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        context.dataStore.edit { p ->
            id = p[DEVICE_ID] ?: SyncCrypto.randomHex128().also { p[DEVICE_ID] = it }
        }
        return id
    }

    suspend fun peers(): List<Peer> = peersFlow.first()

    suspend fun setName(name: String) {
        context.dataStore.edit { p ->
            val n = name.trim().take(40)
            if (n.isEmpty()) p.remove(DEVICE_NAME) else p[DEVICE_NAME] = n
        }
    }

    suspend fun setAutoSync(on: Boolean) {
        context.dataStore.edit { it[AUTO_SYNC] = on }
    }

    /** Adds or replaces the peer with the same id. */
    suspend fun upsert(peer: Peer) = editPeers { list -> list.filter { it.id != peer.id } + peer }

    suspend fun remove(id: String) = editPeers { list -> list.filter { it.id != id } }

    suspend fun markSynced(id: String, at: Long) =
        editPeers { list -> list.map { if (it.id == id) it.copy(lastSync = at) else it } }

    suspend fun rename(id: String, name: String, kind: String) =
        editPeers { list -> list.map { if (it.id == id && (it.name != name || it.kind != kind)) it.copy(name = name, kind = kind) else it } }

    private suspend fun editPeers(change: (List<Peer>) -> List<Peer>) {
        context.dataStore.edit { p ->
            val before = decode(p[PEERS])
            val after = change(before)
            if (after != before) p[PEERS] = encode(after)
        }
    }

    private fun encode(list: List<Peer>): String = JSONArray().apply {
        list.forEach { peer ->
            put(JSONObject().put("id", peer.id).put("name", peer.name).put("kind", peer.kind)
                .put("key", peer.key).put("lastSync", peer.lastSync))
        }
    }.toString()

    private fun decode(json: String?): List<Peer> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id"); val key = o.optString("key")
                if (id.isEmpty() || key.isEmpty()) null
                else Peer(id, o.optString("name", "Device"), o.optString("kind", "phone"), key, o.optLong("lastSync", 0L))
            }
        }.getOrDefault(emptyList())
    }
}

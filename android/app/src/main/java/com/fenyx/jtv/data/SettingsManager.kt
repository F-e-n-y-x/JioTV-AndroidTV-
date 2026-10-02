package com.fenyx.jtv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        private val DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
        private val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        private val HARDWARE_DECODER = booleanPreferencesKey("hardware_decoder")
        private val TUNNELING = booleanPreferencesKey("tunneling_enabled")
        private val PLAYBACK_BUFFER_SEC = intPreferencesKey("playback_buffer_sec")
        private val VOICE_BOOST = intPreferencesKey("voice_boost")          // 0=off,1=low,2=medium,3=high,4=max
        private val AUDIO_NORMALIZE = booleanPreferencesKey("audio_normalize")
        private val LAST_SELECTED_CATEGORY = stringPreferencesKey("last_selected_category")
        private val LAST_UPDATE_CHECK = stringPreferencesKey("last_update_check_timestamp")
        private val PLAYER_RESIZE_MODE = intPreferencesKey("player_resize_mode")
        
        // Collapse per-language duplicate channels (e.g. "Star Sports 1 Hindi/Tamil/Telugu") into one.
        private val GROUP_LANGUAGE_VARIANTS = booleanPreferencesKey("group_language_variants")

        private val EPG_MODE = booleanPreferencesKey("epg_mode")
        private val EPG_URL = stringPreferencesKey("epg_url")
        private val FAVORITE_CHANNELS = stringPreferencesKey("favorite_channels")
        // When the favourites list last changed (wall clock ms). LAN sync: the newer list wins.
        private val FAVORITES_UPDATED_AT = longPreferencesKey("favorites_updated_at")
        
        private val AUTH_SSO_TOKEN = stringPreferencesKey("auth_sso_token")
        private val AUTH_AUTH_TOKEN = stringPreferencesKey("auth_auth_token")
        private val AUTH_REFRESH_TOKEN = stringPreferencesKey("auth_refresh_token")
        // Phone-mode sign-in number: lets JioApiClient rebuild a session Jio has ended without a new OTP.
        private val AUTH_MOBILE = stringPreferencesKey("auth_mobile")
        private val AUTH_CRMID = stringPreferencesKey("auth_crmid")
        private val AUTH_UNIQUE_ID = stringPreferencesKey("auth_unique_id")
        private val AUTH_DEVICE_ID = stringPreferencesKey("auth_device_id")
        private val AUTH_USER_ID = stringPreferencesKey("auth_user_id")

        private val AUTOPLAY_LAST_CHANNEL = booleanPreferencesKey("autoplay_last_channel")
        private val LAST_CHANNEL_ID = stringPreferencesKey("last_channel_id")
        private val LAST_CHANNEL_GROUP = stringPreferencesKey("last_channel_group")

        // Onboarding / account source. setupMode: null = not chosen yet, "phone" = OTP login,
        // "server" = pull credentials from a JTV proxy server.
        private val SETUP_MODE = stringPreferencesKey("setup_mode")
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val SERVER_TOKEN = stringPreferencesKey("server_token")

        // v2 appearance: "system" | "dark" | "light". Absent = system on phone/tablet, dark on TV.
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        // Recently watched channel ids, newest first (comma-joined, capped).
        private val RECENT_CHANNELS = stringPreferencesKey("recent_channels")
        private const val RECENT_MAX = 12
    }

    val recentChannelsFlow: Flow<List<String>> = context.dataStore.data.map { p ->
        p[RECENT_CHANNELS]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    }

    val themeModeFlow: Flow<String?> = context.dataStore.data.map { it[THEME_MODE] }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    val setupModeFlow: Flow<String?> = context.dataStore.data.map { it[SETUP_MODE] }
    val serverUrlFlow: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "" }
    val serverTokenFlow: Flow<String> = context.dataStore.data.map { it[SERVER_TOKEN] ?: "" }

    suspend fun setSetupMode(mode: String?) {
        context.dataStore.edit { prefs ->
            if (mode == null) prefs.remove(SETUP_MODE) else prefs[SETUP_MODE] = mode
        }
    }

    suspend fun setServerConfig(url: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL] = url
            prefs[SERVER_TOKEN] = token
        }
    }

    val defaultLanguageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_LANGUAGE] ?: "hi"
    }

    val defaultQualityFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_QUALITY] ?: "auto"
    }

    val hardwareDecoderFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HARDWARE_DECODER] ?: true
    }

    // Off by default: tunneling causes random black screens on many Amlogic/MediaTek TVs.
    val tunnelingFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TUNNELING] ?: false
    }

    // Max playback buffer in seconds. Higher = smoother (rides out network/CDN jitter) at the cost of
    // more RAM and slightly higher channel-zap time. Default 60s.
    val playbackBufferSecFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PLAYBACK_BUFFER_SEC] ?: 60
    }

    // Audio enhancement (applied via AudioEffects on the player session). Defaults to 2 (Medium) so
    // dialogue is clearer out of the box; users who explicitly set a level (incl. 0/Off) keep theirs.
    val voiceBoostFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[VOICE_BOOST] ?: 2
    }
    val audioNormalizeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUDIO_NORMALIZE] ?: false
    }

    val lastSelectedCategoryFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_SELECTED_CATEGORY]
    }

    val lastUpdateCheckFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LAST_UPDATE_CHECK]?.toLongOrNull() ?: 0L
    }

    val playerResizeModeFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PLAYER_RESIZE_MODE] ?: 0 // Default: RESIZE_MODE_FIT
    }

    // On by default: most users want one tile per channel and pick language in the player.
    val groupLanguageVariantsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GROUP_LANGUAGE_VARIANTS] ?: true
    }

    val epgModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[EPG_MODE] ?: false
    }

    val epgUrlFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[EPG_URL] ?: "https://avkb.short.gy/epg.xml.gz"
    }

    val autoplayLastChannelFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTOPLAY_LAST_CHANNEL] ?: false
    }

    val lastChannelIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_CHANNEL_ID]
    }

    val lastChannelGroupFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_CHANNEL_GROUP]
    }

    /**
     * Favorites in the user's chosen order. The same comma-joined key has always been written in
     * insertion order, so existing installs keep their favorites (oldest first) with no migration.
     */
    val favoriteOrderFlow: Flow<List<String>> = context.dataStore.data.map { preferences ->
        parseFavorites(preferences[FAVORITE_CHANNELS])
    }

    val favoriteChannelsFlow: Flow<Set<String>> = favoriteOrderFlow.map { it.toSet() }

    /** Favourites plus when they last changed, for LAN sync. */
    val favoritesSnapshotFlow: Flow<com.fenyx.jtv.sync.FavoritesSnapshot> = context.dataStore.data.map { p ->
        com.fenyx.jtv.sync.FavoritesSnapshot(parseFavorites(p[FAVORITE_CHANNELS]), p[FAVORITES_UPDATED_AT] ?: 0L)
    }

    /**
     * Applies favourites that came from another device, keeping their timestamp, but only when they are
     * newer than ours (checked inside the same transaction). Returns true when they were applied.
     */
    suspend fun applySyncedFavorites(ids: List<String>, updatedAt: Long): Boolean {
        var applied = false
        context.dataStore.edit { p ->
            if (updatedAt > (p[FAVORITES_UPDATED_AT] ?: 0L)) {
                p[FAVORITE_CHANNELS] = ids.filter { it.isNotBlank() }.distinct().joinToString(",")
                p[FAVORITES_UPDATED_AT] = updatedAt
                applied = true
            }
        }
        return applied
    }

    private fun stampFavorites(p: androidx.datastore.preferences.core.MutablePreferences) {
        p[FAVORITES_UPDATED_AT] = com.fenyx.jtv.sync.FavoritesSync.nextStamp(p[FAVORITES_UPDATED_AT] ?: 0L, System.currentTimeMillis())
    }

    val authDataFlow: Flow<JioApiClient.AuthData?> = context.dataStore.data.map { preferences ->
        val ssoToken = preferences[AUTH_SSO_TOKEN]
        if (ssoToken.isNullOrEmpty()) {
            null
        } else {
            JioApiClient.AuthData(
                ssoToken = ssoToken,
                authToken = preferences[AUTH_AUTH_TOKEN] ?: "",
                crmid = preferences[AUTH_CRMID] ?: "",
                uniqueId = preferences[AUTH_UNIQUE_ID] ?: "",
                deviceId = preferences[AUTH_DEVICE_ID] ?: "",
                userId = preferences[AUTH_USER_ID] ?: "",
                refreshToken = preferences[AUTH_REFRESH_TOKEN] ?: ""
            )
        }
    }

    suspend fun setDefaultLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_LANGUAGE] = language
        }
    }

    suspend fun setDefaultQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_QUALITY] = quality
        }
    }

    suspend fun setHardwareDecoder(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HARDWARE_DECODER] = enabled
        }
    }

    suspend fun setTunneling(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TUNNELING] = enabled
        }
    }

    suspend fun setPlaybackBufferSec(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PLAYBACK_BUFFER_SEC] = seconds
        }
    }

    suspend fun setVoiceBoost(level: Int) {
        context.dataStore.edit { preferences -> preferences[VOICE_BOOST] = level }
    }

    suspend fun setAudioNormalize(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[AUDIO_NORMALIZE] = enabled }
    }

    suspend fun setLastSelectedCategory(category: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SELECTED_CATEGORY] = category
        }
    }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_UPDATE_CHECK] = timestamp.toString()
        }
    }

    suspend fun setPlayerResizeMode(mode: Int) {
        context.dataStore.edit { preferences ->
            preferences[PLAYER_RESIZE_MODE] = mode
        }
    }

    suspend fun setGroupLanguageVariants(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[GROUP_LANGUAGE_VARIANTS] = enabled }
    }

    suspend fun setEpgMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[EPG_MODE] = enabled
        }
    }

    suspend fun setEpgUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[EPG_URL] = url
        }
    }

    suspend fun setAutoplayLastChannel(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTOPLAY_LAST_CHANNEL] = enabled
        }
    }

    suspend fun setLastChannelId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_CHANNEL_ID] = id
            // Same write also maintains "Recently watched" (move to front, de-duplicated, capped).
            val old = preferences[RECENT_CHANNELS]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            preferences[RECENT_CHANNELS] = (listOf(id) + old.filter { it != id }).take(RECENT_MAX).joinToString(",")
        }
    }

    suspend fun setLastChannelGroup(group: String?) {
        context.dataStore.edit { preferences ->
            if (group != null) {
                preferences[LAST_CHANNEL_GROUP] = group
            } else {
                preferences.remove(LAST_CHANNEL_GROUP)
            }
        }
    }

    /** Adds the channel to the END of the favorites order, or removes it. */
    suspend fun toggleFavoriteChannel(channelId: String) {
        context.dataStore.edit { preferences ->
            val list = parseFavorites(preferences[FAVORITE_CHANNELS]).toMutableList()
            if (!list.remove(channelId)) list.add(channelId)
            preferences[FAVORITE_CHANNELS] = list.joinToString(",")
            stampFavorites(preferences)
        }
    }

    /** Replaces the whole favorites order (used by the reorder / sort-by-category actions). */
    suspend fun setFavoriteOrder(ids: List<String>) {
        context.dataStore.edit { preferences ->
            val joined = ids.filter { it.isNotBlank() }.distinct().joinToString(",")
            if (joined != preferences[FAVORITE_CHANNELS].orEmpty()) {
                preferences[FAVORITE_CHANNELS] = joined
                stampFavorites(preferences)
            }
        }
    }

    private fun parseFavorites(serialized: String?): List<String> =
        if (serialized.isNullOrEmpty()) emptyList()
        else serialized.split(",").filter { it.isNotBlank() }.distinct()

    suspend fun saveAuthData(authData: JioApiClient.AuthData) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_SSO_TOKEN] = authData.ssoToken
            preferences[AUTH_AUTH_TOKEN] = authData.authToken
            preferences[AUTH_REFRESH_TOKEN] = authData.refreshToken
            preferences[AUTH_CRMID] = authData.crmid
            preferences[AUTH_UNIQUE_ID] = authData.uniqueId
            preferences[AUTH_DEVICE_ID] = authData.deviceId
            preferences[AUTH_USER_ID] = authData.userId
        }
    }

    val authMobileFlow: Flow<String> = context.dataStore.data.map { it[AUTH_MOBILE] ?: "" }

    suspend fun setAuthMobile(mobile: String) {
        context.dataStore.edit { it[AUTH_MOBILE] = mobile }
    }

    suspend fun clearAuthData() {
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_MOBILE)
            preferences.remove(AUTH_SSO_TOKEN)
            preferences.remove(AUTH_AUTH_TOKEN)
            preferences.remove(AUTH_REFRESH_TOKEN)
            preferences.remove(AUTH_CRMID)
            preferences.remove(AUTH_UNIQUE_ID)
            preferences.remove(AUTH_DEVICE_ID)
            preferences.remove(AUTH_USER_ID)
        }
    }
}

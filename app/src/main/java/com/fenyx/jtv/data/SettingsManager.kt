package com.fenyx.jtv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        private val VOICE_BOOST = intPreferencesKey("voice_boost")          // 0=off,1=low,2=high
        private val AUDIO_NORMALIZE = booleanPreferencesKey("audio_normalize")
        private val REDUCE_BACKGROUND = booleanPreferencesKey("reduce_background")
        private val BUFFER_SIZE = intPreferencesKey("buffer_size")
        private val LAST_SELECTED_CATEGORY = stringPreferencesKey("last_selected_category")
        private val LAST_SELECTED_CHANNEL_INDEX = intPreferencesKey("last_selected_channel_index")
        private val LAST_UPDATE_CHECK = stringPreferencesKey("last_update_check_timestamp")
        private val PLAYER_RESIZE_MODE = intPreferencesKey("player_resize_mode")
        
        private val EPG_MODE = booleanPreferencesKey("epg_mode")
        private val EPG_URL = stringPreferencesKey("epg_url")
        private val FAVORITE_CHANNELS = stringPreferencesKey("favorite_channels")
        
        private val AUTH_SSO_TOKEN = stringPreferencesKey("auth_sso_token")
        private val AUTH_AUTH_TOKEN = stringPreferencesKey("auth_auth_token")
        private val AUTH_CRMID = stringPreferencesKey("auth_crmid")
        private val AUTH_UNIQUE_ID = stringPreferencesKey("auth_unique_id")
        private val AUTH_DEVICE_ID = stringPreferencesKey("auth_device_id")
        private val AUTH_USER_ID = stringPreferencesKey("auth_user_id")

        private val AUTOPLAY_LAST_CHANNEL = booleanPreferencesKey("autoplay_last_channel")
        private val LAST_CHANNEL_ID = stringPreferencesKey("last_channel_id")
        private val LAST_CHANNEL_GROUP = stringPreferencesKey("last_channel_group")
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

    // Audio enhancement (applied via AudioEffects on the player session).
    val voiceBoostFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[VOICE_BOOST] ?: 0
    }
    val audioNormalizeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUDIO_NORMALIZE] ?: false
    }
    val reduceBackgroundFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REDUCE_BACKGROUND] ?: false
    }

    val bufferSizeFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[BUFFER_SIZE] ?: 5000
    }

    val lastSelectedCategoryFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_SELECTED_CATEGORY]
    }

    val lastSelectedChannelIndexFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[LAST_SELECTED_CHANNEL_INDEX] ?: 0
    }

    val lastUpdateCheckFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LAST_UPDATE_CHECK]?.toLongOrNull() ?: 0L
    }

    val playerResizeModeFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PLAYER_RESIZE_MODE] ?: 0 // Default: RESIZE_MODE_FIT
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

    val favoriteChannelsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        val serialized = preferences[FAVORITE_CHANNELS] ?: ""
        if (serialized.isEmpty()) emptySet() else serialized.split(",").toSet()
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
                userId = preferences[AUTH_USER_ID] ?: ""
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

    suspend fun setReduceBackground(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[REDUCE_BACKGROUND] = enabled }
    }

    suspend fun setBufferSize(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[BUFFER_SIZE] = size
        }
    }

    suspend fun setLastSelectedCategory(category: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SELECTED_CATEGORY] = category
        }
    }

    suspend fun setLastSelectedChannelIndex(index: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SELECTED_CHANNEL_INDEX] = index
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

    suspend fun toggleFavoriteChannel(channelId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[FAVORITE_CHANNELS] ?: ""
            val set = if (current.isEmpty()) mutableSetOf() else current.split(",").toMutableSet()
            if (set.contains(channelId)) {
                set.remove(channelId)
            } else {
                set.add(channelId)
            }
            preferences[FAVORITE_CHANNELS] = set.joinToString(",")
        }
    }

    suspend fun saveAuthData(authData: JioApiClient.AuthData) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_SSO_TOKEN] = authData.ssoToken
            preferences[AUTH_AUTH_TOKEN] = authData.authToken
            preferences[AUTH_CRMID] = authData.crmid
            preferences[AUTH_UNIQUE_ID] = authData.uniqueId
            preferences[AUTH_DEVICE_ID] = authData.deviceId
            preferences[AUTH_USER_ID] = authData.userId
        }
    }

    suspend fun clearAuthData() {
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_SSO_TOKEN)
            preferences.remove(AUTH_AUTH_TOKEN)
            preferences.remove(AUTH_CRMID)
            preferences.remove(AUTH_UNIQUE_ID)
            preferences.remove(AUTH_DEVICE_ID)
            preferences.remove(AUTH_USER_ID)
        }
    }
}

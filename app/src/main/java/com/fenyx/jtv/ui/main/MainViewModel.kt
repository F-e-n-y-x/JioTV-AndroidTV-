package com.fenyx.jtv.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.JioApiClient
import com.fenyx.jtv.data.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)
    private val epgRepository = com.fenyx.jtv.data.EpgRepository(application)
    val epgSyncStatus = epgRepository.syncStatus

    private val _epgData = MutableStateFlow<Map<String, List<com.fenyx.jtv.data.EpgProgram>>>(emptyMap())
    val epgData: StateFlow<Map<String, List<com.fenyx.jtv.data.EpgProgram>>> = _epgData.asStateFlow()

    private val _favoriteChannels = MutableStateFlow<Set<String>>(emptySet())
    val favoriteChannels: StateFlow<Set<String>> = _favoriteChannels.asStateFlow()

    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    
    // Moved init block down

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _groups = MutableStateFlow<List<String>>(emptyList())
    val groups: StateFlow<List<String>> = _groups.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedGroup = MutableStateFlow<String?>(null)
    val selectedGroup: StateFlow<String?> = _selectedGroup.asStateFlow()

    private val _filteredChannels = MutableStateFlow<List<Channel>>(emptyList())
    val filteredChannels: StateFlow<List<Channel>> = _filteredChannels.asStateFlow()

    private var hasLoaded: Boolean = false

    init {
        viewModelScope.launch {
            settingsManager.favoriteChannelsFlow.collect { favorites ->
                _favoriteChannels.value = favorites
            }
        }
        // Compute filtered/sorted channels reactively in the ViewModel (not in Compose)
        viewModelScope.launch {
            combine(_allChannels, _selectedGroup, _favoriteChannels) { all, group, favs ->
                val list = if (group == null) all else all.filter { it.group == group }
                list.sortedWith(compareByDescending<Channel> { favs.contains(it.id) }.thenBy { it.channelNumber })
            }.collect { _filteredChannels.value = it }
        }
        // NOTE: EPG is intentionally NOT fetched here. Downloading + parsing the XMLTV file on every
        // launch hammered the CPU on low-end TVs and slowed boot. MainScreen triggers fetchEpg() only
        // when EPG mode is enabled, and Settings offers a manual refresh.
    }

    /** Get all channels (unfiltered) for the player's channel switching */
    fun getAllChannels(): List<Channel> = _allChannels.value

    /** Get channels filtered by group for channel switching within a category, sorted by favorites */
    fun getChannelsByGroup(group: String?): List<Channel> {
        val list = if (group == null) _allChannels.value else _allChannels.value.filter { it.group == group }
        val favorites = _favoriteChannels.value
        return list.sortedWith(compareByDescending<Channel> { favorites.contains(it.id) }.thenBy { it.channelNumber })
    }

    fun setSelectedGroup(group: String?) {
        _selectedGroup.value = group
        // Persist to DataStore
        group?.let {
            viewModelScope.launch {
                settingsManager.setLastSelectedCategory(it)
            }
        }
    }

    private suspend fun publishChannels(parsedChannels: List<Channel>) {
        _allChannels.value = parsedChannels
        _channels.value = parsedChannels
        _groups.value = parsedChannels.map { it.group }.distinct().sorted()
        hasLoaded = true

        // Restore last selected category
        if (_selectedGroup.value == null) {
            val lastCategory = settingsManager.lastSelectedCategoryFlow.first()
            val groups = _groups.value
            _selectedGroup.value = if (lastCategory != null && groups.contains(lastCategory)) {
                lastCategory
            } else {
                groups.firstOrNull()
            }
        }
    }

    fun fetchChannels(port: Int = 0) {
        // Skip if already loaded with data
        if (hasLoaded && _allChannels.value.isNotEmpty()) {
            Log.d("MainViewModel", "Channels already loaded, skipping fetch")
            return
        }

        viewModelScope.launch {
            _error.value = null
            val app = getApplication<Application>()

            // 1) Instant load from disk so the UI appears immediately (no network wait on boot).
            val cached = JioApiClient.readChannelCache(app)
            val cacheFresh = JioApiClient.isChannelCacheFresh(app)
            if (cached != null) {
                publishChannels(cached)
                _isLoading.value = false
                Log.d("MainViewModel", "Loaded ${cached.size} channels from cache (fresh=$cacheFresh)")
            } else {
                _isLoading.value = true
            }

            // 2) Revalidate over the network only when there is no cache or it's stale.
            if (cached == null || !cacheFresh) {
                val result = JioApiClient.getMobileChannelList(app, forceNetwork = true)
                if (result.isSuccess) {
                    val parsedChannels = result.getOrNull() ?: emptyList()
                    if (parsedChannels.isNotEmpty()) {
                        publishChannels(parsedChannels)
                    } else if (cached == null) {
                        _error.value = "No channels found."
                    }
                } else if (cached == null) {
                    _error.value = "Error: ${result.exceptionOrNull()?.message}"
                }
            }

            _isLoading.value = false
        }
    }

    fun fetchEpg(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val url = settingsManager.epgUrlFlow.first()
            val data = epgRepository.getEpgData(url, forceRefresh)
            
            // Merge with existing native EPG data so we don't wipe it out
            val newData = data.toMutableMap()
            _epgData.value.forEach { (channelId, programs) ->
                if (!newData.containsKey(channelId)) {
                    newData[channelId] = programs
                }
            }
            _epgData.value = newData
        }
    }

    private val fetchingEpgChannels = mutableSetOf<String>()

    fun fetchNativeEpgIfMissing(channelId: String) {
        val currentData = _epgData.value[channelId]
        if (currentData.isNullOrEmpty() && !fetchingEpgChannels.contains(channelId)) {
            fetchingEpgChannels.add(channelId)
            viewModelScope.launch {
                val programs = epgRepository.getNativeEpgForChannel(channelId)
                if (programs.isNotEmpty()) {
                    _epgData.value = _epgData.value + (channelId to programs)
                }
                fetchingEpgChannels.remove(channelId)
            }
        }
    }

    fun retry() {
        hasLoaded = false
        fetchChannels()
    }
}

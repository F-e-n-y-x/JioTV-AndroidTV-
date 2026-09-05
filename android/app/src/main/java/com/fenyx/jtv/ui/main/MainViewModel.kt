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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Sentinel category values for the Home sidebar. Real Jio categories never collide with these.
        const val GROUP_ALL: String = "__ALL__"
        const val GROUP_FAVORITES: String = "__FAVORITES__"
    }

    private val settingsManager = SettingsManager(application)
    private val epgRepository = com.fenyx.jtv.data.EpgRepository(application)
    val epgSyncStatus = epgRepository.syncStatus

    private val _epgData = MutableStateFlow<Map<String, List<com.fenyx.jtv.data.EpgProgram>>>(emptyMap())
    val epgData: StateFlow<Map<String, List<com.fenyx.jtv.data.EpgProgram>>> = _epgData.asStateFlow()

    private val _favoriteChannels = MutableStateFlow<Set<String>>(emptySet())
    val favoriteChannels: StateFlow<Set<String>> = _favoriteChannels.asStateFlow()

    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())

    // Channels as shown in the UI: language-variant collapsing is applied here when enabled, so every
    // consumer (grid, per-group player list, index maps) sees the same collapsed list.
    private val _displayChannels = MutableStateFlow<List<Channel>>(emptyList())
    /** Collapsed all-channels list (reactive) — used by Search. */
    val displayChannels: StateFlow<List<Channel>> = _displayChannels.asStateFlow()

    @Volatile
    private var variantMap: Map<String, List<com.fenyx.jtv.data.ChannelLanguage.Variant>> = emptyMap()

    /** Language feeds collapsed under the given representative channel id ([] when it isn't a family). */
    fun variantsFor(channelId: String): List<com.fenyx.jtv.data.ChannelLanguage.Variant> =
        variantMap[channelId] ?: emptyList()

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

    // "Refresh from Server" button state (server mode).
    private val _serverRefreshing = MutableStateFlow(false)
    val serverRefreshing: StateFlow<Boolean> = _serverRefreshing.asStateFlow()
    private val _serverRefreshMsg = MutableStateFlow<String?>(null)
    val serverRefreshMsg: StateFlow<String?> = _serverRefreshMsg.asStateFlow()

    // In-app update state
    private val _updateInfo = MutableStateFlow<com.fenyx.jtv.data.AppUpdateManager.UpdateInfo?>(null)
    val updateInfo: StateFlow<com.fenyx.jtv.data.AppUpdateManager.UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0f)
    val updateDownloadProgress: StateFlow<Float> = _updateDownloadProgress.asStateFlow()

    private val _updateDownloadedBytes = MutableStateFlow(0L)
    val updateDownloadedBytes: StateFlow<Long> = _updateDownloadedBytes.asStateFlow()

    private val _updateTotalBytes = MutableStateFlow(0L)
    val updateTotalBytes: StateFlow<Long> = _updateTotalBytes.asStateFlow()

    private val _updateStatusMessage = MutableStateFlow<String?>(null)
    val updateStatusMessage: StateFlow<String?> = _updateStatusMessage.asStateFlow()

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    private val _downloadedApkFile = MutableStateFlow<java.io.File?>(null)
    val downloadedApkFile: StateFlow<java.io.File?> = _downloadedApkFile.asStateFlow()

    init {
        viewModelScope.launch {
            settingsManager.favoriteChannelsFlow.collect { favorites ->
                _favoriteChannels.value = favorites
            }
        }
        // Apply language-variant collapsing. Depends ONLY on the channel list and the toggle (both
        // deduped), and the collapse runs on Dispatchers.Default — previously it also keyed on
        // defaultLanguageFlow and ran on the main thread, so EVERY DataStore write (incl. the
        // setLastChannelId on each channel zap) re-collapsed all ~1300 channels on the UI thread and
        // reshuffled the list, which both janked the app and swapped the playing channel out from under
        // the player. Representative selection is now language-independent, so the list stays stable.
        viewModelScope.launch {
            combine(
                _allChannels,
                settingsManager.groupLanguageVariantsFlow.distinctUntilChanged()
            ) { all, groupOn ->
                if (groupOn && all.isNotEmpty()) com.fenyx.jtv.data.ChannelLanguage.collapse(all)
                else all to emptyMap<String, List<com.fenyx.jtv.data.ChannelLanguage.Variant>>()
            }.flowOn(kotlinx.coroutines.Dispatchers.Default).collect { (display, map) ->
                variantMap = map
                _displayChannels.value = display
            }
        }
        // Compute filtered/sorted channels reactively in the ViewModel (not in Compose)
        viewModelScope.launch {
            combine(_displayChannels, _selectedGroup, _favoriteChannels) { all, group, favs ->
                val list = when (group) {
                    null, GROUP_ALL -> all
                    GROUP_FAVORITES -> all.filter { favs.contains(it.id) }
                    else -> all.filter { it.group == group }
                }
                list.sortedWith(compareByDescending<Channel> { favs.contains(it.id) }.thenBy { it.channelNumber })
            }.collect { _filteredChannels.value = it }
        }
        // NOTE: EPG is intentionally NOT fetched here. Downloading + parsing the XMLTV file on every
        // launch hammered the CPU on low-end TVs and slowed boot. MainScreen triggers fetchEpg() only
        // when EPG mode is enabled, and Settings offers a manual refresh.

        // Server mode: keep credentials fresh in the BACKGROUND. The app boots instantly on the cached
        // credentials (never blocks on the network); this quietly re-pulls the server's centrally
        // refreshed token once shortly after launch and every few hours, so a rotating shared token
        // never breaks playback and the user never has to re-run setup. Failures are ignored — the
        // cached credentials keep working until the next attempt, and a stream 401 also self-heals.
        viewModelScope.launch {
            val mode = settingsManager.setupModeFlow.first()
            if (mode == "server" || mode == "jtv") {
                while (true) {
                    syncServerCredentialsQuietly()
                    kotlinx.coroutines.delay(3 * 60 * 60 * 1000L) // every 3h while the app is open
                }
            }
        }

        // Quiet background update check on app launch
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000L)
            checkForUpdates(manual = false)
        }
    }

    private suspend fun syncServerCredentialsQuietly() {
        val mode = settingsManager.setupModeFlow.first()
        val urls = com.fenyx.jtv.data.ServerClient.candidateUrls(mode, settingsManager.serverUrlFlow.first())
        if (urls.all { it.isBlank() }) return
        val tok = settingsManager.serverTokenFlow.first()
        com.fenyx.jtv.data.ServerClient.fetchCredentials(urls, tok)
            .onSuccess { settingsManager.saveAuthData(it) }
    }

    /**
     * "Refresh from Server" button: forces the server to refresh the Jio token (POST /api/refresh),
     * pulls the fresh credentials, and force-reloads the channel list. Surfaces status via
     * [serverRefreshing] / [serverRefreshMsg].
     */
    fun refreshFromServer() {
        if (_serverRefreshing.value) return
        viewModelScope.launch {
            _serverRefreshing.value = true
            _serverRefreshMsg.value = null
            val mode = settingsManager.setupModeFlow.first()
            val urls = com.fenyx.jtv.data.ServerClient.candidateUrls(mode, settingsManager.serverUrlFlow.first())
            val tok = settingsManager.serverTokenFlow.first()
            com.fenyx.jtv.data.ServerClient.refreshCredentials(urls, tok)
                .onSuccess { auth ->
                    settingsManager.saveAuthData(auth)
                    // Also force a fresh channel-list pull from the network.
                    val app = getApplication<Application>()
                    val result = JioApiClient.getMobileChannelList(app, forceNetwork = true)
                    result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { publishChannels(it) }
                    _serverRefreshMsg.value = "Refreshed"
                }
                .onFailure { _serverRefreshMsg.value = it.message ?: "Refresh failed" }
            _serverRefreshing.value = false
            kotlinx.coroutines.delay(4000)
            _serverRefreshMsg.value = null
        }
    }

    /** Get all channels (unfiltered, collapsed) for the player's channel switching */
    fun getAllChannels(): List<Channel> = _displayChannels.value

    /** Get channels filtered by group for channel switching within a category, sorted by favorites */
    fun getChannelsByGroup(group: String?): List<Channel> {
        val source = _displayChannels.value
        val favorites = _favoriteChannels.value
        val list = when (group) {
            null, GROUP_ALL -> source
            GROUP_FAVORITES -> source.filter { favorites.contains(it.id) }
            else -> source.filter { it.group == group }
        }
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

        // Restore last selected category (accepting the "All"/"Favorites" pseudo-categories),
        // defaulting new users to "All" so the first screen shows everything.
        if (_selectedGroup.value == null) {
            val lastCategory = settingsManager.lastSelectedCategoryFlow.first()
            val groups = _groups.value
            _selectedGroup.value = when {
                lastCategory == GROUP_ALL || lastCategory == GROUP_FAVORITES -> lastCategory
                lastCategory != null && groups.contains(lastCategory) -> lastCategory
                else -> GROUP_ALL
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
    // Cap concurrent native-EPG requests: scrolling the EPG list fast used to fire one network call
    // per newly-visible row, flooding a weak TV with dozens of parallel connections.
    private val epgFetchSemaphore = kotlinx.coroutines.sync.Semaphore(4)

    fun fetchNativeEpgIfMissing(channelId: String) {
        val currentData = _epgData.value[channelId]
        if (currentData.isNullOrEmpty() && !fetchingEpgChannels.contains(channelId)) {
            fetchingEpgChannels.add(channelId)
            viewModelScope.launch {
                try {
                    epgFetchSemaphore.withPermit {
                        val programs = epgRepository.getNativeEpgForChannel(channelId)
                        if (programs.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            val cur = programs.find { it.startMs <= now && it.stopMs > now }
                            Log.d("EpgDiag", "ch=$channelId n=${programs.size} now=$now " +
                                "range=${programs.first().startMs}..${programs.last().stopMs} current='${cur?.title}'")
                            _epgData.value = _epgData.value + (channelId to programs)
                        }
                    }
                } finally {
                    fetchingEpgChannels.remove(channelId)
                }
            }
        }
    }

    fun retry() {
        hasLoaded = false
        fetchChannels()
    }

    fun checkForUpdates(manual: Boolean = false) {
        if (_isCheckingUpdate.value || _isDownloadingUpdate.value) return
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _updateError.value = null
            if (manual) _updateStatusMessage.value = "Checking for updates..."
            val app = getApplication<Application>()
            val result = com.fenyx.jtv.data.AppUpdateManager.checkForUpdate(app)
            result.onSuccess { info ->
                _updateInfo.value = info
                if (info != null && info.isUpdateAvailable) {
                    _updateStatusMessage.value = "Update v${info.versionName} available"
                } else if (manual) {
                    _updateStatusMessage.value = "App is up to date"
                }
            }.onFailure { err ->
                if (manual) {
                    _updateError.value = err.message ?: "Failed to check update"
                    _updateStatusMessage.value = "Failed to check update"
                }
            }
            _isCheckingUpdate.value = false
        }
    }

    fun downloadAndInstallUpdate(context: android.content.Context) {
        val info = _updateInfo.value ?: return
        if (_isDownloadingUpdate.value) return

        // If already downloaded and exists, trigger install directly
        val existingFile = _downloadedApkFile.value
        if (existingFile != null && existingFile.exists()) {
            com.fenyx.jtv.data.AppUpdateManager.installApk(context, existingFile)
            return
        }

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _updateError.value = null
            _updateDownloadProgress.value = 0f
            _updateDownloadedBytes.value = 0L
            _updateTotalBytes.value = info.apkSize
            _updateStatusMessage.value = "Downloading update..."

            val result = com.fenyx.jtv.data.AppUpdateManager.downloadApk(
                context = context,
                downloadUrl = info.downloadUrl,
                onProgress = { downloaded, total, progress ->
                    _updateDownloadedBytes.value = downloaded
                    _updateTotalBytes.value = total
                    _updateDownloadProgress.value = progress
                }
            )

            result.onSuccess { file ->
                _downloadedApkFile.value = file
                _isDownloadingUpdate.value = false
                _updateStatusMessage.value = "Download complete"
                com.fenyx.jtv.data.AppUpdateManager.installApk(context, file)
            }.onFailure { err ->
                _isDownloadingUpdate.value = false
                _updateError.value = err.message ?: "Download failed"
                _updateStatusMessage.value = "Download failed"
            }
        }
    }
}

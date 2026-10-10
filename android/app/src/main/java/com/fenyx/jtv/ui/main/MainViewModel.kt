package com.fenyx.jtv.ui.main

import com.fenyx.jtv.R
import com.fenyx.jtv.data.userText
import com.fenyx.jtv.i18n.UiText
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.JioApiClient
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.data.FavoriteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** A user-visible message in the app language (the application resources follow it; see AppLocale). */
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)


    companion object {
        // Sentinel category values for the Home sidebar. Real Jio categories never collide with these.
        const val GROUP_ALL: String = "__ALL__"
        const val GROUP_FAVORITES: String = "__FAVORITES__"
        const val GROUP_RECENT: String = "__RECENT__"
    }

    private val settingsManager = SettingsManager(application)
    private val epgRepository = com.fenyx.jtv.data.EpgRepository(application)
    val epgSyncStatus = epgRepository.syncStatus

    private val _epgData = MutableStateFlow<Map<String, List<com.fenyx.jtv.data.EpgProgram>>>(emptyMap())
    val epgData: StateFlow<Map<String, List<com.fenyx.jtv.data.EpgProgram>>> = _epgData.asStateFlow()

    private val _favoriteChannels = MutableStateFlow<Set<String>>(emptySet())
    val favoriteChannels: StateFlow<Set<String>> = _favoriteChannels.asStateFlow()

    /** Favorite ids in the user's chosen order (the Favorites category is shown in this order). */
    private val _favoriteOrder = MutableStateFlow<List<String>>(emptyList())
    val favoriteOrder: StateFlow<List<String>> = _favoriteOrder.asStateFlow()

    /** Settings → Categories, applied by every screen that lists categories. */
    val categoryPrefs: StateFlow<com.fenyx.jtv.data.CategoryPrefs> = settingsManager.categoryPrefsFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.fenyx.jtv.data.CategoryPrefs())

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

    /** Recently watched channel ids, newest first (written by the player on every tune). */
    private val _recentIds = MutableStateFlow<List<String>>(emptyList())
    val recentIds: StateFlow<List<String>> = _recentIds.asStateFlow()

    init {
        viewModelScope.launch {
            settingsManager.recentChannelsFlow.distinctUntilChanged().collect { _recentIds.value = it }
        }
        viewModelScope.launch {
            settingsManager.favoriteOrderFlow.distinctUntilChanged().collect { order ->
                _favoriteOrder.value = order
                _favoriteChannels.value = order.toSet()
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
            combine(_displayChannels, _selectedGroup, _favoriteOrder, _recentIds) { all, group, order, recent ->
                // Recents only matter for the Recent category; other groups ignore them (the StateFlow
                // dedups an identical list, so a zap doesn't recompose the home list).
                channelsFor(all, group, order, if (group == GROUP_RECENT) recent else emptyList())
            }.flowOn(kotlinx.coroutines.Dispatchers.Default).collect { _filteredChannels.value = it }
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
                    _serverRefreshMsg.value = str(R.string.home_refreshed)
                }
                .onFailure { _serverRefreshMsg.value = it.userText(UiText.of(R.string.home_refresh_failed)).resolve(getApplication()) }
            _serverRefreshing.value = false
            kotlinx.coroutines.delay(4000)
            _serverRefreshMsg.value = null
        }
    }

    /** Get all channels (unfiltered, collapsed) for the player's channel switching */
    fun getAllChannels(): List<Channel> = _displayChannels.value

    /** Get channels filtered by group for channel switching within a category, sorted by favorites */
    fun getChannelsByGroup(group: String?): List<Channel> =
        channelsFor(_displayChannels.value, group, _favoriteOrder.value, if (group == GROUP_RECENT) _recentIds.value else emptyList())

    /**
     * One ordering rule for the grid AND the player's zapping list: the Favorites category follows the
     * user's saved order; every other category puts favorites first (in that same order), then the
     * rest by channel number.
     */
    private fun channelsFor(all: List<Channel>, group: String?, order: List<String>, recent: List<String> = emptyList()): List<Channel> {
        if (group == GROUP_RECENT) {
            val byId = all.associateBy { it.id }
            return recent.mapNotNull { byId[it] }
        }
        if (group == GROUP_FAVORITES) return FavoriteOrder.sortedFavorites(all, order) { it.id }
        val list = if (group == null || group == GROUP_ALL) all else all.filter { it.group == group }
        if (order.isEmpty()) return list.sortedBy { it.channelNumber }
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return list.sortedWith(compareBy<Channel> { rank[it.id] ?: Int.MAX_VALUE }.thenBy { it.channelNumber })
    }

    // ── Favorites editing ──

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { settingsManager.toggleFavoriteChannel(channelId) }
    }

    /**
     * Saves a new order for the favorites currently shown. The in-memory order is updated first so
     * the grid doesn't flash back to the old order while DataStore writes.
     */
    fun saveFavoriteOrder(visibleIds: List<String>) {
        val merged = FavoriteOrder.merge(_favoriteOrder.value, visibleIds)
        if (merged == _favoriteOrder.value) return
        _favoriteOrder.value = merged
        viewModelScope.launch { settingsManager.setFavoriteOrder(merged) }
    }

    /** Groups favorites by category (categories in first-appearance order, stable inside each). */
    fun groupFavoritesByCategory() {
        val visible = FavoriteOrder.sortedFavorites(_displayChannels.value, _favoriteOrder.value) { it.id }
        saveFavoriteOrder(FavoriteOrder.groupByCategory(visible) { it.group }.map { it.id })
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
            // readChannelCache is now a suspend fun that handles its own IO dispatch internally
            // (including loading Zee channels without runBlocking deadlock risk).
            val cached = JioApiClient.readChannelCache(app)
            val cacheFresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { JioApiClient.isChannelCacheFresh(app) }
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
                        _error.value = str(R.string.home_no_channels_found)
                    }
                } else if (cached == null) {
                    _error.value = result.exceptionOrNull().userText(UiText.of(R.string.home_error_with_detail, result.exceptionOrNull()?.message.orEmpty())).resolve(getApplication())
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
    private val epgFetchSemaphore = kotlinx.coroutines.sync.Semaphore(6) // Jio CDN caches guide data

    // Guide data for the visible rows arrives one channel at a time. Publishing each one separately
    // re-laid-out the whole list ~15 times in a row on weak TVs; collect them and publish at most every
    // 250 ms instead (one update per batch).
    private val pendingEpg = java.util.concurrent.ConcurrentHashMap<String, List<com.fenyx.jtv.data.EpgProgram>>()
    private var epgFlushJob: kotlinx.coroutines.Job? = null
    private fun queueEpg(channelId: String, programs: List<com.fenyx.jtv.data.EpgProgram>) {
        pendingEpg[channelId] = programs
        if (epgFlushJob?.isActive == true) return
        epgFlushJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            val batch = HashMap(pendingEpg); batch.keys.forEach { pendingEpg.remove(it) }
            if (batch.isNotEmpty()) {
                val cur = _epgData.value
                // Keep any earlier days a catch-up fetch already merged in for that channel.
                _epgData.value = cur + batch.mapValues { (id, progs) ->
                    cur[id].orEmpty().filter { it.stopMs <= progs.first().startMs } + progs
                }
            }
            if (pendingEpg.isNotEmpty()) { epgFlushJob = null; pendingEpg.entries.firstOrNull()?.let { (k, v) -> queueEpg(k, v) } }
        }
    }

    fun fetchNativeEpgIfMissing(channelId: String) {
        val currentData = _epgData.value[channelId]
        // Only earlier catch-up days loaded (no programme reaching now) still counts as missing.
        val nowMs = System.currentTimeMillis()
        val missing = currentData.isNullOrEmpty() ||
            (channelId !in pastOnlyChecked && currentData.none { it.stopMs > nowMs }).also { if (it) pastOnlyChecked.add(channelId) }
        if (missing && !fetchingEpgChannels.contains(channelId)) {
            fetchingEpgChannels.add(channelId)
            viewModelScope.launch {
                try {
                    epgFetchSemaphore.withPermit {
                        var programs = epgRepository.getNativeEpgForChannel(channelId)
                        // Jio's "today" (offset 0) lags for hours after midnight and ends before now;
                        // then its "tomorrow" is the real today.
                        if (programs.none { it.stopMs > System.currentTimeMillis() }) {
                            programs = (programs + epgRepository.getNativeEpgForChannel(channelId, offset = 1))
                                .distinctBy { it.startMs }.sortedBy { it.startMs }
                        }
                        if (programs.isNotEmpty()) queueEpg(channelId, programs)
                    }
                } finally {
                    fetchingEpgChannels.remove(channelId)
                }
            }
        }
    }

    // ── Catch-up: older guide days, and "Replay earlier shows" from a channel's options ──

    /** "channelId:offset" pairs already fetched (or in flight) for past days. */
    private val pastEpgFetched = HashSet<String>()
    /** Channels whose guide held only earlier days once; today's guide was then fetched for them. */
    private val pastOnlyChecked = HashSet<String>()

    /**
     * Fetches Jio's guide for an earlier day ([offset] -1 … -6) of a catch-up channel and merges it
     * into [epgData] (de-duplicated by start time, sorted). Once per channel and day per session.
     */
    fun fetchPastEpgIfMissing(channelId: String, offset: Int) {
        if (offset >= 0 || offset < com.fenyx.jtv.data.Catchup.OLDEST_OFFSET) return
        val key = "$channelId:$offset"
        if (!pastEpgFetched.add(key)) return
        viewModelScope.launch {
            val programs = epgFetchSemaphore.withPermit { epgRepository.getNativeEpgForChannel(channelId, offset) }
            if (programs.isEmpty()) { pastEpgFetched.remove(key); return@launch }
            val merged = HashMap<Long, com.fenyx.jtv.data.EpgProgram>()
            programs.forEach { merged[it.startMs] = it }
            _epgData.value[channelId]?.forEach { merged[it.startMs] = it }
            _epgData.value = _epgData.value + (channelId to merged.values.sortedBy { it.startMs })
        }
    }

    /** Set by "Replay earlier shows": the guide opens on this channel's row, a little back in time. */
    private val _guideFocusChannel = MutableStateFlow<String?>(null)
    val guideFocusChannel: StateFlow<String?> = _guideFocusChannel.asStateFlow()
    fun requestGuideFocus(channelId: String) { _guideFocusChannel.value = channelId }
    fun consumeGuideFocus() { _guideFocusChannel.value = null }

    fun retry() {
        hasLoaded = false
        fetchChannels()
    }

    fun checkForUpdates(manual: Boolean = false) {
        if (_isCheckingUpdate.value || _isDownloadingUpdate.value) return
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _updateError.value = null
            if (manual) _updateStatusMessage.value = str(R.string.home_update_checking)
            val app = getApplication<Application>()
            val result = com.fenyx.jtv.data.AppUpdateManager.checkForUpdate(app)
            result.onSuccess { info ->
                _updateInfo.value = info
                if (info != null && info.isUpdateAvailable) {
                    _updateStatusMessage.value = str(R.string.home_update_available, info.versionName)
                } else if (manual) {
                    _updateStatusMessage.value = str(R.string.home_update_up_to_date)
                }
            }.onFailure { err ->
                if (manual) {
                    _updateError.value = str(R.string.home_update_check_failed)
                    _updateStatusMessage.value = str(R.string.home_update_check_failed)
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
            _updateStatusMessage.value = str(R.string.home_update_downloading)

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
                _updateStatusMessage.value = str(R.string.home_update_downloaded)
                com.fenyx.jtv.data.AppUpdateManager.installApk(context, file)
            }.onFailure { err ->
                _isDownloadingUpdate.value = false
                _updateError.value = str(R.string.home_update_download_failed)
                _updateStatusMessage.value = str(R.string.home_update_download_failed)
            }
        }
    }

    // ── Faster first play ──
    // Resolve a channel's stream link before it's opened: the channel the user rests on in the list
    // (TV focus, after a short pause) and, at launch, the last-watched channel + first favourites.
    // Network only (no player); uses the same short-lived cache the zap prefetch uses.
    private var focusPrefetchJob: kotlinx.coroutines.Job? = null
    fun prefetchSoon(channelId: String, delayMs: Long = 500) {
        focusPrefetchJob?.cancel()
        focusPrefetchJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(delayMs)
            prefetchNow(channelId)
        }
    }
    private suspend fun prefetchNow(channelId: String) {
        val auth = settingsManager.authDataFlow.first() ?: return
        val id = variantsFor(channelId).firstOrNull { it.channel.id == channelId }?.channel?.id ?: channelId
        runCatching { JioApiClient.prefetchStreamUrl(getApplication(), id, auth) }
    }
    private var launchWarmDone = false
    fun warmLikelyChannels() {
        if (launchWarmDone) return
        launchWarmDone = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ids = buildList {
                settingsManager.lastChannelIdFlow.first()?.let { add(it) }
                addAll(_favoriteOrder.value.take(2))
            }.distinct().take(3)
            ids.forEach { prefetchNow(it) }
        }
    }
}

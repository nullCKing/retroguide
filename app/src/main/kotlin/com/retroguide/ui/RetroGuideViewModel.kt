package com.retroguide.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.retroguide.AppContainer
import com.retroguide.core.guide.GuideCursor
import com.retroguide.core.guide.GuideNavigator
import com.retroguide.core.guide.GuideSource
import com.retroguide.core.guide.ProgramSlot
import com.retroguide.core.guide.TimeWindow
import com.retroguide.core.model.Country
import com.retroguide.core.model.FilterRules
import com.retroguide.core.model.Market
import com.retroguide.data.epg.EpgProgress
import com.retroguide.data.settings.AppSettings
import com.retroguide.data.xtream.ImportProgress
import com.retroguide.data.xtream.XtreamAccount
import com.retroguide.data.xtream.XtreamError
import com.retroguide.domain.GuideChannel
import com.retroguide.player.PlayableChannel
import com.retroguide.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.retroguide.core.model.RawCategory
import com.retroguide.core.model.RawVodStream
import com.retroguide.core.model.RawVodInfo
import com.retroguide.core.model.RawSeries
import com.retroguide.core.model.RawSeriesInfo
import com.retroguide.core.filter.CountryDetector
import com.retroguide.core.filter.ForeignCountries
import com.retroguide.core.filter.StreamingServiceDetector
import com.retroguide.core.text.Tokenizer
import com.retroguide.data.db.CategoryEntity
import com.retroguide.ui.categories.ALL_CHANNELS_ID
import com.retroguide.ui.categories.FAVORITE_CHANNELS_ID

/** Which screen is in front. */
enum class Screen {
    Starting,
    Login,
    Importing,
    Home,
    LiveCategories,
    Watching,
    Guide,
    VodCategories,
    SeriesCategories,
    Settings,
}

data class UiState(
    val screen: Screen = Screen.Starting,
    val settings: AppSettings = AppSettings(),
    val loginError: String? = null,
    val isSigningIn: Boolean = false,
    val importMessage: String? = null,
    val importDetail: String? = null,
    val allowedFormats: List<String> = listOf("ts"),
    val accountExpiry: Long? = null,
    val maxConnections: Int = 1,
    val credentialsEncrypted: Boolean = true,
    /** Set while adding a country needs new categories fetched. */
    val isFetchingMore: Boolean = false,
    val savedAccount: XtreamAccount? = null,
    val liveCategories: List<CategoryEntity> = emptyList(),
    val categoryChannelCounts: Map<String, Int> = emptyMap(),
    val favoriteCategoryIds: Set<String> = emptySet(),
    val favoriteChannelCount: Int = 0,
    val totalChannelCount: Int = 0,
    val vodCategories: List<RawCategory> = emptyList(),
    val vodMovies: List<RawVodStream> = emptyList(),
    val selectedVodCategory: RawCategory? = null,
    val isLoadingVodMovies: Boolean = false,
    val selectedVodInfo: RawVodInfo? = null,
    val isLoadingVodInfo: Boolean = false,
    val seriesCategories: List<RawCategory> = emptyList(),
    val seriesList: List<RawSeries> = emptyList(),
    val selectedSeriesCategory: RawCategory? = null,
    val isLoadingSeries: Boolean = false,
    val selectedSeriesInfo: RawSeriesInfo? = null,
    val isLoadingSeriesInfo: Boolean = false,
)

/**
 * The channel banner's contents.
 *
 * [showToken] changes on every tune, including a re-tune to the same channel, which is what lets
 * the overlay restart its four-second timer rather than keeping a stale one running.
 */
data class BannerState(
    val channel: GuideChannel? = null,
    val now: ProgramSlot? = null,
    val next: ProgramSlot? = null,
    val showToken: Long = 0L,
)

data class GuideState(
    val channels: List<GuideChannel> = emptyList(),
    val allChannels: List<GuideChannel> = emptyList(),
    val programsByKey: Map<String, List<ProgramSlot>> = emptyMap(),
    val cursor: GuideCursor = GuideCursor(0, 0L, TimeWindow(0L), 0),
    val nowMs: Long = 0L,
    /** The programme under the highlight. */
    val selected: ProgramSlot? = null,
    /** Non-null while the future-programme details dialog is open. */
    val details: ProgramSlot? = null,
    val categoryId: String? = null,
    val categoryName: String = "ALL CHANNELS",
) {
    val selectedChannel: GuideChannel?
        get() = channels.getOrNull(cursor.channelIndex)
}

/**
 * The app's single view model.
 *
 * It coordinates login, import, the guide cursor and the player. The genuinely tricky logic —
 * which channels survive the filter, where a cell sits on the grid, what Up and Left do to the
 * cursor — is not here: it lives in `:core` where it is unit-tested without an emulator. What is
 * here is plumbing, deliberately kept thin for that reason.
 */
class RetroGuideViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _guide = MutableStateFlow(GuideState())
    val guide: StateFlow<GuideState> = _guide.asStateFlow()

    private val _banner = MutableStateFlow(BannerState())
    val banner: StateFlow<BannerState> = _banner.asStateFlow()

    val playback: StateFlow<PlaybackState> get() = container.player.state

    private var channelsJob: Job? = null
    private var importJob: Job? = null
    private var programsJob: Job? = null

    /**
     * When the user last changed a filter rule, so the time until the guide has the new channel
     * list can be logged against the spec's 100 ms target. Zero when nothing is being timed.
     */
    private var rulesChangedAt = 0L

    /**
     * Reads the loaded window. The navigator asks for a row's programmes while moving the cursor,
     * so this must be a cheap in-memory lookup, never a query.
     */
    private val guideSource = object : GuideSource {
        override val channelCount: Int get() = _guide.value.channels.size

        override fun programsFor(channelIndex: Int, window: TimeWindow): List<ProgramSlot> {
            val state = _guide.value
            val channel = state.channels.getOrNull(channelIndex) ?: return emptyList()
            return state.programsByKey[channel.channelKey].orEmpty()
        }
    }

    private val navigator = GuideNavigator(guideSource, visibleRows = VISIBLE_ROWS)

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                credentialsEncrypted = container.credentials.isEncrypted,
            )
            observeSettings()
            restoreSession()
        }
    }

    // ------------------------------------------------------------------ startup

    private fun observeSettings() {
        viewModelScope.launch {
            container.settings.settings.collectLatest { settings ->
                val previous = _ui.value.settings
                _ui.value = _ui.value.copy(settings = settings)
                if (previous.rules != settings.rules) restartChannelObservation(settings.rules)
            }
        }
    }

    private var rawCategories: List<CategoryEntity> = emptyList()

    private fun updateLiveCategories() {
        val channels = _guide.value.allChannels
        val channelCounts = channels.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
        val allowedCountries = setOf("US", "UK", "JP", "KR")

        val filtered = rawCategories.filter { cat ->
            // 1. Exclude streaming services (Netflix, Paramount+, HBO Max, Disney+, etc.)
            if (StreamingServiceDetector.isStreamingService(cat.name)) return@filter false

            // 2. Exclude foreign countries
            if (cat.foreignMarker != null) return@filter false
            val tokens = Tokenizer.tokenize(cat.name)
            if (ForeignCountries.detect(tokens) != null) return@filter false

            // 3. Must have at least 1 kept channel
            val count = channelCounts[cat.categoryId] ?: 0
            if (count == 0) return@filter false

            // 4. If category has a prefix before '|', validate it strictly
            val pipeIdx = cat.name.indexOf('|')
            if (pipeIdx > 0) {
                val prefix = cat.name.substring(0, pipeIdx).trim()
                val prefixTokens = Tokenizer.tokenize(prefix)
                val prefixCountry = CountryDetector.detect(prefixTokens, prefixOnly = true)?.country
                if (prefixCountry != null && prefixCountry.code in allowedCountries) {
                    return@filter true
                }
                if (prefix.equals("NA", ignoreCase = true) || prefix.startsWith("24/7", ignoreCase = true)) {
                    return@filter true
                }
                // Any other prefix before '|' is rejected
                return@filter false
            }

            // 5. Must belong to US, UK, Japan, or Korea
            val hasExplicitAllowedCountry = cat.country in allowedCountries ||
                CountryDetector.detect(tokens, prefixOnly = true)?.country != null ||
                CountryDetector.detect(tokens, prefixOnly = false)?.country != null

            if (hasExplicitAllowedCountry) return@filter true

            // If no explicit country marker in category name (e.g. 24/7 channels), ensure all channels inside belong to allowed countries
            val categoryChannels = channels.filter { it.categoryId == cat.categoryId }
            categoryChannels.isNotEmpty() && categoryChannels.all { it.country?.code in allowedCountries }
        }

        _ui.value = _ui.value.copy(
            liveCategories = filtered,
            categoryChannelCounts = channelCounts,
        )
    }

    private fun observeLiveCategories() {
        viewModelScope.launch {
            container.database.categoryDao().observeAll().collectLatest { cats ->
                rawCategories = cats
                updateLiveCategories()
            }
        }
        viewModelScope.launch {
            container.guideRepository.observeFavoriteCategoryIds().collectLatest { favIds ->
                _ui.value = _ui.value.copy(favoriteCategoryIds = favIds)
            }
        }
    }

    private suspend fun restoreSession() {
        val account = container.credentials.load()
        if (account == null) {
            _ui.value = _ui.value.copy(screen = Screen.Login)
            return
        }
        _ui.value = _ui.value.copy(savedAccount = account)
        val settings = container.settings.settings.first()
        container.connect(account)
        observeLiveCategories()
        if (settings.hasCompletedImport && container.guideRepository.channelCount() > 0) {
            restartChannelObservation(settings.rules)
            _ui.value = _ui.value.copy(screen = Screen.Home)
        } else {
            runImport(fullRefresh = true)
        }
    }

    // ------------------------------------------------------------------ login

    fun signIn(serverUrl: String, username: String, password: String) {
        if (_ui.value.isSigningIn) return
        _ui.value = _ui.value.copy(isSigningIn = true, loginError = null)
        viewModelScope.launch {
            val account = XtreamAccount(serverUrl.trim(), username.trim(), password)
            val result = withContext(Dispatchers.IO) {
                runCatching { container.connect(account).login() }
            }
            result.fold(
                onSuccess = { info ->
                    container.credentials.save(account)
                    container.settings.setStreamFormat(info.preferredFormat)
                    observeLiveCategories()
                    _ui.value = _ui.value.copy(
                        isSigningIn = false,
                        loginError = null,
                        savedAccount = account,
                        allowedFormats = info.allowedOutputFormats,
                        accountExpiry = info.expiryEpochSeconds?.times(1000L),
                        maxConnections = info.maxConnections,
                    )
                    runImport(fullRefresh = true)
                },
                onFailure = { error ->
                    container.disconnect()
                    _ui.value = _ui.value.copy(
                        isSigningIn = false,
                        screen = Screen.Login,
                        savedAccount = account,
                        loginError = describe(error),
                    )
                },
            )
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is XtreamError.BadCredentials ->
            error.serverMessage?.takeIf { it.isNotBlank() }
                ?: "That username or password was not accepted by the server."
        is XtreamError.Expired -> "This account has expired."
        is XtreamError.Unreachable ->
            "Could not reach the server. Check the address and your connection."
        is XtreamError.NotXtream -> "That address did not answer like an Xtream panel."
        else -> error.message ?: "Something went wrong signing in."
    }

    fun signOut() {
        container.player.stop()
        container.credentials.clear()
        container.disconnect()
        channelsJob?.cancel()
        importJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.database.clearAllTables()
            }
            container.settings.clear()
            _guide.value = GuideState()
            _ui.value = UiState(screen = Screen.Login)
        }
    }

    // ------------------------------------------------------------------ import

    fun runImport(fullRefresh: Boolean) {
        val importer = container.channelImporter() ?: return
        importJob?.cancel()
        _ui.value = _ui.value.copy(screen = Screen.Importing, importMessage = "Reading categories…")
        importJob = viewModelScope.launch {
            val rules = container.settings.settings.first().rules
            runCatching {
                importer.import(rules, onlyNewCategories = !fullRefresh).collect { progress ->
                    when (progress) {
                        is ImportProgress.ReadingCategories ->
                            _ui.value = _ui.value.copy(importMessage = "Reading categories…")

                        is ImportProgress.Scanning ->
                            _ui.value = _ui.value.copy(
                                importMessage = "Read ${progress.seen} channels, kept ${progress.kept}",
                                importDetail = progress.category,
                            )

                        is ImportProgress.Done ->
                            _ui.value = _ui.value.copy(
                                importMessage = "Kept ${progress.kept} of ${progress.seen} channels",
                                importDetail = null,
                            )
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "import failed", error)
                _ui.value = _ui.value.copy(
                    screen = Screen.Login,
                    loginError = describe(error),
                )
                return@launch
            }

            // Guide data next. The channel list is already usable, so a slow EPG does not block
            // the user from watching television.
            _ui.value = _ui.value.copy(importMessage = "Loading the guide…", importDetail = null)
            restartChannelObservation(rules)
            _ui.value = _ui.value.copy(screen = Screen.Home)

            runCatching {
                val offset = container.settings.settings.first().epgOffsetHours
                container.xmltvImporter()?.import(manualOffsetHours = offset)?.collect { progress ->
                    if (progress is EpgProgress.Done) {
                        Log.i(TAG, "guide: kept ${progress.kept} of ${progress.seen} programmes")
                    }
                }
                container.settings.setEpgRefreshedAt(System.currentTimeMillis())
                container.settings.setImportCompleted(true)
            }.onFailure { Log.w(TAG, "guide data failed to load", it) }

            refreshWindow()
        }
    }

    fun refreshEpgNow() {
        viewModelScope.launch {
            runCatching {
                val offset = container.settings.settings.first().epgOffsetHours
                container.xmltvImporter()?.import(manualOffsetHours = offset)?.collect { }
                container.settings.setEpgRefreshedAt(System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "manual guide refresh failed", it) }
            refreshWindow()
        }
    }

    // ------------------------------------------------------------------ channels

    private fun restartChannelObservation(rules: FilterRules) {
        channelsJob?.cancel()
        channelsJob = viewModelScope.launch {
            container.guideRepository.observeChannels(rules).collectLatest { allChannels ->
                val now = System.currentTimeMillis()
                if (rulesChangedAt != 0L) {
                    Log.i(TAG, "filter change applied in ${now - rulesChangedAt} ms: ${allChannels.size} channels")
                    rulesChangedAt = 0L
                }
                _ui.value = _ui.value.copy(
                    totalChannelCount = allChannels.size,
                    favoriteChannelCount = allChannels.count { it.isFavorite },
                )
                val catId = _guide.value.categoryId
                val filtered = when (catId) {
                    null, ALL_CHANNELS_ID -> allChannels
                    FAVORITE_CHANNELS_ID -> allChannels.filter { it.isFavorite }
                    else -> allChannels.filter { it.categoryId == catId }
                }
                val previousStreamId = _guide.value.selectedChannel?.streamId
                val index = filtered.indexOfFirst { it.streamId == previousStreamId }
                    .takeIf { it >= 0 } ?: 0
                _guide.value = _guide.value.copy(
                    allChannels = allChannels,
                    channels = filtered,
                    nowMs = now,
                    cursor = if (_guide.value.cursor.window.startMs == 0L) {
                        navigator.initial(now, index)
                    } else {
                        _guide.value.cursor.copy(
                            channelIndex = index.coerceAtMost((filtered.size - 1).coerceAtLeast(0))
                        )
                    },
                )
                updateLiveCategories()
                refreshWindow()
            }
        }
    }

    /** Loads programmes for the visible rows plus a buffer, and nothing else. */
    private fun refreshWindow() {
        programsJob?.cancel()
        programsJob = viewModelScope.launch {
            val state = _guide.value
            if (state.channels.isEmpty()) return@launch
            val first = (state.cursor.firstVisibleRow - ROW_BUFFER).coerceAtLeast(0)
            val last = (state.cursor.firstVisibleRow + VISIBLE_ROWS + ROW_BUFFER)
                .coerceAtMost(state.channels.size)
            val visible = state.channels.subList(first, last)

            val programs = container.guideRepository.programsFor(visible, state.cursor.window)

            // Replace rather than merge. Merging would let the map grow by a row's worth of
            // programmes every time the user scrolls past a channel, so an evening of browsing a
            // 400-channel guide would accumulate the whole catalogue in memory — exactly what the
            // windowed query exists to avoid. Only the visible rows plus the buffer are kept.
            _guide.value = _guide.value.copy(
                programsByKey = programs,
                nowMs = System.currentTimeMillis(),
            )
            _guide.value = _guide.value.copy(selected = navigator.selected(_guide.value.cursor))

            // Channels XMLTV had nothing for get their guide data on demand, once, for the rows
            // actually on screen.
            runCatching {
                val filled = container.shortEpgFetcher()?.fillGaps(visible.map { it.streamId }) ?: 0
                if (filled > 0) {
                    val again = container.guideRepository.programsFor(visible, _guide.value.cursor.window)
                    _guide.value = _guide.value.copy(programsByKey = again)
                }
            }.onFailure { Log.d(TAG, "short EPG fallback skipped", it) }
        }
    }

    /** Called once a minute so the clock and the "now" line stay honest. */
    fun tick() {
        _guide.value = _guide.value.copy(nowMs = System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ navigation

    fun openGuide() {
        val now = System.currentTimeMillis()
        val state = _guide.value
        val playingIndex = state.channels
            .indexOfFirst { it.streamId == playback.value.channel?.streamId }
            .takeIf { it >= 0 } ?: state.cursor.channelIndex
        _guide.value = state.copy(
            cursor = navigator.initial(now, playingIndex),
            nowMs = now,
            details = null,
        )
        _ui.value = _ui.value.copy(screen = Screen.Guide)
        refreshWindow()
    }

    fun closeGuide() {
        _guide.value = _guide.value.copy(details = null)
        _ui.value = _ui.value.copy(screen = Screen.Watching)
    }

    private var screenBeforeSettings: Screen = Screen.Home

    fun openHome() {
        _ui.value = _ui.value.copy(screen = Screen.Home)
    }

    fun openLiveCategories() {
        _ui.value = _ui.value.copy(screen = Screen.LiveCategories)
    }

    fun selectLiveCategory(categoryId: String?, categoryName: String) {
        val catId = categoryId ?: ALL_CHANNELS_ID
        val filtered = when (catId) {
            ALL_CHANNELS_ID -> _guide.value.allChannels
            FAVORITE_CHANNELS_ID -> _guide.value.allChannels.filter { it.isFavorite }
            else -> _guide.value.allChannels.filter { it.categoryId == catId }
        }
        val now = System.currentTimeMillis()
        _guide.value = _guide.value.copy(
            categoryId = catId,
            categoryName = categoryName.uppercase(),
            channels = filtered,
            cursor = navigator.initial(now, 0),
            nowMs = now,
            details = null,
        )
        refreshWindow()
        _ui.value = _ui.value.copy(screen = Screen.Guide)
    }

    fun openGuideDirect() {
        selectLiveCategory(ALL_CHANNELS_ID, "ALL CHANNELS")
    }

    fun openFavoritesDirect() {
        selectLiveCategory(FAVORITE_CHANNELS_ID, "FAVORITE CHANNELS")
    }

    fun toggleFavoriteCategory(categoryId: String) {
        viewModelScope.launch {
            container.guideRepository.toggleFavoriteCategory(categoryId)
        }
    }

    fun toggleFavoriteChannel(streamId: Long) {
        viewModelScope.launch {
            container.guideRepository.toggleFavoriteChannel(streamId)
        }
    }

    fun toggleFavoriteSelectedChannel() {
        val streamId = _guide.value.selectedChannel?.streamId
            ?: _banner.value.channel?.streamId
            ?: return
        toggleFavoriteChannel(streamId)
    }

    fun watchingBack() {
        _ui.value = _ui.value.copy(screen = Screen.Home)
    }

    fun liveCategoriesBack() {
        _ui.value = _ui.value.copy(screen = Screen.Home)
    }

    fun openSettings() {
        screenBeforeSettings = _ui.value.screen
        _ui.value = _ui.value.copy(screen = Screen.Settings)
    }

    fun closeSettings() {
        _ui.value = _ui.value.copy(screen = screenBeforeSettings)
    }

    fun guideUp() = moveCursor(navigator.moveUp(_guide.value.cursor))
    fun guideDown() = moveCursor(navigator.moveDown(_guide.value.cursor))
    fun guideLeft() = moveCursor(navigator.moveLeft(_guide.value.cursor, _guide.value.nowMs))
    fun guideRight() = moveCursor(navigator.moveRight(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageBack() = moveCursor(navigator.pageBack(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageForward() = moveCursor(navigator.pageForward(_guide.value.cursor))

    private fun moveCursor(next: GuideCursor) {
        // The details dialog is modal: the cursor stays where it is until Back closes it.
        if (_guide.value.details != null) return
        val previous = _guide.value.cursor
        if (next == previous) return
        _guide.value = _guide.value.copy(cursor = next, selected = navigator.selected(next))
        if (next.window != previous.window || next.firstVisibleRow != previous.firstVisibleRow) {
            refreshWindow()
        }
    }

    /**
     * Select on the highlighted cell: tune when it is on now, open the details dialog when it is
     * in the future. A "No Information" filler is not a programme, so selecting one still tunes
     * the channel — which is what a viewer expects when the guide simply has no data.
     */
    fun guideSelect() {
        val state = _guide.value
        val channel = state.selectedChannel ?: return
        val slot = state.selected
        if (slot != null && navigator.isFuture(slot, state.nowMs) && !slot.isFiller) {
            _guide.value = state.copy(details = slot)
            return
        }
        tuneTo(channel)
        closeGuide()
    }

    fun dismissDetails() {
        _guide.value = _guide.value.copy(details = null)
    }

    /** Back in the guide: close the details dialog if one is open, otherwise leave the guide. */
    fun guideBack() {
        if (_guide.value.details != null) {
            dismissDetails()
        } else if (playback.value.channel != null) {
            closeGuide()
        } else {
            _ui.value = _ui.value.copy(screen = Screen.LiveCategories)
        }
    }

    // ------------------------------------------------------------------ VOD & Series

    fun openVod() {
        _ui.value = _ui.value.copy(screen = Screen.VodCategories)
        if (_ui.value.vodCategories.isEmpty()) {
            loadVodCategories()
        }
    }

    private fun loadVodCategories() {
        val client = container.client ?: return
        viewModelScope.launch {
            val categories = withContext(Dispatchers.IO) {
                runCatching { client.vodCategories() }.getOrElse { emptyList() }
            }
            _ui.value = _ui.value.copy(vodCategories = categories)
            if (categories.isNotEmpty() && _ui.value.selectedVodCategory == null) {
                selectVodCategory(categories.first())
            }
        }
    }

    fun selectVodCategory(category: RawCategory) {
        val client = container.client ?: return
        _ui.value = _ui.value.copy(
            selectedVodCategory = category,
            isLoadingVodMovies = true,
            vodMovies = emptyList(),
            selectedVodInfo = null,
        )
        viewModelScope.launch {
            val movies = withContext(Dispatchers.IO) {
                runCatching { client.vodStreams(category.categoryId) }.getOrElse { emptyList() }
            }
            _ui.value = _ui.value.copy(
                vodMovies = movies,
                isLoadingVodMovies = false,
            )
        }
    }

    fun selectVodMovie(movie: RawVodStream) {
        val client = container.client ?: return
        _ui.value = _ui.value.copy(
            isLoadingVodInfo = true,
            selectedVodInfo = null,
        )
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { client.vodInfo(movie.streamId) }.getOrNull()
            }
            _ui.value = _ui.value.copy(
                selectedVodInfo = info,
                isLoadingVodInfo = false,
            )
        }
    }

    fun playVod(streamId: Long, extension: String?) {
        val client = container.client ?: return
        val ext = extension ?: "mp4"
        val url = client.vodStreamUrl(streamId, ext)
        val movie = _ui.value.vodMovies.firstOrNull { it.streamId == streamId }
        val title = movie?.name ?: "VOD Movie"
        val coverUrl = movie?.streamIcon ?: _ui.value.selectedVodInfo?.coverUrl
        val plot = _ui.value.selectedVodInfo?.description.orEmpty()

        container.player.play(
            PlayableChannel(
                streamId = streamId,
                number = 0,
                name = title,
                url = url,
            )
        )
        val now = System.currentTimeMillis()
        _banner.value = BannerState(
            channel = GuideChannel(
                streamId = streamId,
                number = 0,
                name = title,
                shortName = title,
                channelKey = "vod_$streamId",
                logoUrl = coverUrl,
                country = null,
                market = null,
                categoryId = null,
                isFavorite = false,
            ),
            now = ProgramSlot(
                id = streamId,
                startMs = now,
                endMs = now + 7_200_000L,
                title = title,
                description = plot,
            ),
            showToken = now,
        )
        _ui.value = _ui.value.copy(
            selectedVodInfo = null,
            screen = Screen.Watching,
        )
    }

    fun dismissVodDialog() {
        _ui.value = _ui.value.copy(selectedVodInfo = null)
    }

    fun vodBack() {
        if (_ui.value.selectedVodInfo != null) {
            dismissVodDialog()
        } else {
            _ui.value = _ui.value.copy(screen = Screen.Home)
        }
    }

    fun openSeries() {
        _ui.value = _ui.value.copy(screen = Screen.SeriesCategories)
        if (_ui.value.seriesCategories.isEmpty()) {
            loadSeriesCategories()
        }
    }

    private fun loadSeriesCategories() {
        val client = container.client ?: return
        viewModelScope.launch {
            val categories = withContext(Dispatchers.IO) {
                runCatching { client.seriesCategories() }.getOrElse { emptyList() }
            }
            _ui.value = _ui.value.copy(seriesCategories = categories)
            if (categories.isNotEmpty() && _ui.value.selectedSeriesCategory == null) {
                selectSeriesCategory(categories.first())
            }
        }
    }

    fun selectSeriesCategory(category: RawCategory) {
        val client = container.client ?: return
        _ui.value = _ui.value.copy(
            selectedSeriesCategory = category,
            isLoadingSeries = true,
            seriesList = emptyList(),
            selectedSeriesInfo = null,
        )
        viewModelScope.launch {
            val seriesList = withContext(Dispatchers.IO) {
                runCatching { client.series(category.categoryId) }.getOrElse { emptyList() }
            }
            _ui.value = _ui.value.copy(
                seriesList = seriesList,
                isLoadingSeries = false,
            )
        }
    }

    fun selectSeries(series: RawSeries) {
        val client = container.client ?: return
        _ui.value = _ui.value.copy(
            isLoadingSeriesInfo = true,
            selectedSeriesInfo = null,
        )
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { client.seriesInfo(series.seriesId) }.getOrNull()
            }
            _ui.value = _ui.value.copy(
                selectedSeriesInfo = info,
                isLoadingSeriesInfo = false,
            )
        }
    }

    fun playEpisode(episodeId: Long, extension: String?) {
        val client = container.client ?: return
        val ext = extension ?: "mkv"
        val url = client.seriesStreamUrl(episodeId, ext)

        var episodeTitle = "Episode"
        var episodePlot = ""
        val info = _ui.value.selectedSeriesInfo
        if (info != null) {
            for ((_, episodes) in info.episodes) {
                val ep = episodes.firstOrNull { it.id == episodeId }
                if (ep != null) {
                    episodeTitle = ep.title.ifBlank { "Episode ${ep.episodeNum}" }
                    episodePlot = ep.info.orEmpty()
                    break
                }
            }
        }
        val seriesTitle = info?.name ?: "TV Series"
        val fullTitle = "$seriesTitle: $episodeTitle"
        val coverUrl = info?.cover

        container.player.play(
            PlayableChannel(
                streamId = episodeId,
                number = 0,
                name = fullTitle,
                url = url,
            )
        )
        val now = System.currentTimeMillis()
        _banner.value = BannerState(
            channel = GuideChannel(
                streamId = episodeId,
                number = 0,
                name = fullTitle,
                shortName = fullTitle,
                channelKey = "series_$episodeId",
                logoUrl = coverUrl,
                country = null,
                market = null,
                categoryId = null,
                isFavorite = false,
            ),
            now = ProgramSlot(
                id = episodeId,
                startMs = now,
                endMs = now + 3_600_000L,
                title = fullTitle,
                description = episodePlot,
            ),
            showToken = now,
        )
        _ui.value = _ui.value.copy(
            selectedSeriesInfo = null,
            screen = Screen.Watching,
        )
    }

    fun dismissSeriesDialog() {
        _ui.value = _ui.value.copy(selectedSeriesInfo = null)
    }

    fun seriesBack() {
        if (_ui.value.selectedSeriesInfo != null) {
            dismissSeriesDialog()
        } else {
            _ui.value = _ui.value.copy(screen = Screen.Home)
        }
    }

    // ------------------------------------------------------------------ playback

    fun channelUp() = stepChannel(+1)
    fun channelDown() = stepChannel(-1)

    private fun stepChannel(delta: Int) {
        val channels = _guide.value.channels
        if (channels.isEmpty()) return
        val currentId = playback.value.channel?.streamId
        val index = channels.indexOfFirst { it.streamId == currentId }.takeIf { it >= 0 } ?: 0
        val next = Math.floorMod(index + delta, channels.size)
        tuneTo(channels[next])
    }

    /** Play/Pause on the Fire remote jumps back to the previous channel. */
    fun lastChannel() {
        viewModelScope.launch {
            val previous = container.settings.settings.first().previousChannelStreamId
            if (previous == 0L) return@launch
            _guide.value.channels.firstOrNull { it.streamId == previous }?.let(::tuneTo)
        }
    }

    fun tuneTo(channel: GuideChannel) {
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            val client = container.client ?: return@launch
            container.player.play(
                PlayableChannel(
                    streamId = channel.streamId,
                    number = channel.number,
                    name = channel.name,
                    url = client.streamUrl(channel.streamId, settings.streamFormat),
                )
            )
            container.settings.setCurrentChannel(channel.streamId)

            // The banner goes up immediately with what is known, then fills in now-and-next when
            // the query returns. Waiting for the database first would make every channel change
            // feel slower than it is.
            _banner.value = BannerState(
                channel = channel,
                showToken = System.currentTimeMillis(),
            )
            val (now, next) = container.guideRepository.nowAndNext(
                channel.channelKey,
                System.currentTimeMillis(),
            )
            if (_banner.value.channel?.streamId == channel.streamId) {
                _banner.value = _banner.value.copy(now = now, next = next)
            }
        }
    }

    private suspend fun resumeLastChannel() {
        val settings = container.settings.settings.first()
        val channels = _guide.value.channels.ifEmpty {
            container.guideRepository.channels(settings.rules).also { loaded ->
                _guide.value = _guide.value.copy(channels = loaded)
            }
        }
        if (channels.isEmpty()) return
        val channel = channels.firstOrNull { it.streamId == settings.lastChannelStreamId }
            ?: channels.first()
        tuneTo(channel)
    }

    fun retryPlayback() = container.player.retry()

    /**
     * Select while watching. It opens the guide, except when playback has given up, where the
     * obvious thing for the button under the viewer's thumb to do is try again.
     */
    fun watchingSelect() {
        if (playback.value.error != null) retryPlayback() else openGuide()
    }

    // ------------------------------------------------------------------ settings

    /**
     * Applies a new country set.
     *
     * Removing one is instant: the rules change, the database query re-runs, the guide redraws,
     * and no network call is made. Adding one needs the categories for that country, which the
     * importer fetches on its own — every other category is already marked imported and is skipped.
     */
    fun setCountries(countries: Set<Country>) {
        rulesChangedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val before = container.settings.settings.first().rules.countries
            container.settings.setCountries(countries)
            val added = countries - before
            if (added.isNotEmpty()) fetchNewCategories()
        }
    }

    fun setMarkets(markets: Set<Market>) {
        rulesChangedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val before = container.settings.settings.first().rules.markets
            container.settings.setMarkets(markets)
            val added = markets - before
            if (added.isNotEmpty()) fetchNewCategories()
        }
    }

    fun setExclusions(keywords: List<String>) {
        rulesChangedAt = System.currentTimeMillis()
        viewModelScope.launch { container.settings.setExclusions(keywords) }
    }

    fun setEpgOffsetHours(hours: Int) {
        viewModelScope.launch {
            container.settings.setEpgOffsetHours(hours)
            refreshEpgNow()
        }
    }

    fun setStreamFormat(format: String) {
        viewModelScope.launch { container.settings.setStreamFormat(format) }
    }

    private fun fetchNewCategories() {
        val importer = container.channelImporter() ?: return
        _ui.value = _ui.value.copy(isFetchingMore = true)
        viewModelScope.launch {
            val rules = container.settings.settings.first().rules
            runCatching {
                importer.import(rules, onlyNewCategories = true).collect { }
            }.onFailure { Log.w(TAG, "fetching new categories failed", it) }
            _ui.value = _ui.value.copy(isFetchingMore = false)
            refreshWindow()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The player belongs to the container, not to this view model: a configuration change
        // must not tear down the stream.
    }

    companion object {
        private const val TAG = "RetroGuideVM"

        /** Channel rows drawn at once. Mirrors GuideTheme.rowsVisible. */
        const val VISIBLE_ROWS = 5

        /** Extra rows loaded either side, so scrolling never waits on a query. */
        const val ROW_BUFFER = 4
    }
}

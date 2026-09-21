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

/** Which screen is in front. */
enum class Screen { Starting, Login, Importing, Watching, Guide, Settings }

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
    val programsByKey: Map<String, List<ProgramSlot>> = emptyMap(),
    val cursor: GuideCursor = GuideCursor(0, 0L, TimeWindow(0L), 0),
    val nowMs: Long = 0L,
    /** The programme under the highlight. */
    val selected: ProgramSlot? = null,
    /** Non-null while the future-programme details dialog is open. */
    val details: ProgramSlot? = null,
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

    private suspend fun restoreSession() {
        val account = container.credentials.load()
        if (account == null) {
            _ui.value = _ui.value.copy(screen = Screen.Login)
            return
        }
        val settings = container.settings.settings.first()
        container.connect(account)
        if (settings.hasCompletedImport && container.guideRepository.channelCount() > 0) {
            restartChannelObservation(settings.rules)
            _ui.value = _ui.value.copy(screen = Screen.Watching)
            resumeLastChannel()
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
                    _ui.value = _ui.value.copy(
                        isSigningIn = false,
                        loginError = null,
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
            _ui.value = _ui.value.copy(screen = Screen.Watching)
            resumeLastChannel()

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
            container.guideRepository.observeChannels(rules).collectLatest { channels ->
                val now = System.currentTimeMillis()
                val previousStreamId = _guide.value.selectedChannel?.streamId
                val index = channels.indexOfFirst { it.streamId == previousStreamId }
                    .takeIf { it >= 0 } ?: 0
                _guide.value = _guide.value.copy(
                    channels = channels,
                    nowMs = now,
                    cursor = if (_guide.value.cursor.window.startMs == 0L) {
                        navigator.initial(now, index)
                    } else {
                        _guide.value.cursor.copy(channelIndex = index)
                    },
                )
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
            _guide.value = _guide.value.copy(
                programsByKey = _guide.value.programsByKey + programs,
                nowMs = System.currentTimeMillis(),
            )
            _guide.value = _guide.value.copy(selected = navigator.selected(_guide.value.cursor))

            // Channels XMLTV had nothing for get their guide data on demand, once, for the rows
            // actually on screen.
            runCatching {
                val filled = container.shortEpgFetcher()?.fillGaps(visible.map { it.streamId }) ?: 0
                if (filled > 0) {
                    val again = container.guideRepository.programsFor(visible, _guide.value.cursor.window)
                    _guide.value = _guide.value.copy(programsByKey = _guide.value.programsByKey + again)
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

    fun openSettings() {
        _ui.value = _ui.value.copy(screen = Screen.Settings)
    }

    fun closeSettings() {
        _ui.value = _ui.value.copy(
            screen = if (_guide.value.channels.isEmpty()) Screen.Watching else Screen.Guide
        )
    }

    fun guideUp() = moveCursor(navigator.moveUp(_guide.value.cursor))
    fun guideDown() = moveCursor(navigator.moveDown(_guide.value.cursor))
    fun guideLeft() = moveCursor(navigator.moveLeft(_guide.value.cursor, _guide.value.nowMs))
    fun guideRight() = moveCursor(navigator.moveRight(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageBack() = moveCursor(navigator.pageBack(_guide.value.cursor, _guide.value.nowMs))
    fun guidePageForward() = moveCursor(navigator.pageForward(_guide.value.cursor))

    private fun moveCursor(next: GuideCursor) {
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
        if (_guide.value.details != null) dismissDetails() else closeGuide()
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
        viewModelScope.launch {
            val before = container.settings.settings.first().rules.countries
            container.settings.setCountries(countries)
            val added = countries - before
            if (added.isNotEmpty()) fetchNewCategories()
        }
    }

    fun setMarkets(markets: Set<Market>) {
        viewModelScope.launch {
            val before = container.settings.settings.first().rules.markets
            container.settings.setMarkets(markets)
            val added = markets - before
            if (added.isNotEmpty()) fetchNewCategories()
        }
    }

    fun setExclusions(keywords: List<String>) {
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

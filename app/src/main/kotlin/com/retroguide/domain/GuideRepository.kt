package com.retroguide.domain

import com.retroguide.core.epg.ProgramCategory
import com.retroguide.core.filter.StreamingServiceDetector
import com.retroguide.core.guide.GuideGeometry
import com.retroguide.core.guide.ProgramSlot
import com.retroguide.core.guide.TimeWindow
import com.retroguide.core.model.Country
import com.retroguide.core.model.FilterRules
import com.retroguide.core.model.Market
import com.retroguide.core.text.Tokenizer
import com.retroguide.data.db.ChannelEntity
import com.retroguide.data.db.RetroGuideDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.retroguide.data.db.FavoriteChannelEntity
import com.retroguide.data.db.FavoriteCategoryEntity

/** A channel row as the guide draws it. */
data class GuideChannel(
    val streamId: Long,
    val number: Int,
    val name: String,
    val shortName: String,
    val channelKey: String,
    val logoUrl: String?,
    val country: Country?,
    val market: Market?,
    val categoryId: String? = null,
    val isFavorite: Boolean = false,
)

/**
 * Supplies the guide with channels and the programmes for a visible window.
 *
 * The important property is that *removing* a country or a market never touches the network. Every
 * channel that was ever imported stays in the database with its country and market recorded, so a
 * rule change is a different `WHERE` clause over a few hundred rows. Only *adding* a country needs
 * a fetch, and only for the categories belonging to it.
 */
class GuideRepository(private val db: RetroGuideDatabase) {

    /** The channel list for the current rules, as a flow so a rule change redraws the guide. */
    fun observeChannels(rules: FilterRules): Flow<List<GuideChannel>> =
        combine(
            db.channelDao().observeFiltered(
                countries = rules.countries.map(Country::code),
                markets = rules.markets.map(Market::name),
            ),
            db.favoritesDao().observeFavoriteChannelIds(),
        ) { rows, favIds ->
            val favSet = favIds.toSet()
            rows.filterNot { excluded(it, rules) }.map { toGuideChannel(it, it.streamId in favSet) }
        }

    suspend fun channels(rules: FilterRules): List<GuideChannel> = withContext(Dispatchers.IO) {
        val favSet = db.favoritesDao().getFavoriteChannelIds().toSet()
        db.channelDao()
            .getFiltered(rules.countries.map(Country::code), rules.markets.map(Market::name))
            .filterNot { excluded(it, rules) }
            .map { toGuideChannel(it, it.streamId in favSet) }
    }

    suspend fun toggleFavoriteChannel(streamId: Long) = withContext(Dispatchers.IO) {
        if (db.favoritesDao().isFavoriteChannel(streamId)) {
            db.favoritesDao().removeFavoriteChannel(streamId)
        } else {
            db.favoritesDao().addFavoriteChannel(FavoriteChannelEntity(streamId))
        }
    }

    suspend fun toggleFavoriteCategory(categoryId: String) = withContext(Dispatchers.IO) {
        if (db.favoritesDao().isFavoriteCategory(categoryId)) {
            db.favoritesDao().removeFavoriteCategory(categoryId)
        } else {
            db.favoritesDao().addFavoriteCategory(FavoriteCategoryEntity(categoryId))
        }
    }

    fun observeFavoriteCategoryIds(): Flow<Set<String>> =
        db.favoritesDao().observeFavoriteCategoryIds().map { it.toSet() }

    suspend fun getFavoriteCategoryIds(): Set<String> = withContext(Dispatchers.IO) {
        db.favoritesDao().getFavoriteCategoryIds().toSet()
    }

    /**
     * The free-text exclusion list is applied here rather than in SQL because it has to match
     * whole tokens. `LIKE '%art%'` would take out "Smart TV" and "Earth"; tokenising does not.
     * It runs over the few hundred rows the query already returned, so the cost is invisible.
     */
    private fun excluded(row: ChannelEntity, rules: FilterRules): Boolean {
        if (StreamingServiceDetector.isStreamingService(row.originalName)) return true
        if (StreamingServiceDetector.isStreamingService(row.displayName)) return true
        if (StreamingServiceDetector.isStreamingService(row.categoryName)) return true
        if (rules.excludePhrases.isEmpty()) return false
        val tokens = Tokenizer.tokenize(row.originalName)
        return rules.excludePhrases.any { tokens.hasPhrase(it) }
    }

    private fun toGuideChannel(row: ChannelEntity, isFavorite: Boolean = false) = GuideChannel(
        streamId = row.streamId,
        number = row.number,
        name = row.displayName,
        shortName = row.shortName,
        channelKey = row.channelKey,
        logoUrl = row.streamIcon,
        country = row.countryEnum,
        market = row.marketEnum,
        categoryId = row.categoryId,
        isFavorite = isFavorite,
    )

    /**
     * Programmes for the visible rows, already gap-filled.
     *
     * Only the channels currently on screen plus a buffer are asked for, and only the visible time
     * window plus a buffer. That is what keeps the guide's memory flat no matter how many channels
     * or days of data the database holds: the query returns tens of rows, not hundreds of
     * thousands.
     */
    suspend fun programsFor(
        channels: List<GuideChannel>,
        window: TimeWindow,
    ): Map<String, List<ProgramSlot>> = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext emptyMap()

        // The query window is widened so a programme that starts before the visible window still
        // comes back and can be drawn with its left notch.
        val from = window.startMs - WINDOW_BUFFER_MS
        val to = window.endMs + WINDOW_BUFFER_MS

        val keys = channels.map { it.channelKey }
        val rows = keys.chunked(SQLITE_VARIABLE_LIMIT).flatMap { chunk ->
            db.programDao().inWindow(chunk, from, to)
        }

        val byKey = rows.groupBy { it.channelKey }
        channels.associate { channel ->
            val slots = byKey[channel.channelKey].orEmpty().map { row ->
                ProgramSlot(
                    id = row.id,
                    startMs = row.startMs,
                    endMs = row.endMs,
                    title = row.title,
                    category = row.categoryEnum,
                    description = row.description,
                    rating = row.rating,
                )
            }
            // A channel with no data gets "No Information" blocks rather than an empty band, the
            // way a cable guide does.
            channel.channelKey to GuideGeometry.withFillers(slots, TimeWindow(from, to - from))
        }
    }

    suspend fun nowAndNext(channelKey: String, now: Long): Pair<ProgramSlot?, ProgramSlot?> =
        withContext(Dispatchers.IO) {
            val upcoming = db.programDao().upcoming(channelKey, now, 2)
            val current = upcoming.firstOrNull { it.startMs <= now && it.endMs > now }
            val next = upcoming.firstOrNull { it.startMs > now }
            fun map(row: com.retroguide.data.db.ProgramEntity?) = row?.let {
                ProgramSlot(
                    id = it.id,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    title = it.title,
                    category = it.categoryEnum,
                    description = it.description,
                    rating = it.rating,
                )
            }
            map(current) to map(next)
        }

    suspend fun channelCount(): Int = withContext(Dispatchers.IO) { db.channelDao().count() }

    suspend fun programCount(): Int = withContext(Dispatchers.IO) { db.programDao().count() }

    companion object {
        /**
         * How far either side of the visible window to load. One window's worth means a page
         * forward or back is already in memory and does not wait on a query.
         */
        const val WINDOW_BUFFER_MS = 2 * 60 * 60 * 1000L

        /**
         * SQLite's default limit on bound variables is 999. Room expands an `IN (:list)` into one
         * variable per element, so a long channel list has to be chunked or the query throws at
         * runtime on exactly the large catalogues this app is built for.
         */
        const val SQLITE_VARIABLE_LIMIT = 900
    }
}

/** The colour bucket for a slot, exposed so the theme can be applied without importing core. */
fun ProgramSlot.colourCategory(): ProgramCategory = category

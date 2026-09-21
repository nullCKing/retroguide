package com.retroguide.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.retroguide.core.epg.XmltvTime
import com.retroguide.core.model.Country
import com.retroguide.core.model.FilterRules
import com.retroguide.core.model.Market
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "retroguide")

/** Everything the settings screen can change, and the small amount of state the player keeps. */
data class AppSettings(
    val rules: FilterRules = FilterRules.DEFAULT,
    /** Manual correction for providers that publish wrong XMLTV offsets, in hours. */
    val epgOffsetHours: Int = 0,
    /** `ts` or `m3u8`. Defaults to whatever the provider listed first at login. */
    val streamFormat: String = "ts",
    val lastEpgRefreshAt: Long = 0L,
    val lastChannelStreamId: Long = 0L,
    val previousChannelStreamId: Long = 0L,
    val hasCompletedImport: Boolean = false,
)

/**
 * Settings, in DataStore.
 *
 * The filter rules live here rather than in the database because they are the user's intent, not
 * the provider's data: wiping and re-importing the catalogue must not silently re-enable a country
 * the user turned off.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            rules = FilterRules(
                countries = prefs[KEY_COUNTRIES]
                    ?.mapNotNull { Country.fromCode(it) }
                    ?.toSet()
                    ?: FilterRules.DEFAULT.countries,
                markets = prefs[KEY_MARKETS]
                    ?.mapNotNull { name -> runCatching { Market.valueOf(name) }.getOrNull() }
                    ?.toSet()
                    ?: FilterRules.DEFAULT.markets,
                excludeKeywords = prefs[KEY_EXCLUSIONS]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?: emptyList(),
            ),
            epgOffsetHours = prefs[KEY_EPG_OFFSET] ?: 0,
            streamFormat = prefs[KEY_FORMAT] ?: "ts",
            lastEpgRefreshAt = prefs[KEY_EPG_REFRESHED] ?: 0L,
            lastChannelStreamId = prefs[KEY_LAST_CHANNEL] ?: 0L,
            previousChannelStreamId = prefs[KEY_PREVIOUS_CHANNEL] ?: 0L,
            hasCompletedImport = prefs[KEY_IMPORTED] ?: false,
        )
    }

    suspend fun setCountries(countries: Set<Country>) = context.dataStore.edit {
        it[KEY_COUNTRIES] = countries.map(Country::code).toSet()
    }

    suspend fun setMarkets(markets: Set<Market>) = context.dataStore.edit {
        it[KEY_MARKETS] = markets.map(Market::name).toSet()
    }

    suspend fun setExclusions(keywords: List<String>) = context.dataStore.edit {
        it[KEY_EXCLUSIONS] = keywords.joinToString(",")
    }

    suspend fun setEpgOffsetHours(hours: Int) = context.dataStore.edit {
        it[KEY_EPG_OFFSET] = hours.coerceIn(
            XmltvTime.MANUAL_OFFSET_HOURS.first,
            XmltvTime.MANUAL_OFFSET_HOURS.last,
        )
    }

    suspend fun setStreamFormat(format: String) = context.dataStore.edit {
        it[KEY_FORMAT] = if (format == "m3u8") "m3u8" else "ts"
    }

    suspend fun setEpgRefreshedAt(at: Long) = context.dataStore.edit { it[KEY_EPG_REFRESHED] = at }

    /**
     * Records the channel being watched, keeping the one before it so Play/Pause can jump back.
     * Tuning to the channel already playing must not overwrite the previous one, or "last channel"
     * would become a no-op after any accidental re-tune.
     */
    suspend fun setCurrentChannel(streamId: Long) = context.dataStore.edit { prefs ->
        val current = prefs[KEY_LAST_CHANNEL] ?: 0L
        if (current != streamId) {
            if (current != 0L) prefs[KEY_PREVIOUS_CHANNEL] = current
            prefs[KEY_LAST_CHANNEL] = streamId
        }
    }

    suspend fun setImportCompleted(done: Boolean) = context.dataStore.edit { it[KEY_IMPORTED] = done }

    suspend fun clear() = context.dataStore.edit { it.clear() }

    private companion object {
        val KEY_COUNTRIES = stringSetPreferencesKey("filter_countries")
        val KEY_MARKETS = stringSetPreferencesKey("filter_markets")
        val KEY_EXCLUSIONS = stringPreferencesKey("filter_exclusions")
        val KEY_EPG_OFFSET = intPreferencesKey("epg_offset_hours")
        val KEY_FORMAT = stringPreferencesKey("stream_format")
        val KEY_EPG_REFRESHED = longPreferencesKey("epg_refreshed_at")
        val KEY_LAST_CHANNEL = longPreferencesKey("last_channel")
        val KEY_PREVIOUS_CHANNEL = longPreferencesKey("previous_channel")
        val KEY_IMPORTED = booleanPreferencesKey("import_completed")
    }
}

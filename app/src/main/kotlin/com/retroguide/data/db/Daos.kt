package com.retroguide.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    /**
     * Blocking counterpart, called from inside the streaming JSON parse.
     *
     * The parser hands over one channel at a time through a plain (non-suspending) callback, so
     * the only way to flush a full batch *during* a category — rather than after it — is a
     * blocking write. That matters for providers that put fifty thousand channels in a single
     * category: without it, peak memory would be a function of the largest category rather than
     * of the batch size. The caller is already on Dispatchers.IO.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAllBlocking(channels: List<ChannelEntity>)

    /**
     * The guide's channel list, already filtered by the current rules.
     *
     * This is the "instant filter change" path in the spec: removing a country or a market is a
     * different set of bind arguments against rows that are already on the device. There is no
     * network call and nothing is re-parsed, so the only cost is an indexed scan of a few hundred
     * rows — comfortably inside the 100 ms budget.
     *
     * A channel with no market is national and is kept whenever its country is allowed. A channel
     * with a market is a local and additionally needs that market to be allowed.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE country IN (:countries)
          AND (market IS NULL OR market IN (:markets))
        ORDER BY number ASC
        """
    )
    fun observeFiltered(countries: List<String>, markets: List<String>): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT * FROM channels
        WHERE country IN (:countries)
          AND (market IS NULL OR market IN (:markets))
        ORDER BY number ASC
        """
    )
    suspend fun getFiltered(countries: List<String>, markets: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE streamId = :streamId")
    suspend fun byStreamId(streamId: Long): ChannelEntity?

    @Query("SELECT * FROM channels ORDER BY number ASC")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Query("SELECT DISTINCT country FROM channels")
    suspend fun importedCountries(): List<String>

    /** Removes channels the provider no longer lists. Their numbers stay in `channel_numbers`. */
    @Query("DELETE FROM channels WHERE categoryId IN (:categoryIds) AND lastSeenAt < :before")
    suspend fun deleteStaleIn(categoryIds: List<String>, before: Long)

    @Query("DELETE FROM channels")
    suspend fun clear()
}

@Dao
interface ProgramDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(programs: List<ProgramEntity>)

    /**
     * Blocking counterpart, called from inside the XMLTV pull-parse.
     *
     * The parser walks the document in a plain loop and cannot suspend mid-element, so batches
     * are written blocking. The caller is already on Dispatchers.IO, and write-ahead logging
     * keeps the guide readable while this runs.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllBlocking(programs: List<ProgramEntity>)

    /**
     * Everything on these channels that overlaps the window.
     *
     * `startMs < :to AND endMs > :from` is an overlap test, not a containment test: a three-hour
     * film that began before the window opened still has to be drawn, with a notch on its left
     * edge. Asking for `startMs BETWEEN` would silently lose it.
     */
    @Query(
        """
        SELECT * FROM programs
        WHERE channelKey IN (:channelKeys)
          AND startMs < :to AND endMs > :from
        ORDER BY channelKey ASC, startMs ASC
        """
    )
    suspend fun inWindow(channelKeys: List<String>, from: Long, to: Long): List<ProgramEntity>

    @Query(
        """
        SELECT * FROM programs
        WHERE channelKey = :channelKey AND endMs > :now
        ORDER BY startMs ASC LIMIT :limit
        """
    )
    suspend fun upcoming(channelKey: String, now: Long, limit: Int): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE channelKey = :channelKey AND startMs <= :now AND endMs > :now LIMIT 1")
    suspend fun nowPlaying(channelKey: String, now: Long): ProgramEntity?

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun count(): Int

    /** Which of these channels already have guide data, so the short-EPG fallback can skip them. */
    @Query("SELECT DISTINCT channelKey FROM programs WHERE channelKey IN (:channelKeys)")
    suspend fun keysWithData(channelKeys: List<String>): List<String>

    @Query("DELETE FROM programs WHERE endMs < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM programs WHERE channelKey = :channelKey")
    suspend fun clearChannel(channelKey: String)

    @Query("DELETE FROM programs")
    suspend fun clear()
}

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE imported = 0")
    suspend fun notYetImported(): List<CategoryEntity>

    @Query("UPDATE categories SET imported = 1, lastImportedAt = :at WHERE categoryId = :categoryId")
    suspend fun markImported(categoryId: String, at: Long)

    @Query("DELETE FROM categories")
    suspend fun clear()
}

@Dao
interface ChannelNumberDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(numbers: List<ChannelNumberEntity>)

    /** Blocking counterpart, for flushing a batch from inside the streaming parse. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllBlocking(numbers: List<ChannelNumberEntity>)

    @Query("SELECT * FROM channel_numbers")
    suspend fun all(): List<ChannelNumberEntity>

    /** Blocking counterpart, for loading the ledger before a streaming import begins. */
    @Query("SELECT * FROM channel_numbers")
    fun allBlocking(): List<ChannelNumberEntity>

    @Query("SELECT MAX(number) FROM channel_numbers WHERE number BETWEEN :from AND :to")
    suspend fun highestIn(from: Int, to: Int): Int?

    @Query("DELETE FROM channel_numbers")
    suspend fun clear()
}

@Dao
interface FavoritesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavoriteChannel(favorite: FavoriteChannelEntity)

    @Query("DELETE FROM favorite_channels WHERE streamId = :streamId")
    suspend fun removeFavoriteChannel(streamId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_channels WHERE streamId = :streamId)")
    suspend fun isFavoriteChannel(streamId: Long): Boolean

    @Query("SELECT streamId FROM favorite_channels")
    fun observeFavoriteChannelIds(): Flow<List<Long>>

    @Query("SELECT streamId FROM favorite_channels")
    suspend fun getFavoriteChannelIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavoriteCategory(favorite: FavoriteCategoryEntity)

    @Query("DELETE FROM favorite_categories WHERE categoryId = :categoryId")
    suspend fun removeFavoriteCategory(categoryId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_categories WHERE categoryId = :categoryId)")
    suspend fun isFavoriteCategory(categoryId: String): Boolean

    @Query("SELECT categoryId FROM favorite_categories")
    fun observeFavoriteCategoryIds(): Flow<List<String>>

    @Query("SELECT categoryId FROM favorite_categories")
    suspend fun getFavoriteCategoryIds(): List<String>
}


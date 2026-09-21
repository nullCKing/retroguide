package com.retroguide.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChannelEntity::class,
        ProgramEntity::class,
        CategoryEntity::class,
        ChannelNumberEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RetroGuideDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun programDao(): ProgramDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelNumberDao(): ChannelNumberDao

    companion object {
        private const val NAME = "retroguide.db"

        @Volatile
        private var instance: RetroGuideDatabase? = null

        fun get(context: Context): RetroGuideDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): RetroGuideDatabase =
            Room.databaseBuilder(context, RetroGuideDatabase::class.java, NAME)
                // The database is a cache of the provider's data, rebuildable by re-importing.
                // On a schema change, throwing it away and re-importing is both simpler and
                // safer than migrating a table the user has no unique data in.
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Write-ahead logging keeps the guide readable while the EPG refresh is
                        // writing, which is the spec's "must stay usable during a refresh".
                        db.execSQL("PRAGMA journal_mode=WAL")
                        // A Fire Stick Lite has 1 GB of RAM for everything. The default 2 MB page
                        // cache is more than this workload needs; 1 MB keeps the footprint down
                        // without measurably hurting the windowed queries.
                        db.execSQL("PRAGMA cache_size=-1024")
                        db.execSQL("PRAGMA synchronous=NORMAL")
                    }
                })
                .build()
    }
}

package com.retroguide

import android.content.Context
import com.retroguide.data.db.RetroGuideDatabase
import com.retroguide.data.epg.ShortEpgFetcher
import com.retroguide.data.epg.XmltvImporter
import com.retroguide.data.settings.CredentialStore
import com.retroguide.data.settings.SettingsStore
import com.retroguide.data.xtream.ChannelImporter
import com.retroguide.data.xtream.XtreamAccount
import com.retroguide.data.xtream.XtreamClient
import com.retroguide.domain.GuideRepository
import com.retroguide.player.ExoPlayerController
import okhttp3.OkHttpClient

/**
 * Manual dependency wiring.
 *
 * A dependency-injection framework would earn its keep in a larger app; here it would be a build
 * plugin and a layer of generated code in exchange for constructing eight objects. The one thing
 * worth being careful about is the player: [player] is created once and shared, because a second
 * ExoPlayer would open a second stream and Xtream accounts are sold with a connection limit.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val httpClient: OkHttpClient = XtreamClient.defaultClient()

    val database: RetroGuideDatabase = RetroGuideDatabase.get(appContext)
    val settings = SettingsStore(appContext)
    val credentials = CredentialStore(appContext)
    val guideRepository = GuideRepository(database)

    /** The one and only player. */
    val player: ExoPlayerController by lazy { ExoPlayerController(appContext, httpClient) }

    /** Null until the user has signed in. */
    @Volatile
    var client: XtreamClient? = null
        private set

    fun connect(account: XtreamAccount): XtreamClient =
        XtreamClient(account, httpClient).also { client = it }

    fun disconnect() {
        client = null
    }

    fun channelImporter(): ChannelImporter? = client?.let { ChannelImporter(it, database) }

    fun xmltvImporter(): XmltvImporter? = client?.let { XmltvImporter(it, database) }

    fun shortEpgFetcher(): ShortEpgFetcher? = client?.let { ShortEpgFetcher(it, database) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}

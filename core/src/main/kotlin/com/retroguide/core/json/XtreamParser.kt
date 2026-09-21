package com.retroguide.core.json

import com.retroguide.core.model.RawCategory
import com.retroguide.core.model.RawChannel
import java.io.Reader

/** What `player_api.php` returns for a bare login. */
data class LoginInfo(
    val authenticated: Boolean,
    val status: String?,
    val message: String?,
    val expiryEpochSeconds: Long?,
    val maxConnections: Int,
    val allowedOutputFormats: List<String>,
    val serverUrl: String?,
    val serverPort: String?,
    val serverHttpsPort: String?,
    val serverProtocol: String?,
    val timezone: String?,
) {
    /** The format to default to: whatever the provider lists first, per the spec. */
    val preferredFormat: String
        get() = allowedOutputFormats.firstOrNull { it == "ts" || it == "m3u8" } ?: "ts"
}

/** One entry from `get_short_epg`, still base64 encoded. */
data class ShortEpgEntry(
    val titleEncoded: String,
    val descriptionEncoded: String,
    val startEpochSeconds: Long,
    val stopEpochSeconds: Long,
)

/**
 * Parsers for the Xtream API, written against [JsonReader] so a stream list is never materialised.
 *
 * The channel parser takes a callback rather than returning a list: the import filters each
 * channel as it is read and discards the overwhelming majority immediately, so nothing
 * accumulates. That is the difference between a few hundred kilobytes of peak usage and a hundred
 * megabytes.
 */
object XtreamParser {

    fun parseLogin(reader: Reader): LoginInfo {
        var auth = false
        var status: String? = null
        var message: String? = null
        var expiry: Long? = null
        var maxConnections = 1
        val formats = ArrayList<String>(2)
        var url: String? = null
        var port: String? = null
        var httpsPort: String? = null
        var protocol: String? = null
        var timezone: String? = null

        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) {
                return LoginInfo(false, null, "unexpected response", null, 1, emptyList(),
                    null, null, null, null, null)
            }
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "user_info" -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "auth" -> auth = json.nextBoolean()
                                "status" -> status = json.nextString()
                                "message" -> message = json.nextString()
                                "exp_date" -> expiry = json.nextString()?.toLongOrNull()
                                "max_connections" ->
                                    maxConnections = json.nextString()?.toIntOrNull() ?: 1
                                "allowed_output_formats" -> {
                                    if (json.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                        json.beginArray()
                                        while (json.hasNext()) {
                                            json.nextString()?.let { formats.add(it) }
                                        }
                                        json.endArray()
                                    } else {
                                        json.skipValue()
                                    }
                                }
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    }
                    "server_info" -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "url" -> url = json.nextString()
                                "port" -> port = json.nextString()
                                "https_port" -> httpsPort = json.nextString()
                                "server_protocol" -> protocol = json.nextString()
                                "timezone" -> timezone = json.nextString()
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }

        // A provider that reports `status: Active` without an explicit auth flag is authenticated.
        val effectiveAuth = auth || status.equals("Active", ignoreCase = true)
        return LoginInfo(
            authenticated = effectiveAuth,
            status = status,
            message = message,
            expiryEpochSeconds = expiry,
            maxConnections = maxConnections.coerceAtLeast(1),
            allowedOutputFormats = if (formats.isEmpty()) listOf("ts") else formats,
            serverUrl = url,
            serverPort = port,
            serverHttpsPort = httpsPort,
            serverProtocol = protocol,
            timezone = timezone,
        )
    }

    /** Categories are small — a few hundred entries — so a list is fine here. */
    fun parseCategories(reader: Reader): List<RawCategory> {
        val out = ArrayList<RawCategory>(64)
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_ARRAY) return emptyList()
            json.beginArray()
            while (json.hasNext()) {
                json.beginObject()
                var id: String? = null
                var name: String? = null
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "category_id" -> id = json.nextString()
                        "category_name" -> name = json.nextString()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (id != null && name != null) out.add(RawCategory(id, name))
            }
            json.endArray()
        }
        return out
    }

    /**
     * Streams the live stream list, invoking [onChannel] once per entry.
     *
     * Returns the number of channels seen, which is what the discovery report's "total on the
     * server" figure comes from. Nothing is retained between callbacks.
     */
    fun streamChannels(reader: Reader, onChannel: (RawChannel) -> Unit): Int {
        var seen = 0
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_ARRAY) return 0
            json.beginArray()
            while (json.hasNext()) {
                json.beginObject()
                var streamId = 0L
                var name: String? = null
                var categoryId: String? = null
                var epgId: String? = null
                var icon: String? = null
                var archive = 0
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "stream_id" -> streamId = json.nextLong()
                        "name" -> name = json.nextString()
                        "category_id" -> categoryId = json.nextString()
                        "epg_channel_id" -> epgId = json.nextString()
                        "stream_icon" -> icon = json.nextString()
                        "tv_archive" -> archive = json.nextInt()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                seen++
                if (streamId != 0L && !name.isNullOrBlank()) {
                    onChannel(RawChannel(streamId, name, categoryId, epgId, icon, archive))
                }
            }
            json.endArray()
        }
        return seen
    }

    fun parseShortEpg(reader: Reader): List<ShortEpgEntry> {
        val out = ArrayList<ShortEpgEntry>(8)
        JsonReader(reader).use { json ->
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyList()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "epg_listings") {
                    json.skipValue()
                    continue
                }
                if (json.peek() != JsonReader.Token.BEGIN_ARRAY) {
                    json.skipValue()
                    continue
                }
                json.beginArray()
                while (json.hasNext()) {
                    json.beginObject()
                    var title = ""
                    var desc = ""
                    var start = 0L
                    var stop = 0L
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "title" -> title = json.nextString().orEmpty()
                            "description" -> desc = json.nextString().orEmpty()
                            "start_timestamp" -> start = json.nextLong()
                            "stop_timestamp" -> stop = json.nextLong()
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                    if (stop > start) out.add(ShortEpgEntry(title, desc, start, stop))
                }
                json.endArray()
            }
            json.endObject()
        }
        return out
    }
}

package com.retroguide.data.epg

import android.util.Xml
import com.retroguide.core.epg.ProgramCategory
import com.retroguide.core.epg.ShortEpg
import com.retroguide.core.epg.XmltvTime
import com.retroguide.data.db.ProgramEntity
import com.retroguide.data.db.RetroGuideDatabase
import com.retroguide.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParser
import java.io.Reader

sealed interface EpgProgress {
    data object Downloading : EpgProgress
    data class Parsing(val kept: Int, val seen: Int) : EpgProgress
    data class Done(val kept: Int, val seen: Int) : EpgProgress
}

/**
 * Imports guide data from `xmltv.php`.
 *
 * A provider's XMLTV for a few thousand channels over several days is well over a hundred
 * megabytes — the mock server's is 187 MB with 542,046 programmes, deliberately, because that is
 * the case worth being able to survive. So this pulls through the document with an
 * `XmlPullParser`, holds one `<programme>` at a time, and writes survivors in batches.
 *
 * Two filters run while parsing, before anything is allocated for long:
 *
 *  - **Channel.** Only programmes whose `channel` attribute matches a channel that survived the
 *    import are kept. With four countries out of thirty that discards most of the document.
 *  - **Time.** Only a window from [PAST_WINDOW_MS] behind to [FUTURE_WINDOW_MS] ahead is kept.
 *    A provider publishing two weeks of guide data is not worth storing on a Fire Stick.
 */
class XmltvImporter(
    private val client: XtreamClient,
    private val db: RetroGuideDatabase,
) {

    fun import(manualOffsetHours: Int = 0, now: Long = System.currentTimeMillis()): Flow<EpgProgress> =
        flow {
            emit(EpgProgress.Downloading)

            val wanted = db.channelDao().all()
                .mapNotNull { it.epgChannelId?.takeIf(String::isNotBlank) }
                .toHashSet()
            if (wanted.isEmpty()) {
                emit(EpgProgress.Done(0, 0))
                return@flow
            }

            val from = now - PAST_WINDOW_MS
            val to = now + FUTURE_WINDOW_MS

            var kept = 0
            var seen = 0
            val batch = ArrayList<ProgramEntity>(BATCH_SIZE)

            client.openXmltv().use { reader ->
                parse(reader, wanted, from, to, manualOffsetHours * 60) { program ->
                    seen++
                    if (program != null) {
                        batch.add(program)
                        kept++
                        if (batch.size >= BATCH_SIZE) {
                            db.programDao().insertAllBlocking(batch)
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) db.programDao().insertAllBlocking(batch)

            // Old programmes are dropped on every refresh so the table does not grow without
            // bound over months of use.
            db.programDao().prune(from)

            emit(EpgProgress.Done(kept, seen))
        }.flowOn(Dispatchers.IO)

    /**
     * Pulls through the document, calling [onProgram] for every `<programme>` seen — with the
     * entity when it survives both filters, and with null when it does not, so the caller can
     * report progress over the whole document.
     */
    private suspend fun parse(
        reader: Reader,
        wantedChannels: Set<String>,
        from: Long,
        to: Long,
        fallbackOffsetMinutes: Int,
        onProgram: (ProgramEntity?) -> Unit,
    ) {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        var event = parser.eventType
        var checks = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                // Cancellation is checked every so often rather than every tag: half a million
                // iterations of a context lookup is measurable on a Stick.
                if (++checks % 512 == 0) currentCoroutineContext().ensureActive()
                onProgram(readProgramme(parser, wantedChannels, from, to, fallbackOffsetMinutes))
            }
            event = parser.next()
        }
    }

    /**
     * Reads one `<programme>`, returning null when it should be discarded.
     *
     * The channel and time checks happen from the attributes alone, before any child element is
     * read, so a programme on an unwanted channel costs nothing but a `skipTag`.
     */
    private fun readProgramme(
        parser: XmlPullParser,
        wantedChannels: Set<String>,
        from: Long,
        to: Long,
        fallbackOffsetMinutes: Int,
    ): ProgramEntity? {
        val channel = parser.getAttributeValue(null, "channel")
        val start = XmltvTime.parse(parser.getAttributeValue(null, "start"), fallbackOffsetMinutes)
        val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"), fallbackOffsetMinutes)

        val unwanted = channel == null || channel !in wantedChannels ||
            start == null || stop == null || stop <= start ||
            stop <= from || start >= to

        if (unwanted) {
            skipTag(parser)
            return null
        }

        var title = ""
        var description = ""
        var rating: String? = null
        val categories = ArrayList<String>(2)

        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                "desc" -> if (description.isEmpty()) description = parser.nextText().trim()
                "category" -> categories.add(parser.nextText().trim())
                "rating" -> rating = readRating(parser) ?: rating
                else -> skipTag(parser)
            }
        }

        return ProgramEntity(
            channelKey = channel,
            startMs = start,
            endMs = stop,
            title = title.ifEmpty { UNTITLED },
            description = description,
            category = ProgramCategory.fromXmltv(categories).name,
            rating = rating,
        )
    }

    /** `<rating><value>TV-14</value></rating>`. */
    private fun readRating(parser: XmlPullParser): String? {
        var value: String? = null
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "value") value = parser.nextText().trim() else skipTag(parser)
        }
        return value?.takeIf { it.isNotEmpty() }
    }

    /** Consumes the current element and everything inside it. */
    private fun skipTag(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        /** Two hours back, as the spec asks: enough that the guide can show what is on now. */
        const val PAST_WINDOW_MS = 2 * 60 * 60 * 1000L
        /** Seventy-two hours ahead. Beyond that the data is not worth a Fire Stick's storage. */
        const val FUTURE_WINDOW_MS = 72 * 60 * 60 * 1000L
        const val BATCH_SIZE = 500
        const val UNTITLED = "Untitled"
    }
}

/**
 * The per-channel fallback for channels XMLTV had nothing for.
 *
 * Fetched lazily and only for rows the guide is actually showing, because a provider with no
 * XMLTV at all would otherwise mean one HTTP request per channel at import time.
 */
class ShortEpgFetcher(
    private val client: XtreamClient,
    private val db: RetroGuideDatabase,
) {

    /** Fetches short EPG for any of [streamIds] that has no stored guide data. Returns how many. */
    suspend fun fillGaps(streamIds: List<Long>, limit: Int = 8): Int {
        if (streamIds.isEmpty()) return 0
        val channels = streamIds.mapNotNull { db.channelDao().byStreamId(it) }
        val haveData = db.programDao().keysWithData(channels.map { it.channelKey }).toSet()
        val missing = channels.filterNot { it.channelKey in haveData }
        var filled = 0

        for (channel in missing) {
            currentCoroutineContext().ensureActive()
            val entries = runCatching { client.shortEpg(channel.streamId, limit) }.getOrNull()
                ?: continue
            if (entries.isEmpty()) continue
            db.programDao().insertAll(
                entries.map { entry ->
                    ProgramEntity(
                        channelKey = channel.channelKey,
                        startMs = entry.startEpochSeconds * 1000L,
                        endMs = entry.stopEpochSeconds * 1000L,
                        // Titles and descriptions in this endpoint are base64; some providers
                        // send them in plain text anyway, which decodeField handles.
                        title = ShortEpg.decodeField(entry.titleEncoded)
                            .ifBlank { XmltvImporter.UNTITLED },
                        description = ShortEpg.decodeField(entry.descriptionEncoded),
                        category = ProgramCategory.SERIES_OTHER.name,
                        rating = null,
                    )
                }
            )
            filled++
        }
        return filled
    }
}

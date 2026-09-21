package com.retroguide.core.text

/**
 * Turns a provider's channel name into something that fits a cable-guide channel column.
 *
 * `US| ESPN 2 ᴴᴰ` and `|US| ESPN 2 FHD` and `US - ESPN 2 [4K]` all become `ESPN 2`.
 *
 * The original string is always kept in the database; this only produces the display form.
 */
object NameCleaner {

    /** Country prefixes the import strips once it has already recorded the country. */
    private val COUNTRY_PREFIX = setOf(
        "US", "USA", "UNITED", "STATES", "AMERICA",
        "UK", "GB", "GBR", "BRITAIN", "ENGLAND", "KINGDOM",
        "JP", "JPN", "JAPAN",
        "KR", "KOR", "KOREA", "SOUTH",
    )

    /** Quality, packaging and marketing tags that carry no information for the viewer. */
    private val QUALITY_TAGS = setOf(
        "HD", "SD", "FHD", "UHD", "4K", "8K", "HEVC", "H264", "H265", "X264", "X265",
        "VIP", "RAW", "MULTI", "BACKUP", "ALT", "PLUS", "HQ", "LQ", "HDR", "FULLHD",
        "1080", "1080P", "720", "720P", "480", "480P", "2160", "2160P", "60FPS", "50FPS",
        "AAC", "AC3", "TS", "M3U8", "DIRECT", "STABLE", "PREMIUM", "SLOW",
    )

    /**
     * Characters providers use as decoration around the real name. Stripped from both ends and
     * collapsed in the middle.
     */
    private const val DECORATION = "|:[]()<>{}▎│┃┆┇▌▐●•*~#_\\/"

    fun clean(rawName: String): String {
        if (rawName.isBlank()) return rawName

        // Fold the Unicode small-caps HD marks and full-width letters to ASCII first so the tag
        // list below can see them, then split on decoration and whitespace.
        val folded = foldDecorations(rawName)
        val pieces = folded.split(' ').filter { it.isNotBlank() }
        if (pieces.isEmpty()) return rawName.trim()

        val kept = ArrayList<String>(pieces.size)
        var stillInPrefix = true
        for (piece in pieces) {
            val bare = piece.trim { it in DECORATION || it == '.' || it == '-' }
            if (bare.isEmpty()) continue
            val upper = bare.uppercase()

            // Country tokens only count as a prefix while nothing real has been seen yet, so
            // `UK GOLD` loses the `UK` but `TV JAPAN` keeps `JAPAN`.
            if (stillInPrefix && upper in COUNTRY_PREFIX) continue
            if (upper in QUALITY_TAGS) continue

            stillInPrefix = false
            kept.add(bare)
        }

        val result = kept.joinToString(" ").trim()
        // If stripping removed everything the name was nothing but tags; fall back to the original.
        return result.ifEmpty { rawName.trim() }
    }

    /**
     * A short form for the guide's channel column, which has room for roughly ten characters.
     * Words are dropped from the end before any letters are cut.
     */
    fun shortName(cleanedName: String, maxChars: Int = 10): String {
        if (cleanedName.length <= maxChars) return cleanedName
        val words = cleanedName.split(' ')
        val sb = StringBuilder()
        for (w in words) {
            val candidate = if (sb.isEmpty()) w else "${sb} $w"
            if (candidate.length > maxChars) break
            sb.setLength(0)
            sb.append(candidate)
        }
        if (sb.isNotEmpty()) return sb.toString()
        return cleanedName.take(maxChars)
    }

    private fun foldDecorations(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val mapped = when {
                ch.code < 128 -> if (ch in DECORATION) ' ' else ch
                ch in 'Ａ'..'Ｚ' -> ch - 0xFEE0
                ch in 'ａ'..'ｚ' -> ch - 0xFEE0
                ch == 'ᴴ' -> 'H'
                ch == 'ᴰ' -> 'D'
                ch == 'ᵁ' -> 'U'
                ch == 'ᴷ' -> 'K'
                ch == 'ᴿ' -> 'R'
                ch == ' ' -> ' '
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }
}

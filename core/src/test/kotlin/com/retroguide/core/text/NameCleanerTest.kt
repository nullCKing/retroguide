package com.retroguide.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class NameCleanerTest {

    @Test
    fun `country prefixes are stripped`() {
        assertEquals("ESPN", NameCleaner.clean("US| ESPN"))
        assertEquals("ESPN", NameCleaner.clean("|US| ESPN"))
        assertEquals("ESPN", NameCleaner.clean("US: ESPN"))
        assertEquals("ESPN", NameCleaner.clean("USA - ESPN"))
        assertEquals("BBC One", NameCleaner.clean("UK| BBC One"))
        assertEquals("NHK G", NameCleaner.clean("JP ▎NHK G"))
        assertEquals("KBS 1", NameCleaner.clean("KR: KBS 1"))
    }

    @Test
    fun `quality tags are stripped wherever they appear`() {
        assertEquals("ESPN 2", NameCleaner.clean("US| ESPN 2 ᴴᴰ"))
        assertEquals("ESPN 2", NameCleaner.clean("US| ESPN 2 FHD"))
        assertEquals("ESPN 2", NameCleaner.clean("US - ESPN 2 [4K]"))
        assertEquals("ESPN 2", NameCleaner.clean("US VIP ESPN 2 HEVC"))
        assertEquals("Discovery", NameCleaner.clean("US| Discovery UHD"))
        assertEquals("AMC", NameCleaner.clean("US| AMC HD 1080p"))
    }

    @Test
    fun `a country word that is part of the real name survives`() {
        // The prefix stripper stops at the first token that is not a tag, so a trailing
        // "JAPAN" belongs to the channel and is kept.
        assertEquals("TV JAPAN", NameCleaner.clean("US| TV JAPAN"))
        assertEquals("America's Test Kitchen", NameCleaner.clean("US| America's Test Kitchen"))
    }

    @Test
    fun `a name made only of tags falls back to the original`() {
        assertEquals("US| HD", NameCleaner.clean("US| HD"))
    }

    @Test
    fun `short name drops whole words before cutting letters`() {
        assertEquals("ESPN", NameCleaner.shortName("ESPN"))
        assertEquals("Cartoon", NameCleaner.shortName("Cartoon Network"))
        assertEquals("Discovery", NameCleaner.shortName("Discovery Channel"))
        // A single word longer than the limit is cut.
        assertEquals("Supercalif", NameCleaner.shortName("Supercalifragilistic"))
    }
}

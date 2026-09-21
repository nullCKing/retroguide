package com.retroguide.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retroguide.core.epg.XmltvTime
import com.retroguide.core.model.Country
import com.retroguide.core.model.Market
import com.retroguide.ui.UiState
import com.retroguide.ui.theme.GuideTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings, reached with the MENU button.
 *
 * The filter rules are the point of this screen, and the asymmetry between removing and adding is
 * worth knowing while using it: unticking a country or a market is a database query and the guide
 * redraws immediately, while ticking one back on fetches the categories for it. The screen says so
 * rather than leaving the user to wonder why one direction is instant and the other is not.
 */
@Composable
fun SettingsScreen(
    ui: UiState,
    theme: GuideTheme,
    onCountries: (Set<Country>) -> Unit,
    onMarkets: (Set<Market>) -> Unit,
    onExclusions: (List<String>) -> Unit,
    onOffset: (Int) -> Unit,
    onFormat: (String) -> Unit,
    onRefreshEpg: () -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rules = ui.settings.rules

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = 64.dp, vertical = 32.dp)
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {

            item {
                Text(
                    text = "Settings",
                    color = theme.infoTitle,
                    fontSize = theme.titleSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = theme.titleWeight,
                )
                Spacer(Modifier.height(12.dp))
            }

            // ------------------------------------------------------------ countries
            item { SectionHeading("Countries", theme) }
            items(Country.entries.toList(), key = { it.code }) { country ->
                ToggleRow(
                    label = country.displayName,
                    checked = country in rules.countries,
                    theme = theme,
                    detail = if (country in rules.countries) null else "adding this fetches new categories",
                    onToggle = {
                        val next = if (country in rules.countries) {
                            rules.countries - country
                        } else {
                            rules.countries + country
                        }
                        // Never leave the guide with nothing in it.
                        if (next.isNotEmpty()) onCountries(next)
                    },
                )
            }

            // ------------------------------------------------------------ markets
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("US local markets", theme)
                Text(
                    text = "National US channels are always kept. These are the broadcast " +
                        "affiliates whose local feeds appear in the guide.",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
                Spacer(Modifier.height(6.dp))
            }
            items(Market.DEFAULT_ALLOWED.toList(), key = { it.name }) { market ->
                ToggleRow(
                    label = market.displayName,
                    checked = market in rules.markets,
                    theme = theme,
                    onToggle = {
                        val next = if (market in rules.markets) {
                            rules.markets - market
                        } else {
                            rules.markets + market
                        }
                        onMarkets(next)
                    },
                )
            }

            // ------------------------------------------------------------ exclusions
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Excluded keywords", theme)
                ExclusionRow(
                    keywords = rules.excludeKeywords,
                    theme = theme,
                    onChange = onExclusions,
                )
            }

            // ------------------------------------------------------------ guide data
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Guide data", theme)
                ActionRow(
                    label = "Refresh now",
                    detail = ui.settings.lastEpgRefreshAt
                        .takeIf { it > 0 }
                        ?.let { "Last refreshed ${formatTime(it)}" }
                        ?: "Never refreshed",
                    theme = theme,
                    onSelect = onRefreshEpg,
                )
                StepperRow(
                    label = "Guide time offset",
                    value = ui.settings.epgOffsetHours,
                    range = XmltvTime.MANUAL_OFFSET_HOURS,
                    theme = theme,
                    detail = "Shift programme times when the provider publishes the wrong time zone.",
                    onChange = onOffset,
                )
            }

            // ------------------------------------------------------------ stream format
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Stream format", theme)
                ToggleRow(
                    label = "Use HLS (.m3u8) instead of MPEG-TS (.ts)",
                    checked = ui.settings.streamFormat == "m3u8",
                    detail = "Your provider allows: ${ui.allowedFormats.joinToString(", ")}",
                    theme = theme,
                    onToggle = {
                        onFormat(if (ui.settings.streamFormat == "m3u8") "ts" else "m3u8")
                    },
                )
            }

            // ------------------------------------------------------------ account
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeading("Account", theme)
                Text(
                    text = buildString {
                        ui.accountExpiry?.let { appendLine("Expires ${formatTime(it)}") }
                        appendLine("Maximum connections: ${ui.maxConnections}")
                        append(
                            if (ui.credentialsEncrypted) {
                                "Credentials are encrypted on this device."
                            } else {
                                "Credentials are stored unencrypted: this device's keystore is unavailable."
                            }
                        )
                    },
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
                Spacer(Modifier.height(6.dp))
                ActionRow(
                    label = "Sign out and forget credentials",
                    detail = "Clears the channel list and guide data from this device",
                    theme = theme,
                    onSelect = onSignOut,
                )
            }

            // ------------------------------------------------------------ remote help
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeading("Remote", theme)
                Text(
                    text = "While watching: UP and DOWN change channel, SELECT opens the guide, " +
                        "PLAY/PAUSE jumps back to the last channel.\n" +
                        "In the guide: REWIND and FAST FORWARD page the time window by two hours, " +
                        "SELECT tunes or shows details, BACK returns to the picture.\n" +
                        "The Fire remote has no number pad, so there is no direct channel entry.",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Press BACK to close settings",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, theme: GuideTheme) {
    Text(
        text = text.uppercase(),
        color = theme.highlight,
        fontSize = theme.sectionSize,
        fontFamily = theme.fontFamily,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun RowShell(
    theme: GuideTheme,
    onSelect: () -> Unit,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) theme.highlight else theme.panel)
            .border(
                width = 1.dp,
                color = if (focused) theme.highlight else theme.panelEdge,
                shape = RoundedCornerShape(4.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onSelect(); true }
                    Key.DirectionLeft -> onLeft?.let { it(); true } ?: false
                    Key.DirectionRight -> onRight?.let { it(); true } ?: false
                    else -> false
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        content(focused)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    theme: GuideTheme,
    onToggle: () -> Unit,
    detail: String? = null,
) {
    RowShell(theme = theme, onSelect = onToggle) { focused ->
        val textColour = if (focused) theme.highlightText else theme.cellText
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (checked) "✓" else " ",
                color = if (focused) theme.highlightText else theme.highlight,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = label,
                    color = textColour,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                )
                detail?.let {
                    Text(
                        text = it,
                        color = if (focused) theme.highlightText else theme.infoDetail,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    detail: String?,
    theme: GuideTheme,
    onSelect: () -> Unit,
) {
    RowShell(theme = theme, onSelect = onSelect) { focused ->
        Column {
            Text(
                text = label,
                color = if (focused) theme.highlightText else theme.cellText,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
            )
            detail?.let {
                Text(
                    text = it,
                    color = if (focused) theme.highlightText else theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    theme: GuideTheme,
    detail: String,
    onChange: (Int) -> Unit,
) {
    RowShell(
        theme = theme,
        onSelect = { },
        onLeft = { if (value > range.first) onChange(value - 1) },
        onRight = { if (value < range.last) onChange(value + 1) },
    ) { focused ->
        Column {
            Text(
                text = "$label:  ${if (value >= 0) "+" else ""}$value hours",
                color = if (focused) theme.highlightText else theme.cellText,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
            )
            Text(
                text = "$detail  Use LEFT and RIGHT to adjust.",
                color = if (focused) theme.highlightText else theme.infoDetail,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
            )
        }
    }
}

/**
 * The free-text exclusion list.
 *
 * Editing text with a D-pad is miserable, so this cycles through a short set of common exclusions
 * rather than presenting a keyboard. Anything more specific is better typed on a phone, and the
 * list is stored as plain text in settings for exactly that reason.
 */
@Composable
private fun ExclusionRow(
    keywords: List<String>,
    theme: GuideTheme,
    onChange: (List<String>) -> Unit,
) {
    val suggestions = remember {
        listOf("PPV", "ADULT", "XXX", "24/7", "RADIO", "TEST")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (keywords.isEmpty()) {
                "None. A channel whose name contains an excluded word is hidden."
            } else {
                "Hiding channels containing: ${keywords.joinToString(", ")}"
            },
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
        suggestions.forEach { word ->
            ToggleRow(
                label = word,
                checked = keywords.any { it.equals(word, ignoreCase = true) },
                theme = theme,
                onToggle = {
                    val next = if (keywords.any { it.equals(word, ignoreCase = true) }) {
                        keywords.filterNot { it.equals(word, ignoreCase = true) }
                    } else {
                        keywords + word
                    }
                    onChange(next)
                },
            )
        }
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

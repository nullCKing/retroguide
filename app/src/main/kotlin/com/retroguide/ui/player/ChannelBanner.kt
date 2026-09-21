package com.retroguide.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retroguide.core.guide.ProgramSlot
import com.retroguide.ui.guide.timeRange
import com.retroguide.ui.theme.GuideTheme

/**
 * The cable-style banner that appears at the bottom of the screen on every channel change.
 *
 * Channel number and name, what is on now with a progress bar, and what is on next — the same
 * information a cable box has shown for thirty years, styled from the guide's theme so the two
 * screens look like one product.
 */
@Composable
fun ChannelBanner(
    number: Int,
    name: String,
    now: ProgramSlot?,
    next: ProgramSlot?,
    nowMs: Long,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(theme.bannerHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(theme.bannerBackground)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {

            // Channel number, big, the way a banner leads with it.
            Column(
                modifier = Modifier.width(96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = number.toString(),
                    color = theme.channelNumber,
                    fontSize = theme.titleSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = theme.infoTitle,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = now?.title ?: "No Information",
                    color = theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))

                ProgressBar(now = now, nowMs = nowMs, theme = theme)

                Spacer(Modifier.height(6.dp))
                Text(
                    text = next?.let { "Next: ${it.title}  •  ${timeRange(it.startMs, it.endMs)}" }
                        ?: "",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = now?.let { timeRange(it.startMs, it.endMs) } ?: "",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

/** How far through the current programme we are. Empty when there is no guide data. */
@Composable
private fun ProgressBar(now: ProgramSlot?, nowMs: Long, theme: GuideTheme) {
    val fraction = if (now == null || now.durationMs <= 0) {
        0f
    } else {
        ((nowMs - now.startMs).toFloat() / now.durationMs.toFloat()).coerceIn(0f, 1f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(theme.bannerProgressTrack)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(theme.bannerProgress)
            )
        }
    }
}

/** Overlaid while the player is retrying a stalled stream. */
@Composable
fun ReconnectingLabel(theme: GuideTheme, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(theme.bannerBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Reconnecting…",
            color = theme.cellText,
            fontSize = theme.detailSize,
            fontFamily = theme.fontFamily,
        )
    }
}

/** Shown once the retry budget is spent. */
@Composable
fun PlaybackErrorPanel(
    message: String,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(theme.panel)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "This channel could not be played.",
            color = theme.infoTitle,
            fontSize = theme.detailSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = message,
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
        Text(
            text = "Press SELECT to try again, or UP and DOWN to change channel.",
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
    }
}

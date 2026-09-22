package com.retroguide.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retroguide.core.guide.GuideGeometry
import com.retroguide.core.guide.HALF_HOUR_MS
import com.retroguide.core.guide.LaidOutCell
import com.retroguide.core.guide.ProgramSlot
import com.retroguide.core.guide.TimeWindow
import com.retroguide.domain.GuideChannel
import com.retroguide.ui.theme.GuideTheme
import java.util.Calendar
import java.util.Locale

/**
 * The programme grid, drawn onto a single Canvas.
 *
 * The obvious way to build this — a lazy column of channel rows, each a lazy row of variable-width
 * cells — performs badly on a Fire TV Stick. Every cell becomes a composable with its own layout,
 * measurement and recomposition scope, and a cell's width depends on a programme's duration, so
 * the horizontal lists cannot reuse anything between rows. Scrolling then allocates and lays out
 * dozens of nodes per frame on a device with one weak core to spare.
 *
 * Drawing it instead makes the cost proportional to what is actually on screen: about forty
 * rectangles and forty strings per frame, with no view hierarchy, no layout pass and no
 * recomposition when the highlight moves. The arithmetic behind it lives in
 * [com.retroguide.core.guide.GuideGeometry], which is plain Kotlin and unit-tested, so the part
 * that is easy to get wrong is not the part that needs an emulator to check.
 *
 * Text is drawn twice per cell — once stroked for the dark outline, once filled — rather than the
 * eight offset passes a naive outline needs, which keeps the per-frame text cost to two draws.
 */
@Composable
fun GuideGrid(
    channels: List<GuideChannel>,
    programsByKey: Map<String, List<ProgramSlot>>,
    window: TimeWindow,
    firstVisibleRow: Int,
    selectedRow: Int,
    selectedProgramId: Long?,
    nowMs: Long,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)

    Canvas(modifier = modifier) {
        drawGuide(
            measurer = measurer,
            channels = channels,
            programsByKey = programsByKey,
            window = window,
            firstVisibleRow = firstVisibleRow,
            selectedRow = selectedRow,
            selectedProgramId = selectedProgramId,
            nowMs = nowMs,
            theme = theme,
        )
    }
}

/**
 * Pulled out of the composable so screenshot tests and previews can draw the grid into any
 * DrawScope without standing up a Canvas.
 */
internal fun DrawScope.drawGuide(
    measurer: TextMeasurer,
    channels: List<GuideChannel>,
    programsByKey: Map<String, List<ProgramSlot>>,
    window: TimeWindow,
    firstVisibleRow: Int,
    selectedRow: Int,
    selectedProgramId: Long?,
    nowMs: Long,
    theme: GuideTheme,
) {
    // DrawScope is itself a Density, so dp values convert directly.
    run {
        val channelColumnWidth = theme.channelColumnWidth.toPx()
        val headerHeight = theme.timeHeaderHeight.toPx()
        val rowHeight = theme.rowHeight.toPx()
        val rowGap = theme.rowGap.toPx()
        val corner = theme.cellCorner.toPx()
        val notchDepth = theme.notchDepth.toPx()
        val padding = theme.cellPadding.toPx()
        val minWidth = theme.cellMinWidth.toPx()
        val outline = theme.textOutlineWidth.toPx()

        val gridLeft = channelColumnWidth
        val gridWidth = (size.width - channelColumnWidth).coerceAtLeast(1f)

        // ------------------------------------------------------------ time header
        drawTimeHeader(
            measurer = measurer,
            window = window,
            nowMs = nowMs,
            theme = theme,
            channelColumnWidth = channelColumnWidth,
            gridLeft = gridLeft,
            gridWidth = gridWidth,
            headerHeight = headerHeight,
            corner = corner,
        )

        // ------------------------------------------------------------ rows
        val rowsToDraw = minOf(theme.rowsVisible, channels.size - firstVisibleRow)
        for (offset in 0 until rowsToDraw) {
            val index = firstVisibleRow + offset
            val channel = channels.getOrNull(index) ?: continue
            val top = headerHeight + offset * (rowHeight + rowGap)
            val bottom = top + rowHeight

            drawChannelCell(
                measurer = measurer,
                channel = channel,
                theme = theme,
                left = 0f,
                top = top,
                width = channelColumnWidth - rowGap,
                height = rowHeight,
                corner = corner,
                isSelectedRow = index == selectedRow,
            )

            val slots = programsByKey[channel.channelKey].orEmpty()
            val cells = GuideGeometry.layoutRow(
                slots = slots,
                window = window,
                gridLeft = gridLeft,
                gridWidth = gridWidth,
                minWidth = minWidth,
            )

            for (cell in cells) {
                val isSelected = index == selectedRow && cell.slot.id == selectedProgramId
                drawProgramCell(
                    measurer = measurer,
                    cell = cell,
                    top = top,
                    bottom = bottom,
                    theme = theme,
                    corner = corner,
                    notchDepth = notchDepth,
                    padding = padding,
                    outlineWidth = outline,
                    isSelected = isSelected,
                    gap = rowGap,
                )
            }
        }

        // ------------------------------------------------------------ now marker
        if (nowMs in window.startMs..window.endMs) {
            val x = GuideGeometry.xForTime(nowMs, window, gridLeft, gridWidth)
            val bottom = headerHeight + rowsToDraw * (rowHeight + rowGap)
            drawLine(
                color = theme.nowLine,
                start = Offset(x, headerHeight),
                end = Offset(x, bottom),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

// ---------------------------------------------------------------------- pieces

private fun DrawScope.drawTimeHeader(
    measurer: TextMeasurer,
    window: TimeWindow,
    nowMs: Long,
    theme: GuideTheme,
    channelColumnWidth: Float,
    gridLeft: Float,
    gridWidth: Float,
    headerHeight: Float,
    corner: Float,
) {
    val tabGap = 2f
    val tabHeight = headerHeight - 4f

    // The date tab sits over the channel column, like the "Today" tab in the reference.
    drawTopRoundedRect(
        color = theme.tabFill,
        left = 0f,
        top = 2f,
        right = channelColumnWidth - tabGap,
        bottom = 2f + tabHeight,
        corner = corner,
    )
    drawCenteredLabel(
        measurer = measurer,
        text = dayLabel(window.startMs, nowMs),
        left = 0f,
        right = channelColumnWidth - tabGap,
        top = 2f,
        height = tabHeight,
        style = TextStyle(
            color = theme.tabText,
            fontSize = theme.tabTextSize,
            fontFamily = theme.fontFamily,
            fontWeight = theme.titleWeight,
        ),
    )

    // One tab per half hour.
    val columnWidth = gridWidth / window.columnCount
    for (column in 0 until window.columnCount) {
        val slotStart = window.startMs + column * HALF_HOUR_MS
        val left = gridLeft + column * columnWidth
        val right = left + columnWidth - tabGap
        val isCurrent = nowMs >= slotStart && nowMs < slotStart + HALF_HOUR_MS
        drawTopRoundedRect(
            color = if (isCurrent) theme.tabFillCurrent else theme.tabFill,
            left = left,
            top = 2f,
            right = right,
            bottom = 2f + tabHeight,
            corner = corner,
        )
        drawCenteredLabel(
            measurer = measurer,
            text = clockLabel(slotStart),
            left = left,
            right = right,
            top = 2f,
            height = tabHeight,
            style = TextStyle(
                color = theme.tabText,
                fontSize = theme.tabTextSize,
                fontFamily = theme.fontFamily,
                fontWeight = theme.titleWeight,
            ),
        )
    }
}

private fun DrawScope.drawChannelCell(
    measurer: TextMeasurer,
    channel: GuideChannel,
    theme: GuideTheme,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    corner: Float,
    isSelectedRow: Boolean,
) {
    drawRoundRect(
        color = theme.channelColumn,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )
    if (isSelectedRow) {
        drawRoundRect(
            color = theme.highlight,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = Stroke(width = 2f),
        )
    }

    // Number over call sign, as every cable guide has done since the 1990s.
    val numberText = if (channel.isFavorite) "${channel.number} ★" else channel.number.toString()
    val numberLayout = measurer.measure(
        text = numberText,
        style = TextStyle(
            color = if (channel.isFavorite) theme.highlight else theme.channelNumber,
            fontSize = theme.channelNumberSize,
            fontFamily = theme.fontFamily,
            fontWeight = theme.titleWeight,
        ),
    )
    val nameLayout = measurer.measure(
        text = channel.shortName,
        style = TextStyle(
            color = theme.channelName,
            fontSize = theme.channelNameSize,
            fontFamily = theme.fontFamily,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = (width - 8f).toInt().coerceAtLeast(1)),
    )
    val totalHeight = numberLayout.size.height + nameLayout.size.height
    val startY = top + (height - totalHeight) / 2f
    drawText(
        textLayoutResult = numberLayout,
        topLeft = Offset(left + (width - numberLayout.size.width) / 2f, startY),
    )
    drawText(
        textLayoutResult = nameLayout,
        topLeft = Offset(
            left + (width - nameLayout.size.width) / 2f,
            startY + numberLayout.size.height,
        ),
    )
}

private fun DrawScope.drawProgramCell(
    measurer: TextMeasurer,
    cell: LaidOutCell,
    top: Float,
    bottom: Float,
    theme: GuideTheme,
    corner: Float,
    notchDepth: Float,
    padding: Float,
    outlineWidth: Float,
    isSelected: Boolean,
    gap: Float,
) {
    val left = cell.left
    val right = (cell.right - gap).coerceAtLeast(left + 1f)
    val fill = if (isSelected) theme.highlight else theme.cellColor(cell.slot.category, cell.slot.isFiller)

    if (cell.notchLeft || cell.notchRight) {
        drawPath(
            path = notchedPath(left, top, right, bottom, notchDepth, cell.notchLeft, cell.notchRight),
            color = fill,
        )
    } else {
        drawRoundRect(
            color = fill,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        )
    }

    val textLeft = left + padding + if (cell.notchLeft) notchDepth else 0f
    val textRight = right - padding - if (cell.notchRight) notchDepth else 0f
    val available = (textRight - textLeft).toInt()
    if (available <= 8) return

    val textColour = if (isSelected) theme.highlightText else theme.cellText
    val base = TextStyle(
        fontSize = theme.cellTextSize,
        fontFamily = theme.fontFamily,
        fontWeight = theme.titleWeight,
    )
    val constraints = androidx.compose.ui.unit.Constraints(maxWidth = available)

    clipRect(left = left, top = top, right = right, bottom = bottom) {
        // The highlighted cell has dark text on yellow and needs no outline; every other cell is
        // white text on a mid-tone fill, where the dark outline is what keeps it readable.
        if (!isSelected) {
            val outlineLayout = measurer.measure(
                text = cell.slot.title,
                style = base.copy(
                    color = theme.cellTextOutline,
                    drawStyle = Stroke(width = outlineWidth * 2f),
                ),
                maxLines = theme.cellMaxLines,
                overflow = TextOverflow.Ellipsis,
                constraints = constraints,
            )
            drawText(
                textLayoutResult = outlineLayout,
                topLeft = Offset(textLeft, top + (bottom - top - outlineLayout.size.height) / 2f),
            )
        }
        // Fill is spelled out rather than left null: the measurer caches the paragraph across
        // both passes, a null drawStyle leaves its paint however the last pass set it, and the
        // last pass was the stroke — so without this the fill pass draws stroked too.
        val layout = measurer.measure(
            text = cell.slot.title,
            style = base.copy(color = textColour, drawStyle = Fill),
            maxLines = theme.cellMaxLines,
            overflow = TextOverflow.Ellipsis,
            constraints = constraints,
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(textLeft, top + (bottom - top - layout.size.height) / 2f),
        )
    }
}

/**
 * A cell with a triangular notch on the edges where it runs past the window.
 *
 * The notch is how the reference guides say "this started before you were looking" and "this
 * carries on past the edge", and it is why the grid is drawn rather than composed: an arbitrary
 * polygon per cell is trivial here and awkward with a Shape on a Box.
 */
private fun notchedPath(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    depth: Float,
    notchLeft: Boolean,
    notchRight: Boolean,
): Path {
    val middle = (top + bottom) / 2f
    return Path().apply {
        if (notchLeft) {
            moveTo(left + depth, top)
        } else {
            moveTo(left, top)
        }
        if (notchRight) {
            lineTo(right - depth, top)
            lineTo(right, middle)
            lineTo(right - depth, bottom)
        } else {
            lineTo(right, top)
            lineTo(right, bottom)
        }
        if (notchLeft) {
            lineTo(left + depth, bottom)
            lineTo(left, middle)
        } else {
            lineTo(left, bottom)
        }
        close()
    }
}

/** A rectangle with rounded top corners and square bottom ones, the shape of a guide tab. */
private fun DrawScope.drawTopRoundedRect(
    color: Color,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    corner: Float,
) {
    val r = corner.coerceAtMost((right - left) / 2f).coerceAtMost(bottom - top)
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius.Zero,
                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius.Zero,
            )
        )
    }
    drawPath(path, color)
}

private fun DrawScope.drawCenteredLabel(
    measurer: TextMeasurer,
    text: String,
    left: Float,
    right: Float,
    top: Float,
    height: Float,
    style: TextStyle,
) {
    val layout = measurer.measure(text = text, style = style, maxLines = 1)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            left + ((right - left) - layout.size.width) / 2f,
            top + (height - layout.size.height) / 2f,
        ),
    )
}

// ---------------------------------------------------------------------- labels

/** `9 PM` / `9:30`, matching the reference guide's time tabs. */
internal fun clockLabel(epochMs: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMs }
    val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val meridiem = if (hour24 < 12) "AM" else "PM"
    return if (minute == 0) "$hour12 $meridiem" else "$hour12:${"%02d".format(minute)}"
}

/** `Today`, `Tomorrow`, or `Mon, Sep 21`. */
internal fun dayLabel(epochMs: Long, nowMs: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = epochMs }
    val today = Calendar.getInstance().apply { timeInMillis = nowMs }
    val sameDay = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    today.add(Calendar.DAY_OF_YEAR, 1)
    val isTomorrow = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    if (isTomorrow) return "Tomorrow"
    return android.text.format.DateFormat.format("EEE, MMM d", epochMs).toString()
}

/** `Fri, Apr 25 • 8:43PM`, the clock in the top-right of the reference guides. */
internal fun headerClock(nowMs: Long): String {
    val date = android.text.format.DateFormat.format("EEE, MMM d", nowMs)
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }
    val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val meridiem = if (hour24 < 12) "AM" else "PM"
    return "$date • $hour12:${"%02d".format(minute)}$meridiem"
}

/** `8:00 - 9:00 PM`, for the info panel. */
internal fun timeRange(startMs: Long, endMs: Long): String =
    "${clockLabelWithMinutes(startMs)} - ${clockLabelWithMinutes(endMs)}"

private fun clockLabelWithMinutes(epochMs: Long): String {
    val calendar = Calendar.getInstance(Locale.getDefault()).apply { timeInMillis = epochMs }
    val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val meridiem = if (hour24 < 12) "AM" else "PM"
    return "$hour12:${"%02d".format(minute)}$meridiem"
}

private const val TEXT_CACHE_SIZE = 96

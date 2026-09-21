package com.retroguide.core.guide

import com.retroguide.core.epg.ProgramCategory

/** Half an hour, the width of one column in the guide's time header. */
const val HALF_HOUR_MS: Long = 30 * 60 * 1000L

/** The visible time span of the grid. Two hours by default: four 30-minute columns. */
data class TimeWindow(val startMs: Long, val durationMs: Long = 2 * 60 * 60 * 1000L) {
    val endMs: Long get() = startMs + durationMs
    val columnCount: Int get() = ((durationMs + HALF_HOUR_MS - 1) / HALF_HOUR_MS).toInt()

    fun shiftedBy(deltaMs: Long): TimeWindow = copy(startMs = startMs + deltaMs)

    /** Column boundaries as absolute times, one more than [columnCount]. */
    fun columnBoundaries(): List<Long> =
        (0..columnCount).map { startMs + it * HALF_HOUR_MS }

    companion object {
        /** Rounds [ms] down to the containing half hour, which is where a cable guide starts. */
        fun snapToHalfHour(ms: Long): Long = Math.floorDiv(ms, HALF_HOUR_MS) * HALF_HOUR_MS

        /** The window a guide opens on: the current half hour, plus [duration] of future. */
        fun around(nowMs: Long, duration: Long = 2 * 60 * 60 * 1000L): TimeWindow =
            TimeWindow(snapToHalfHour(nowMs), duration)
    }
}

/** One programme on one channel row, as the guide needs it. */
data class ProgramSlot(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val category: ProgramCategory = ProgramCategory.SERIES_OTHER,
    val description: String = "",
    val rating: String? = null,
    /** True for the synthetic blocks that stand in for missing guide data. */
    val isFiller: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
    fun containsTime(ms: Long): Boolean = ms >= startMs && ms < endMs
}

/**
 * A programme placed in the grid, clipped to the visible window.
 *
 * [notchLeft] and [notchRight] drive the triangular notches the reference guides draw on cells
 * that run past the edge of the visible window.
 */
data class LaidOutCell(
    val slot: ProgramSlot,
    val left: Float,
    val right: Float,
    val notchLeft: Boolean,
    val notchRight: Boolean,
) {
    val width: Float get() = right - left
}

/**
 * Turns programme times into pixel positions.
 *
 * All of the grid's arithmetic lives here, away from the drawing code, because it is the part
 * worth testing: a custom-drawn grid has no view hierarchy to assert against, but its geometry is
 * just numbers.
 */
object GuideGeometry {

    /** Pixel x for an absolute time, clamped to the grid. */
    fun xForTime(timeMs: Long, window: TimeWindow, gridLeft: Float, gridWidth: Float): Float {
        val fraction = (timeMs - window.startMs).toDouble() / window.durationMs.toDouble()
        return gridLeft + (fraction.coerceIn(0.0, 1.0) * gridWidth).toFloat()
    }

    /** Unclamped counterpart of [xForTime], for deciding whether something is off-screen. */
    fun rawXForTime(timeMs: Long, window: TimeWindow, gridLeft: Float, gridWidth: Float): Float {
        val fraction = (timeMs - window.startMs).toDouble() / window.durationMs.toDouble()
        return gridLeft + (fraction * gridWidth).toFloat()
    }

    /**
     * Lays out one channel row.
     *
     * Programmes entirely outside the window are dropped; the rest are clipped to it and marked
     * with notches where they were cut. [minWidth] stops a programme that only just overlaps the
     * window from becoming an unreadable sliver.
     */
    fun layoutRow(
        slots: List<ProgramSlot>,
        window: TimeWindow,
        gridLeft: Float,
        gridWidth: Float,
        minWidth: Float = 0f,
    ): List<LaidOutCell> {
        val out = ArrayList<LaidOutCell>(slots.size)
        for (slot in slots) {
            if (slot.endMs <= window.startMs || slot.startMs >= window.endMs) continue
            var left = xForTime(slot.startMs, window, gridLeft, gridWidth)
            var right = xForTime(slot.endMs, window, gridLeft, gridWidth)
            if (right - left < minWidth) {
                val gridRight = gridLeft + gridWidth
                if (left + minWidth <= gridRight) {
                    right = left + minWidth
                } else {
                    // Against the right edge there is no room to grow rightwards, so the cell
                    // takes its minimum width from the edge inwards instead of staying a sliver.
                    right = gridRight
                    left = (right - minWidth).coerceAtLeast(gridLeft)
                }
            }
            out.add(
                LaidOutCell(
                    slot = slot,
                    left = left,
                    right = right,
                    notchLeft = slot.startMs < window.startMs,
                    notchRight = slot.endMs > window.endMs,
                )
            )
        }
        return out
    }

    /**
     * Fills the gaps in a row with "No Information" blocks, in 30-minute pieces aligned to the
     * half hour, so a channel with no guide data looks like a cable guide rather than an empty
     * band.
     *
     * Input slots must be sorted by start time; overlapping ones are trimmed rather than dropped,
     * since providers do publish overlapping programmes and the grid cannot draw them stacked.
     */
    fun withFillers(
        slots: List<ProgramSlot>,
        window: TimeWindow,
        fillerTitle: String = NO_INFORMATION,
    ): List<ProgramSlot> {
        val result = ArrayList<ProgramSlot>(slots.size + window.columnCount)
        var cursor = window.startMs
        var fillerId = -1L

        fun fill(from: Long, to: Long) {
            var t = TimeWindow.snapToHalfHour(from)
            if (t < from) t = from
            var blockStart = from
            while (blockStart < to) {
                val nextBoundary = TimeWindow.snapToHalfHour(blockStart) + HALF_HOUR_MS
                val blockEnd = minOf(nextBoundary, to)
                result.add(
                    ProgramSlot(
                        id = fillerId--,
                        startMs = blockStart,
                        endMs = blockEnd,
                        title = fillerTitle,
                        category = ProgramCategory.SERIES_OTHER,
                        isFiller = true,
                    )
                )
                blockStart = blockEnd
            }
        }

        for (slot in slots.sortedBy { it.startMs }) {
            if (slot.endMs <= window.startMs) continue
            if (slot.startMs >= window.endMs) break
            if (slot.startMs > cursor) fill(cursor, slot.startMs)
            if (slot.endMs > cursor) {
                // Trim a programme that overlaps the one before it.
                val trimmed = if (slot.startMs < cursor) slot.copy(startMs = cursor) else slot
                result.add(trimmed)
                cursor = trimmed.endMs
            }
        }
        if (cursor < window.endMs) fill(cursor, window.endMs)
        return result
    }

    const val NO_INFORMATION = "No Information"
}

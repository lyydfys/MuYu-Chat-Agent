package com.muyuchat.mca.presentation

import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.feature.agent.TuningTrialItem

// Benchmark and size formatting for the UI, moved verbatim from MainActivity during P2.
//
// formatAppUpdateBytes and formatAssetBytes are the same function under two names, and an earlier
// revision of this comment claimed otherwise. It said the first "switches to GB at 1 GiB and
// prints with no space between number and unit" while the second "walks B/KB/MB/GB and prints
// with a space". Reading the bodies shows both walk B/KB/MB/GB and both print with a space; the
// only differences were a loop variable name and an if-expression versus a block. The claim was
// wrong on both counts for the first function, and it was the reason they were left duplicated.
//
// They now share one implementation. Both names are kept, because they say which surface each
// call site belongs to and renaming call sites would be a change with no benefit; each is a
// one-line delegation, so there is one place to read the behaviour and two names to call it by.
// Nothing about the output changed: same units, same 1 KiB step, same "0 B" for zero and
// negative, same space, same one decimal, same toLong for the byte range.

internal fun formatAppUpdateBytes(bytes: Long): String = formatByteMagnitude(bytes)

internal fun formatAssetBytes(bytes: Long): String = formatByteMagnitude(bytes)

/**
 * Byte count as B, KB, MB or GB, with a space before the unit.
 *
 * Non-positive input renders "0 B". Below 1 KiB the value prints as a whole number; above it,
 * one decimal place. The loop stops at GB, so a terabyte-scale value keeps growing in GB rather
 * than gaining a unit.
 *
 * This is one of several byte formatters in the repository and is deliberately not shared with
 * the others. The GB/MB pair used by the model screens prints "2.00GB" with no space, and
 * settings renders through the same four units with a different loop. Those outputs are visible
 * text, so merging them is a product change rather than a refactor.
 */
private fun formatByteMagnitude(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        "%.1f %s".format(value, units[index])
    }
}

internal fun BenchmarkResult.toTuningTrialItems(): List<TuningTrialItem> {
    val trials = runCatching { org.json.JSONArray(threadResultsJson) }.getOrNull() ?: return emptyList()
    return (0 until trials.length()).mapNotNull { index ->
        val item = trials.optJSONObject(index) ?: return@mapNotNull null
        val threads = item.optInt("threads")
        if (threads <= 0) return@mapNotNull null
        TuningTrialItem(
            threads = threads,
            decodeTps = item.optDouble("decodeTps"),
            ttftMs = item.optLong("ttftMs"),
            genTokens = item.optInt("genTokens"),
            stable = item.optBoolean("stable", true) && item.optString("error").isBlank(),
            selected = threads == bestThreadCount
        )
    }
}

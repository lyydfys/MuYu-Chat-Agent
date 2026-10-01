package com.muyuchat.mca

/**
 * Human-readable formatting for the numbers the UI shows next to progress and sizes.
 *
 * These three answer the same question -- "how do I write this magnitude for a person?" --
 * so they live together and are tested together. Extracted verbatim from `MainViewModel`
 * during P2; no threshold, unit, or rounding was changed.
 *
 * ## Locale
 *
 * [formatBytes] and [formatPercent] use `String.format` **without an explicit Locale**, so a
 * device whose locale uses a comma decimal separator renders "1,50 GB" rather than "1.50 GB".
 * That is inherited behaviour, not a decision made here, and P2 preserves it because changing
 * it would change visible output. It is pinned by a test so the dependence stays deliberate;
 * making it explicit is a separate, visible change.
 *
 * [formatDuration] is not locale-sensitive: its units are Chinese literals.
 */

/** One decimal place and a percent sign, e.g. `42.0%`. */
internal fun formatPercent(value: Double): String = "%.1f%%".format(value)

/**
 * Duration in the largest unit that applies.
 *
 * Negative input is clamped to zero. Seconds are **dropped** once the duration reaches an
 * hour: 3600 becomes "1小时0分" and 3661 becomes "1小时1分".
 */
internal fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val minutes = safe / 60
    val remainSeconds = safe % 60
    val hours = minutes / 60
    val remainMinutes = minutes % 60
    return when {
        hours > 0 -> "${hours}小时${remainMinutes}分"
        minutes > 0 -> "${minutes}分${remainSeconds}秒"
        else -> "${remainSeconds}秒"
    }
}

/**
 * Byte count as gigabytes once it reaches 1 GiB, otherwise as megabytes.
 *
 * There is no kilobyte or byte form: anything below 1 MiB renders as "0.0 MB", and one byte
 * under 1 GiB renders as "1024.0 MB" rather than "1.00 GB". Preserved from the original.
 */
internal fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    val mb = bytes / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
}

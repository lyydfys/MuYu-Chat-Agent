package com.muyuchat.core.modelstore

/**
 * Byte count for the model screens: gigabytes once the value reaches 1 GiB, megabytes below it.
 *
 * One implementation for the two identical copies that used to sit in ModelCompatibility and
 * ModelStoreRepository. Their bodies were character for character the same, including the
 * constants, so this is a move rather than a merge of different behaviours.
 *
 * The output is deliberately unlike the other byte formatters in the repository and is not
 * shared with them. There is no space between the number and the unit ("2.00GB" not "2.00 GB"),
 * there is no kilobyte or byte form, and anything below 1 MiB renders "0.0 MB" while one byte
 * under 1 GiB renders "1024.0 MB". Those are visible strings on the model screens; changing them
 * is a product decision, not a refactor.
 */
internal fun formatModelSizeBytes(bytes: Long): String {
    val gb = bytes / GIB.toDouble()
    val mb = bytes / MIB.toDouble()
    return if (gb >= 1.0) "%.2fGB".format(gb) else "%.1fMB".format(mb)
}

private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB

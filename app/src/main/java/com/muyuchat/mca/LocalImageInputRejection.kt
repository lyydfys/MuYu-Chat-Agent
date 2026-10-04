package com.muyuchat.mca

/**
 * The typed rejection used by every product-input contract check.
 *
 * Split out of LocalImageInputContract.kt. `rejectProductInput` was file-private; it becomes
 * `localImageInputReject` so it can serve the other contract files from one place, with the
 * responsibility prefix this package requires of shared top-level declarations.
 */

class LocalImageProductContractException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)

internal fun localImageInputReject(code: String, message: String): Nothing =
    throw LocalImageProductContractException(code, message)

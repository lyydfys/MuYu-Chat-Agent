package com.muyuchat.mca

import kotlinx.coroutines.CancellationException

/** Covers preflight, the operation and result publication with one exception boundary. */
internal suspend fun <T> recoverModelOperation(
    operation: suspend () -> T,
    onSuccess: suspend (T) -> Unit = {},
    onFailure: suspend (Exception) -> Unit
) {
    try {
        onSuccess(operation())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    }
}

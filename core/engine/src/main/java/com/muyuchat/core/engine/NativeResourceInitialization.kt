package com.muyuchat.core.engine

/** A failed cleanup must not be followed by another native allocation in the same process. */
internal class NativeResourceCleanupException(initialization: Throwable, cleanup: Throwable) :
    IllegalStateException("Native initialization failed and its candidate could not be released; reload the worker.", initialization) {
    init { addSuppressed(cleanup) }
}

internal fun <T> initializeOwnedNativeResource(
    create: () -> T,
    initialize: (T) -> Unit,
    close: (T) -> Unit
): T {
    val candidate = create()
    try {
        initialize(candidate)
        return candidate
    } catch (initialization: Throwable) {
        try {
            close(candidate)
        } catch (cleanup: Throwable) {
            throw NativeResourceCleanupException(initialization, cleanup)
        }
        throw initialization
    }
}

package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeResourceInitializationTest {
    @Test
    fun failedCandidateIsReleasedBeforeAnotherCandidateCanBeInitialized() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("compiled model initialization failed")
        fun attempt(id: Int) = initializeOwnedNativeResource(
            create = { events += "create$id"; id },
            initialize = { events += "initialize$it"; if (it == 1) throw failure },
            close = { events += "close$it" }
        )
        assertSame(failure, runCatching { attempt(1) }.exceptionOrNull())
        assertEquals(2, attempt(2))
        assertEquals(listOf("create1", "initialize1", "close1", "create2", "initialize2"), events)
    }

    @Test
    fun cleanupFailureIsDistinctSoTheLoaderMustNotRetryAnUnreleasedEngine() {
        val initialization = IllegalStateException("status code: 13")
        val cleanup = IllegalStateException("close failed")
        val error = runCatching {
            initializeOwnedNativeResource(
                create = { Any() },
                initialize = { throw initialization },
                close = { throw cleanup }
            )
        }.exceptionOrNull()
        assertTrue(error is NativeResourceCleanupException)
        assertSame(initialization, error?.cause)
        assertSame(cleanup, error?.suppressed?.single())
    }

    @Test
    fun successfulInitializationTransfersOwnershipWithoutClosingTheResource() {
        val candidate = Any()
        var closed = false
        assertSame(candidate, initializeOwnedNativeResource(
            create = { candidate }, initialize = {}, close = { closed = true }
        ))
        assertEquals(false, closed)
    }
}

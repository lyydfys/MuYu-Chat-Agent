package com.muyuchat.mca

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ModelOperationRecoveryTest {
    @Test fun downloadSuccessFollowedByCatalogWriteFailureIsHandled() = runBlocking {
        var downloaded = false
        var failure: Exception? = null
        recoverModelOperation(operation = { downloaded = true; "complete file" },
            onSuccess = { throw IOException("ENOSPC during catalog refresh") }, onFailure = { failure = it })
        assertTrue(downloaded)
        assertTrue(failure is IOException)
    }

    @Test fun preflightFailureNeverStartsNativeLoad() = runBlocking {
        var nativeLoadStarted = false
        var failure: Exception? = null
        recoverModelOperation(operation = { throw IOException("unreadable manifest") },
            onSuccess = { nativeLoadStarted = true }, onFailure = { failure = it })
        assertFalse(nativeLoadStarted)
        assertNotNull(failure)
    }

    @Test fun cancellationPropagatesWithoutReportingFalseFailure() = runBlocking {
        var failed = false
        val error = runCatching {
            recoverModelOperation(operation = { throw CancellationException("paused") }, onFailure = { failed = true })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(failed)
    }
}

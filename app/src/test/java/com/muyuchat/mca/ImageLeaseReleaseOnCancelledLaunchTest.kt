package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2 regression: a generation request must not leak its foreground-service lease when the
 * launched job never runs its body.
 *
 * `McaGenerationForegroundService.acquire(...)` happens in `submit`/`upscaleImageAsset`, outside
 * the coroutine, and the matching release used to live only in a `try/finally` *inside* the job
 * body. `launch` does not execute the body of a job that is already cancelled (scope cancelled, or
 * the job cancelled between `launch` and dispatch), so that `finally` never runs, the lease stays
 * in `GenerationForegroundLeaseRegistry`, the last-owner `stopService` dispatch never happens and
 * the ongoing "MCA 正在生成" notification is pinned forever.
 *
 * These tests exercise the real production registry (`GenerationForegroundLeaseRegistry`) and the
 * real completion/release semantics used by the two owners, and pin the owner call sites to the
 * completion-handler form so the defect cannot come back silently.
 */
class ImageLeaseReleaseOnCancelledLaunchTest {

    private val owners = listOf(
        "app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt",
        "app/src/main/java/com/muyuchat/mca/ImageUpscaleOwner.kt"
    )

    @Test
    fun completionHandlerReleasesRegisteredOwnerWhenTheBodyNeverStarts() {
        val registry = GenerationForegroundLeaseRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.cancel()
        var stopDispatches = 0

        val lease = registry.acquire("image") { }

        // The fixed production shape: the release is additionally bound to job completion.
        // Unconfined keeps this deterministic: the job completes before `launch` returns, so the
        // completion handler has already run by the assertions below (no thread race).
        val job = scope.launch(Dispatchers.Unconfined) {
            try {
                awaitBody()
            } finally {
                registry.release(lease) { stopDispatches++ }
            }
        }
        job.invokeOnCompletion { registry.release(lease) { stopDispatches++ } }

        assertTrue(job.isCancelled)
        assertEquals(
            "Completion-handler release must drop the owner even though the body never ran",
            0,
            registry.withCurrentTask { it?.ownerCount ?: 0 }
        )
        assertEquals("The last-owner stop must be dispatched exactly once", 1, stopDispatches)
    }

    @Test
    fun bodyOnlyFinallyCannotReleaseWhenTheBodyNeverStarts() {
        // This is the exact pre-fix shape. It documents why the completion handler is required:
        // the body never runs, so its finally never runs either.
        val registry = GenerationForegroundLeaseRegistry()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.cancel()
        var stopDispatches = 0

        val lease = registry.acquire("image") { }

        val job = scope.launch(Dispatchers.Unconfined) {
            try {
                awaitBody()
            } finally {
                registry.release(lease) { stopDispatches++ }
            }
        }

        assertTrue(job.isCancelled)
        assertEquals(
            "This assertion is the defect: the owner is still registered after a cancelled launch",
            1,
            registry.withCurrentTask { it?.ownerCount ?: 0 }
        )
        assertEquals(0, stopDispatches)
    }

    @Test
    fun releasingTwiceIsIdempotentAndNeverStopsAnotherOwner() {
        val registry = GenerationForegroundLeaseRegistry()
        var stops = 0
        val first = registry.acquire("image") { }
        val second = registry.acquire("chat") { }

        assertEquals(true, registry.release(first) { stops++ })
        assertEquals("Another owner still holds the lease, so no stop may be dispatched", 0, stops)
        // The body's finally and the completion handler can both run for one job.
        assertEquals(false, registry.release(first) { stops++ })
        assertEquals(0, stops)
        assertEquals(true, registry.release(second) { stops++ })
        assertEquals("Only the last owner stops the service", 1, stops)
    }

    @Test
    fun bothOwnersBindTheReleaseToJobCompletion() {
        owners.forEach { relative ->
            val source = stripComments(File(repositoryRoot(), relative).readText(Charsets.UTF_8))
            val declaresReleaseLambda = Regex(
                """val\s+releaseForegroundLease\s*=\s*\{\s*McaGenerationForegroundService\.release\("""
            ).containsMatchIn(source)
            assertTrue(
                "$relative must build its foreground-lease release from the acquired lease",
                declaresReleaseLambda
            )
            val completionReleases = Regex(
                """invokeOnCompletion\s*\{\s*releaseForegroundLease\(\)\s*\}"""
            ).findAll(source).count()
            assertTrue(
                "$relative must release its foreground lease from job completion so a job that " +
                    "never starts its body still releases it (found $completionReleases)",
                completionReleases >= 1
            )
        }
    }

    /**
     * Kotlin source only: a commented-out release must not satisfy the contract. Block and line
     * comments are blanked so the regular expressions can only match live code.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            when {
                source.startsWith("//", cursor) -> {
                    while (cursor < source.length && source[cursor] != '\n') {
                        out.append(' ')
                        cursor++
                    }
                }
                source.startsWith("/*", cursor) -> {
                    val end = source.indexOf("*/", cursor + 2).let { if (it < 0) source.length else it + 2 }
                    repeat(end - cursor) {
                        out.append(if (source[cursor] == '\n') '\n' else ' ')
                        cursor++
                    }
                }
                else -> {
                    out.append(source[cursor])
                    cursor++
                }
            }
        }
        return out.toString()
    }

    private suspend fun awaitBody() {
        kotlinx.coroutines.yield()
    }

    private fun repositoryRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return generateSequence(start) { it.parentFile }
            .firstOrNull { candidate ->
                File(candidate, "settings.gradle.kts").isFile &&
                    File(candidate, "AGENTS.md").isFile &&
                    File(candidate, ".git").exists()
            }
            ?: error("Cannot locate MCA repository root from ${start.path}")
    }
}

private fun CoroutineScope.cancel() {
    (coroutineContext[Job])?.cancel()
}

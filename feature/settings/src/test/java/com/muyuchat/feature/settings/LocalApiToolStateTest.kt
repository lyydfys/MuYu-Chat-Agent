package com.muyuchat.feature.settings

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiToolStateTest {
    @Test
    fun `self-test busy is transient instead of saveable`() {
        val source = localApiToolSource()
        val declaration = Regex("""var\s+selfTestBusy\s+by\s+([^\r\n]+)""").find(source)

        assertNotNull("selfTestBusy declaration", declaration)
        assertEquals(
            "remember { mutableStateOf(false) }",
            declaration!!.groupValues[1].trim()
        )
        assertFalse(source.contains("selfTestBusy = true"))
        assertFalse(source.contains("selfTestBusy = false"))
        assertEquals(4, occurrences(source, "withLocalApiSelfTestBusy("))
    }

    @Test
    fun `self-test result text remains saveable`() {
        val source = localApiToolSource()

        listOf("modelsSelfTest", "chatSelfTest", "visionSelfTest").forEach { result ->
            assertTrue(
                "$result must preserve completed or failed result across rotation",
                source.contains("var $result by rememberSaveable { mutableStateOf(")
            )
        }
    }

    @Test
    fun `successful self-test clears busy`() = runBlocking {
        val busyStates = mutableListOf<Boolean>()

        val result = withLocalApiSelfTestBusy({ busyStates += it }) { "ok" }

        assertEquals("ok", result)
        assertEquals(listOf(true, false), busyStates)
    }

    @Test
    fun `failed self-test clears busy and propagates failure`() = runBlocking {
        val busyStates = mutableListOf<Boolean>()

        val failure = try {
            withLocalApiSelfTestBusy({ busyStates += it }) { throw ExpectedFailure() }
            null
        } catch (error: ExpectedFailure) {
            error
        }

        assertNotNull("expected failure must propagate", failure)
        assertEquals(listOf(true, false), busyStates)
    }

    @Test
    fun `cancelled self-test clears busy and allows retry`() = runBlocking {
        val busyStates = mutableListOf<Boolean>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withLocalApiSelfTestBusy({ busyStates += it }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }

        started.await()
        assertEquals(listOf(true), busyStates)

        job.cancelAndJoin()
        assertEquals(listOf(true, false), busyStates)

        val retryResult = withLocalApiSelfTestBusy({ busyStates += it }) { "retry" }
        assertEquals("retry", retryResult)
        assertEquals(listOf(true, false, true, false), busyStates)
    }

    @Test
    fun `cancellation exception is propagated instead of swallowed`() = runBlocking {
        var busy = false
        val cancellation = CancellationException("cancelled")

        val actual = try {
            withLocalApiSelfTestBusy({ busy = it }) { throw cancellation }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, actual)
        assertFalse(busy)
    }

    private fun localApiToolSource(): String = sourceFile(LOCAL_API_TOOL_SOURCE_PATH)

    private fun occurrences(source: String, value: String): Int =
        Regex(Regex.escape(value)).findAll(source).count()

    private fun sourceFile(relativePath: String): String = File(projectRoot(), relativePath).readText()

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private class ExpectedFailure : RuntimeException("expected failure")

    private companion object {
        const val LOCAL_API_TOOL_SOURCE_PATH =
            "feature/settings/src/main/java/com/muyuchat/feature/settings/LocalApiToolScreen.kt"
    }
}
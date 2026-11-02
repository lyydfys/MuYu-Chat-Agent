package com.muyuchat.mca

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 wake ownership contract.
 *
 * Rules are checked against code with comments and literals removed, or against the compiled type
 * surface, so documentation cannot satisfy or break a rule. Runtime behaviour lives in
 * `AgentParkWakeRunnerTest` and the owner's `AgentParkTokenWakeTest`.
 */
class AgentParkWakeWorkerSourceContractTest {

    private val worker = codeOnly(source("app/src/main/java/com/muyuchat/mca/AgentParkWakeWorker.kt"))
    private val runner = codeOnly(source("app/src/main/java/com/muyuchat/mca/agent/workflow/AgentParkWakeRunner.kt"))
    private val owner = codeOnly(source("app/src/main/java/com/muyuchat/mca/composition/McaProcessRuntimeOwner.kt"))
    private val assembly = codeOnly(source("app/src/main/java/com/muyuchat/mca/agent/composition/AgentRuntimeAssembly.kt"))
    private val scheduler = codeOnly(source("app/src/main/java/com/muyuchat/mca/WorkManagerAgentParkWakeScheduler.kt"))

    @Test
    fun `the park worker borrows the process root and never builds a runtime`() {
        listOf(
            "processRuntimeOwner.wakeParkedRun(",
            "AgentParkWakeRunner(",
            "beginBackgroundWork()",
            "finishBackgroundWork()"
        ).forEach { token ->
            assertTrue("AgentParkWakeWorker must contain $token", worker.contains(token))
        }
        listOf(worker, runner).forEach { code ->
            forbiddenRuntimeConstructors.forEach { type ->
                assertFalse("the park wake path must not construct $type", code.contains("$type("))
            }
        }

        val constructors = AgentParkWakeWorker::class.java.declaredConstructors
        assertEquals("one WorkManager constructor", 1, constructors.size)
        assertEquals(
            "the worker receives only WorkManager's own inputs",
            listOf("android.content.Context", WorkerParameters::class.java.name),
            constructors.single().parameterTypes.map { it.name }
        )
        assertTrue(
            "WorkManager must be able to run the worker as a CoroutineWorker",
            CoroutineWorker::class.java.isAssignableFrom(AgentParkWakeWorker::class.java)
        )
    }

    @Test
    fun `the worker settles and rethrows cancellation through the runner`() {
        assertTrue(runner.contains("finally"))
        assertTrue(runner.contains("settle()"))
        assertTrue(runner.contains("catch (cancelled: CancellationException)"))
        assertTrue(runner.contains("throw cancelled"))
    }

    @Test
    fun `the process owner reconciles before validating a wake on the shared root`() {
        assertTrue(owner.contains("suspend fun wakeParkedRun("))
        assertTrue(owner.contains("schedulesMutex.withLock"))
        assertTrue(owner.contains("val processRoot = root(scopeId)"))
        assertTrue(owner.contains("agentHost.runtime().reconcileStartup()"))
        assertTrue(owner.contains("check(!processRoot.isClosed)"))
        assertTrue(owner.contains("agentHost.runtime().wakeParked(runId, wakeId)"))
        val reconcileAt = owner.indexOf("reconcileStartup()", owner.indexOf("wakeParkedRun("))
        val wakeAt = owner.indexOf("wakeParked(runId, wakeId)", owner.indexOf("wakeParkedRun("))
        assertTrue("recovery must run before a park wake", reconcileAt in 0 until wakeAt)
        assertEquals(
            "the process root must be created in exactly one place",
            1,
            Regex("McaProcessRuntimeRoot\\(").findAll(owner).count()
        )
    }

    @Test
    fun `cold start arms a platform wake only for parks that remain waiting`() {
        assertTrue(assembly.contains("AgentParkWakeScheduler"))
        assertTrue(assembly.contains("owner.wakeParked(token.runId, token.wakeId)"))
        assertTrue(assembly.contains("AgentParkWakeResult.StillParked"))
        assertTrue(assembly.contains("parkWakeScheduler?.schedule(token.runId, token.wakeId)"))
        assertTrue(
            "the wake must be armed after the owner validated it",
            assembly.indexOf("owner.wakeParked(") <
                assembly.indexOf("parkWakeScheduler?.schedule(")
        )
    }

    @Test
    fun `the WorkManager adapter uses one unique work name per run`() {
        assertTrue(scheduler.contains("enqueueUniqueWork("))
        assertTrue(scheduler.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(scheduler.contains("AgentParkWakePlan.uniqueName(runId)"))
        assertTrue(scheduler.contains("setInputData("))
        assertTrue(scheduler.contains("AgentParkWakeWorker.KEY_RUN_ID"))
        assertTrue(scheduler.contains("AgentParkWakeWorker.KEY_WAKE_ID"))
        assertFalse(scheduler.contains("AgentRunOwner("))
        assertFalse(scheduler.contains("McaProcessRuntimeRoot("))
    }

    private fun source(relative: String): String =
        File(projectRoot(), relative).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val app = File(candidate, "app/src/main/java/com/muyuchat/mca")
            if (app.isDirectory) return candidate!!
            candidate = candidate?.parentFile
        }
        error("Unable to locate the MCA project root from ${System.getProperty("user.dir")}")
    }

    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("//", index) -> {
                    val newline = text.indexOf('\n', index)
                    index = if (newline < 0) text.length else newline
                }
                text.startsWith("/*", index) -> index = skipBlockComment(text, index)
                text.startsWith("\"\"\"", index) -> {
                    val end = text.indexOf("\"\"\"", index + 3)
                    index = if (end < 0) text.length else end + 3
                    out.append("\"\"")
                }
                text[index] == '"' -> {
                    index = skipQuoted(text, index, '"')
                    out.append("\"\"")
                }
                text[index] == '\'' -> {
                    index = skipQuoted(text, index, '\'')
                    out.append("''")
                }
                else -> {
                    out.append(text[index])
                    index += 1
                }
            }
        }
        return out.toString()
    }

    private fun skipBlockComment(text: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < text.length) {
            when {
                text.startsWith("/*", index) -> {
                    depth += 1
                    index += 2
                }
                text.startsWith("*/", index) -> {
                    depth -= 1
                    index += 2
                    if (depth == 0) return index
                }
                else -> index += 1
            }
        }
        return index
    }

    private fun skipQuoted(text: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                quote -> return index + 1
                else -> index += 1
            }
        }
        return index
    }

    private companion object {
        val forbiddenRuntimeConstructors = listOf(
            "MainRuntimeAssembly",
            "AgentRuntimeAssembly",
            "McaAppContainer",
            "McaProcessRuntimeRoot",
            "AgentRunOwner"
        )
    }
}

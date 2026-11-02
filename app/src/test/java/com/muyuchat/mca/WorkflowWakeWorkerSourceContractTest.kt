package com.muyuchat.mca

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.muyuchat.mca.agent.workflow.AgentWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.WorkflowScheduleService
import com.muyuchat.mca.agent.workflow.WorkflowScheduleStore
import com.muyuchat.mca.agent.workflow.WorkflowTriggerFacade
import com.muyuchat.mca.agent.workflow.WorkflowWakeScheduler
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0008 / N22 ownership contract for the WorkManager wake.
 *
 * Rules are checked against code with comments and literals removed, or against the compiled type
 * surface. Documentation can therefore never satisfy or break a rule by accident. The behaviour
 * that depends on real state (a frozen schedule plan beating an ambient plan) lives in
 * [com.muyuchat.mca.agent.workflow.WorkflowScheduleServiceTest].
 */
class WorkflowWakeWorkerSourceContractTest {

    private val worker = codeOnly(source(WORKER))
    private val scheduler = codeOnly(source(SCHEDULER))
    private val runner = codeOnly(source(RUNNER))
    private val owner = codeOnly(source(OWNER))
    private val assembly = codeOnly(source(ASSEMBLY))
    private val service = codeOnly(source(SERVICE))
    private val serviceImports = importLines(source(SERVICE))

    @Test
    fun `the wake worker obtains the process root and never builds a runtime`() {
        listOf(
            "processRuntimeOwner.workflowSchedules()",
            "WorkflowWakeRunner(",
            "beginBackgroundWork()",
            "finishBackgroundWork()"
        ).forEach { token ->
            assertTrue("WorkflowWakeWorker must contain $token", worker.contains(token))
        }
        listOf(worker, runner).forEach { code ->
            forbiddenRuntimeConstructors.forEach { type ->
                assertFalse("the wake path must not construct $type", code.contains("$type("))
            }
        }
        listOf("hasDue()", "runDue()").forEach { token ->
            assertTrue("WorkflowWakeRunner must ask the shared service for $token", runner.contains(token))
        }

        val constructors = WorkflowWakeWorker::class.java.declaredConstructors
        assertEquals("one WorkManager constructor", 1, constructors.size)
        assertEquals(
            "the worker receives only WorkManager's own inputs",
            listOf("android.content.Context", WorkerParameters::class.java.name),
            constructors.single().parameterTypes.map { it.name }
        )
        assertTrue(
            "WorkManager must be able to run the worker as a CoroutineWorker",
            CoroutineWorker::class.java.isAssignableFrom(WorkflowWakeWorker::class.java)
        )
    }

    @Test
    fun `the wake worker delegates terminal settlement to the tested runner`() {
        assertTrue(
            "the worker must settle through the process root it borrowed",
            worker.contains("finishBackgroundWork()")
        )
        assertTrue(
            "terminal-path behaviour belongs to WorkflowWakeRunner",
            worker.contains("WorkflowWakeRunner(")
        )
    }

    @Test
    fun `the process owner exposes schedules from the single shared root`() {
        assertTrue(
            owner.contains("suspend fun workflowSchedules(): WorkflowScheduleService")
        )
        assertTrue(owner.contains("schedulesMutex.withLock"))
        assertTrue(owner.contains("val processRoot = root(scopeId)"))
        assertTrue(owner.contains("val agentHost = processRoot.assembly.agentHost"))
        assertTrue(owner.contains("agentHost.runtime().reconcileStartup()"))
        assertTrue(owner.contains("check(!processRoot.isClosed)"))
        assertTrue(owner.contains("agentHost.workflowScheduleService()"))
        val reconcileAt = owner.indexOf("reconcileStartup()")
        val schedulesAt = owner.indexOf("workflowScheduleService()")
        assertTrue(
            "a worker cold start must reconcile interrupted runs before any schedule runs",
            reconcileAt in 0 until schedulesAt
        )
        assertEquals(
            "the process root must be created in exactly one place",
            1,
            Regex("McaProcessRuntimeRoot\\(").findAll(owner).count()
        )
        assertEquals(
            "the composition owner is the one place that creates the application container",
            1,
            Regex("McaAppContainer\\(").findAll(owner).count()
        )
        listOf("MainRuntimeAssembly(", "AgentRuntimeAssembly(").forEach { type ->
            assertFalse("the owner must not build a second $type", owner.contains(type))
        }
    }

    @Test
    fun `the assembly creates the schedule service on the shared root`() {
        assertTrue(assembly.contains("fun workflowScheduleService(): WorkflowScheduleService"))
        assertTrue(assembly.contains("FileWorkflowScheduleStore(workflowRoot)"))
        assertTrue(assembly.contains("WorkManagerWorkflowWakeScheduler(application)"))
    }

    @Test
    fun `a foreground cold start reconciles the owner before re-arming persisted schedules`() {
        assertTrue(assembly.contains("suspend fun reconcileProcessStartup()"))
        val reconcileAt = assembly.indexOf("runtime().reconcileStartup()")
        val rearmAt = assembly.indexOf("workflowScheduleService().rearm()")
        assertTrue("the assembly must reconcile interrupted runs", reconcileAt >= 0)
        assertTrue("the assembly must re-arm durable wakes after reconciling", rearmAt > reconcileAt)
        val viewModel = codeOnly(source(VIEW_MODEL))
        assertTrue(
            "the foreground startup path must use the one reconciliation entry",
            viewModel.contains("agentHost.reconcileProcessStartup()")
        )
        assertFalse(
            "the page must not install wakes itself",
            viewModel.contains("WorkManager") || viewModel.contains("setSweepEnabled")
        )
    }

    @Test
    fun `a failed reconciliation must not leave persisted schedules without their wake`() {
        // Device evidence 2026-10-28 (run 7): reconcileStartup() threw, the caller swallowed it, and
        // rearm() never ran, so that cold start installed no one-time wake at all. The two duties
        // are independent; the re-arm must sit outside the guard that wraps reconciliation.
        val body = assembly.substring(
            assembly.indexOf("suspend fun reconcileProcessStartup()"),
            assembly.indexOf("fun close()")
        )
        val reconcileAt = body.indexOf("runtime().reconcileStartup()")
        val guardAt = body.indexOf("catch (failure: Throwable)")
        val rearmAt = body.indexOf("workflowScheduleService().rearm()")
        assertTrue("the assembly must reconcile interrupted runs", reconcileAt >= 0)
        assertTrue(
            "the re-arm must run after the reconciliation failure is contained",
            guardAt in reconcileAt until rearmAt
        )
        assertEquals(
            "both duties must be contained on their own",
            2,
            Regex("catch \\(failure: Throwable\\)").findAll(body).count()
        )
        assertEquals(
            "cancellation must still propagate out of both duties",
            2,
            Regex("catch \\(cancelled: CancellationException\\)").findAll(body).count()
        )
        assertEquals(
            "every contained failure must be visible in the log",
            3,
            Regex("Log\\.w\\(").findAll(body).count()
        )
    }

    @Test
    fun `the schedule service depends on no chat surface`() {
        val constructor = WorkflowScheduleService::class.java.constructors
            .single { it.parameterCount == 5 }
        assertEquals(
            "the schedule service cannot read a page selection: its dependency surface is fixed",
            listOf(
                WorkflowScheduleStore::class.java.name,
                AgentWorkflowDefinitionStore::class.java.name,
                WorkflowTriggerFacade::class.java.name,
                WorkflowWakeScheduler::class.java.name,
                "kotlin.jvm.functions.Function0"
            ),
            constructor.parameterTypes.map { it.name }
        )
        assertTrue(
            "the schedule service must not import a chat page or its state",
            serviceImports.none { line ->
                line.contains("muyuchat.feature") ||
                    line.contains("ChatViewModel") ||
                    line.contains("MainViewModel") ||
                    line.contains("MainUiState")
            }
        )
        listOf("ChatViewModel", "MainViewModel", "MainUiState", "activeBinding").forEach { symbol ->
            assertFalse("the schedule service must not read $symbol", service.contains(symbol))
        }
    }

    @Test
    fun `a delayed wake is never expedited because WorkManager rejects that combination`() {
        // Device evidence 2026-10-28 (run 11): every re-arm failed at WorkRequest.build with
        // "IllegalArgumentException: Expedited jobs cannot be delayed", so no cold start installed
        // a one-time wake and only the 15-minute sweep ever claimed a slot. The delay and the
        // expedited flag are therefore exclusive: an already-due wake is expedited (Android 12+
        // needs that for its off-screen foreground notification), a deferred wake is ordinary
        // one-time work.
        val guardAt = scheduler.indexOf("if (wake.delayMillis > 0L)")
        val delayAt = scheduler.indexOf("builder.setInitialDelay(wake.delayMillis, TimeUnit.MILLISECONDS)")
        val expeditedAt = scheduler.indexOf(
            "builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)"
        )
        assertTrue("the install must branch on whether the wake is deferred", guardAt >= 0)
        assertTrue("a deferred wake keeps its delay", delayAt > guardAt)
        assertTrue("only an already-due wake is expedited", expeditedAt > guardAt)
        assertEquals(
            "the delay is set in exactly one place",
            1,
            Regex("setInitialDelay").findAll(scheduler).count()
        )
        assertEquals(
            "the expedited flag is set in exactly one place",
            1,
            Regex("setExpedited").findAll(scheduler).count()
        )
        assertTrue(
            "a quota-capped expedited wake must still run as ordinary work",
            scheduler.contains("import androidx.work.OutOfQuotaPolicy")
        )
        assertTrue(
            "the worker must publish the same foreground notification on the pre-31 expedited path",
            worker.contains("override suspend fun getForegroundInfo(): ForegroundInfo")
        )
        assertFalse(
            "the periodic sweep cannot be expedited and must not pretend to be",
            scheduler.contains("PeriodicWorkRequestBuilder<WorkflowWakeWorker>(") &&
                scheduler.substringAfter("PeriodicWorkRequestBuilder<WorkflowWakeWorker>(")
                    .substringBefore(".build()").contains("setExpedited")
        )
    }

    @Test
    fun `a refused foreground notification must never skip the due claim`() {
        // Device evidence 2026-10-28: a backgrounded process is denied the WorkManager foreground
        // service ("startForegroundService() not allowed ... mAllowStartForeground false"), the
        // onDue hook threw, WorkflowWakeRunner reported Failed and runDue() never ran - the due
        // slot stayed unclaimed for the whole observation window. The notification is a hint; the
        // claim is the contract.
        assertTrue(
            "the due hook must tolerate a refused foreground promotion",
            worker.contains("runCatching { setForeground(foreground()) }")
        )
        assertFalse(
            "setForeground must not sit unguarded in the runner's due hook",
            worker.contains("runner.run { setForeground")
        )
        assertTrue(
            "the claim still runs after a refused promotion",
            worker.contains("runner.run { promoteToForeground() }")
        )
    }

    @Test
    fun `a failed wake install must name its cause instead of returning a bare false`() {
        // Device runs 7, 8 and 9 each ended with only "schedules are durable but their wakes are
        // not armed": schedule() returned false from an exception it discarded, so neither the
        // WorkManager state nor the harness could tell the cause apart.
        assertFalse(
            "the wake adapter must not discard the failure it reports",
            scheduler.contains("catch (_: Throwable)")
        )
        assertEquals(
            "the one-time install and the sweep each log their own throwable",
            2,
            Regex("catch \\(failure: Throwable\\)").findAll(scheduler).count()
        )
        assertEquals(
            "the cancel path logs instead of staying silent",
            1,
            Regex("onFailure \\{ failure ->").findAll(scheduler).count()
        )
        assertEquals(
            "all three failure paths reach logcat under one tag",
            3,
            Regex("Log\\.w\\(LOG_TAG,").findAll(scheduler).count()
        )
        assertTrue(
            "the log tag stays distinct from the WorkManager work tag",
            scheduler.contains("const val LOG_TAG =")
        )
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

    /**
     * Removes comments and literals so a rule only sees executable code. Kotlin block comments
     * nest; string templates are dropped with their literal, which is intended for these checks.
     */
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
            when {
                text[index] == '\\' -> index += 2
                text[index] == quote -> return index + 1
                else -> index += 1
            }
        }
        return index
    }

    private fun importLines(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .toList()

    private companion object {
        const val WORKER = "app/src/main/java/com/muyuchat/mca/WorkflowWakeWorker.kt"
        const val SCHEDULER = "app/src/main/java/com/muyuchat/mca/WorkManagerWorkflowWakeScheduler.kt"
        const val RUNNER = "app/src/main/java/com/muyuchat/mca/agent/workflow/WorkflowWakeRunner.kt"
        const val OWNER = "app/src/main/java/com/muyuchat/mca/composition/McaProcessRuntimeOwner.kt"
        const val ASSEMBLY = "app/src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt"
        const val SERVICE = "app/src/main/java/com/muyuchat/mca/agent/workflow/WorkflowScheduleService.kt"
        const val VIEW_MODEL = "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"

        val forbiddenRuntimeConstructors = listOf(
            "MainRuntimeAssembly",
            "AgentRuntimeAssembly",
            "McaAppContainer",
            "McaProcessRuntimeRoot",
            "WorkflowScheduleService"
        )
    }
}

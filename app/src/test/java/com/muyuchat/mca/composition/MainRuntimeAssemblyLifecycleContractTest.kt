package com.muyuchat.mca.composition

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level lifecycle contract for the P0 assembly split.
 *
 * The split moved DSH and Agent wiring out of `MainRuntimeAssembly` without changing behaviour, so
 * this test pins the properties that made the move safe and that a compile alone cannot prove:
 *
 *  1. the app shell still creates exactly one DSH host object and never names the binder/Node host;
 *  2. a DSH phase query answers from the lazy handle's initialization state, so a user who never
 *     opened DSH never constructs the binder, gateway, bridge or maintenance owner;
 *  3. the screen-capture consent projection stays eagerly available and Android-free;
 *  4. there is exactly one production construction site of the account lane (one owner per process);
 *  5. the Agent settlement order is preserved: runtime close -> transport release -> scope cancel.
 *
 * This is static wiring evidence, not a runtime proof. Duplicate entry, rotation and close-order
 * observation on a device remain NOT_RUN until a device round records them.
 */
class MainRuntimeAssemblyLifecycleContractTest {

    private val main = source("app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt")
    private val host = source("app/src/main/java/com/muyuchat/mca/composition/DshHostAssembly.kt")
    private val agent = source("app/src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt")
    private val viewModel = source("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
    private val processRoot = source("app/src/main/java/com/muyuchat/mca/composition/McaProcessRuntimeRoot.kt")
    private val accountComposition = source("app/src/main/java/com/muyuchat/mca/account/DshAccountLoginComposition.kt")

    @Test
    fun `the shell owns one DSH host and never constructs the binder or host service`() {
        assertEquals(1, countOccurrences(main, "DshHostAssembly("))
        assertTrue(main.contains("internal val dshHost: DshHostAssembly = DshHostAssembly("))
        assertFalse("the shell must not construct the :dsh client", main.contains("DshRuntimeServiceClient("))
        assertFalse("the shell must not construct the host mode", main.contains("DshModeAssembly("))
        assertFalse("the shell must not construct the gateway", main.contains("DshGatewayAssembly("))
    }

    @Test
    fun `a DSH phase query answers without initializing the runtime`() {
        assertTrue(host.contains("private val modeAssemblyLazy: Lazy<DshModeAssembly> = lazy("))
        assertTrue(host.contains("private val modeAssembly: DshModeAssembly by modeAssemblyLazy"))
        assertTrue(host.contains("if (modeAssemblyLazy.isInitialized())"))
        // The readiness guard is the only phase read; it must not fall through to a construction.
        val body = functionBody(host, "internal fun runtimePhaseOrNull(): DshRuntimePhase?")
        assertTrue(body.contains("modeAssemblyLazy.isInitialized()"))
        assertTrue(body.contains("modeAssembly.runtimeClient.snapshot.value.phase"))
        // Every costly generation dependency is lazy; only the consent projection is eager.
        val lazyCount = countOccurrences(host, "by lazy(LazyThreadSafetyMode.SYNCHRONIZED)")
        assertTrue("expected the generation owners to stay lazy, found $lazyCount", lazyCount >= 7)
    }

    @Test
    fun `the screen capture consent projection stays eager and Android-free`() {
        assertTrue(host.contains("internal val screenCaptureConsent: DshScreenCaptureConsentController ="))
        assertFalse(
            "the consent controller must not be constructed lazily behind the runtime",
            host.contains("screenCaptureConsent: DshScreenCaptureConsentController by lazy")
        )
        assertTrue(host.contains("isGrantLive = { capabilityAssembly.isScreenCaptureGranted() }"))
    }

    @Test
    fun `the account lane has exactly one production construction site`() {
        val appSources = productionSources()
        val sites = appSources.filterValues { text -> text.contains("createDshAccountLoginOwner(") }
        assertEquals(
            "the account owner must be created once per app process, not per page",
            mapOf(
                "com/muyuchat/mca/account/DshAccountLoginComposition.kt" to 1,
                "com/muyuchat/mca/composition/DshHostAssembly.kt" to 1
            ),
            sites.mapValues { (_, text) -> countOccurrences(text, "createDshAccountLoginOwner(") }
        )
        assertTrue(accountComposition.contains("DshAccountLoginOwner("))
        // The shell must reach the lane through the typed host entry, never by building a second one.
        assertTrue(viewModel.contains("runtimeAssembly.dshHost.accountLoginPort"))
    }

    @Test
    fun `the Agent settlement order is preserved by the new boundary`() {
        val body = functionBody(agent, "fun close()")
        val runtimeClose = body.indexOf("agentRuntimeRef?.close()")
        val transport = body.indexOf("connectionPool.evictAll()")
        val executor = body.indexOf("dispatcher.executorService.shutdown()")
        val scopeCancel = body.indexOf("agentOwnerJob.cancel()")
        assertTrue("runtime close must come first", runtimeClose >= 0)
        assertTrue("transport release follows the runtime close", transport > runtimeClose)
        assertTrue(executor > transport)
        assertTrue("the owner scope must be cancelled last", scopeCancel > executor)
        // The agent owner scope lives in the Agent boundary, not in the ViewModel.
        assertTrue(agent.contains("SupervisorJob(parentScope.coroutineContext[Job])"))
        // ADR-0008: the process root is the settlement caller; page teardown never closes it.
        assertTrue(processRoot.contains("assembly.close()"))
        assertFalse(viewModel.contains("agentHost.close()"))
    }

    private fun productionSources(): Map<String, String> {
        val root = File(projectRoot(), "app/src/main/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { file -> file.relativeTo(root).invariantSeparatorsPath to file.readText(Charsets.UTF_8) }
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "missing signature: $signature" }
        val next = source.indexOf("\n    fun ", start + signature.length)
        val nextInternal = source.indexOf("\n    internal fun ", start + signature.length)
        val nextPrivate = source.indexOf("\n    private fun ", start + signature.length)
        val end = listOf(next, nextInternal, nextPrivate)
            .filter { it > start }
            .minOrNull() ?: source.length
        return source.substring(start, end)
    }

    private fun countOccurrences(text: String, token: String): Int {
        var count = 0
        var index = text.indexOf(token)
        while (index >= 0) {
            count++
            index = text.indexOf(token, index + token.length)
        }
        return count
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found from ${System.getProperty("user.dir")}")
    }
}


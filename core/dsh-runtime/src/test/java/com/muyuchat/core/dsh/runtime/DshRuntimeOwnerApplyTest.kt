package com.muyuchat.core.dsh.runtime

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.log.DshOutputRing
import com.muyuchat.core.dsh.runtime.payload.DshPayloadCriticalFile
import com.muyuchat.core.dsh.runtime.payload.DshPayloadInstallResult
import com.muyuchat.core.dsh.runtime.payload.DshPayloadManifest
import com.muyuchat.core.dsh.runtime.payload.DshPayloadProvisioner
import com.muyuchat.core.dsh.runtime.payload.DshPayloadSourceInfo
import com.muyuchat.core.dsh.runtime.process.DshCommandResult
import com.muyuchat.core.dsh.runtime.process.DshNodeExecStrategy
import com.muyuchat.core.dsh.runtime.process.DshNodeExecutable
import com.muyuchat.core.dsh.runtime.process.DshNodeHost
import com.muyuchat.core.dsh.runtime.process.DshProcessRunner
import com.muyuchat.core.dsh.runtime.process.DshStopOutcome
import com.muyuchat.core.dsh.runtime.readiness.DshHttpProbe
import com.muyuchat.core.dsh.runtime.readiness.DshHttpResponse
import com.muyuchat.core.dsh.runtime.readiness.DshReadinessGate
import com.muyuchat.core.dsh.runtime.state.DshApplyRequest
import com.muyuchat.core.dsh.runtime.state.DshApplyStatus
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the binding reload on a real [DshRuntimeOwner] over fake process seams.
 *
 * These tests pin the defect this slice fixes: an already-READY Node host used to return from
 * `ensureStarted` immediately, so a new gateway port/token or model catalog never reached the
 * running process. They also pin what must NOT happen: a reload is not claimed when the new
 * generation did not reach READY, in-flight work is not cut without an explicit force, and a
 * failed reload restores the previous binding instead of leaving a half-applied configuration.
 */
class DshRuntimeOwnerApplyTest {

    private class FakeHost : DshNodeHost {
        private val launch = DshLaunchEntry(
            authenticatedUrl = "http://127.0.0.1:4321/?token=abcdefghijklmnopqrst",
            origin = "http://127.0.0.1:4321",
            port = 4321
        )

        override val output = DshOutputRing(10)
        override val isAlive: Boolean get() = alive
        override val exitCode: Int? get() = null
        override val processId: Int? get() = 4242

        var alive = false
        var starts = 0
        var stops = 0
        var nextStopOutcome: DshStopOutcome? = null

        override fun start(
            executable: DshNodeExecutable,
            arguments: List<String>,
            environment: Map<String, String>,
            workingDir: File
        ) {
            starts++
            alive = true
        }

        override fun awaitLaunchEntry(timeoutMillis: Long, shouldAbort: () -> Boolean) = launch

        override fun stop(graceMillis: Long, killMillis: Long): DshStopOutcome {
            stops++
            alive = false
            return nextStopOutcome ?: DshStopOutcome(true, false, false, "test")
        }

        override fun tail(lines: Int) = ""
    }

    private inner class ApplyDeps(private val root: File) : DshRuntimeOwner.Deps {
        var endpoint: DshGatewayEndpoint? = gateway("token-1", "rev-1")
        var hostBuild: String = "engine-1"
        var failAllSeeds: Boolean = false
        val failSeedFor: MutableSet<String> = mutableSetOf()
        val failSeedForRevisions: MutableSet<String> = mutableSetOf()

        var seedCalls: Int = 0
        var hostStarts: Int = 0
        val seededEndpoints = mutableListOf<DshGatewayEndpoint?>()
        val logs = mutableListOf<String>()
        val host = FakeHost()

        override val payload = DshPayloadProvisioner {
            DshPayloadInstallResult.Installed(
                manifest = DshPayloadManifest(
                    manifestVersion = 1,
                    payloadVersion = "dsh-test",
                    archiveAsset = "dsh/payload.zip",
                    archiveSize = 1,
                    archiveSha256 = "a".repeat(64),
                    nodeLibraryName = "libdshnode.so",
                    nodeSize = 1,
                    nodeSha256 = "b".repeat(64),
                    kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                    runtimeLibraryDir = "runtime/lib",
                    fileCount = 1,
                    criticalFiles = listOf(DshPayloadCriticalFile("x", 1, "a".repeat(64))),
                    source = DshPayloadSourceInfo(null, null, null),
                    licenses = emptyList()
                ),
                archiveSha256 = "a".repeat(64),
                reused = true
            )
        }

        override val execStrategy = DshNodeExecStrategy(FakeRunner())
        override val readinessGate = DshReadinessGate(
            probe = DshHttpProbe { url, cookie ->
                when {
                    url.contains("?token=") -> DshHttpResponse(
                        303, "", null,
                        mapOf(
                            "Set-Cookie" to listOf("dsh-auth-x=v1; Max-Age=1; HttpOnly; SameSite=Strict"),
                            "Location" to listOf("./")
                        )
                    )

                    url.contains("/plugins/") ->
                        DshHttpResponse(200, "x".repeat(600), "text/javascript", emptyMap())

                    cookie == null -> DshHttpResponse(401, "no", "text/plain", emptyMap())

                    else -> DshHttpResponse(
                        200,
                        "<script>__DSH_BOOT_READY__</script>" +
                            "<script src=\"/plugins/@deepseek-ai/dsh-client-modules/client.js\"></script>",
                        "text/html",
                        emptyMap()
                    )
                }
            },
            stabilityDelayMillis = 0,
            readinessTimeoutMillis = 500,
            retryDelayMillis = 5,
            sleeper = { _ -> }
        )
        override val supportedAbis = listOf("arm64-v8a")
        override val packagedAbis = listOf("arm64-v8a")

        override suspend fun gatewayEndpoint(): DshGatewayEndpoint? = endpoint

        override fun hostBuildRevision(): String = hostBuild

        override suspend fun seedDshHome(endpoint: DshGatewayEndpoint): Boolean {
            seedCalls++
            seededEndpoints += endpoint
            return !failAllSeeds &&
                endpoint.baseUrl !in failSeedFor &&
                endpoint.catalogRevision !in failSeedForRevisions
        }

        override suspend fun seedDshHomeAccountOnly(): Boolean {
            seedCalls++
            seededEndpoints += null
            return !failAllSeeds
        }

        override fun nativeLibraryDir(): File = root

        override fun newNodeHost(): DshNodeHost {
            hostStarts++
            return host
        }

        override fun isProcessAlive(pid: Int): Boolean = false

        override fun findOrphanedHostProcesses(): List<Int> = emptyList()

        override fun prepareRuntimeLinks(executable: DshNodeExecutable, payloadRoot: File) {}

        override fun log(line: String) {
            logs += line
        }
    }

    private class FakeRunner : DshProcessRunner {
        override fun run(
            argv: List<String>,
            environment: Map<String, String>,
            workingDir: File?,
            timeoutMillis: Long
        ): DshCommandResult = DshCommandResult(0, "v26.4.0\n", "", false)
    }

    private fun root(): File = java.nio.file.Files.createTempDirectory("dsh-apply").toFile().also {
        File(it, "libdshnode.so").writeBytes(byteArrayOf(1))
    }

    private fun config(root: File) = DshRuntimeConfig(
        dataRoot = root,
        authLineTimeoutMillis = 2_000,
        readinessTimeoutMillis = 2_000,
        nodeExecProbeTimeoutMillis = 2_000,
        stopGraceMillis = 1_000,
        stopKillMillis = 500
    ).validated()

    private fun gateway(token: String, revision: String, port: Int = 9) =
        DshGatewayEndpoint(
            baseUrl = "http://127.0.0.1:$port/v1",
            bearerToken = token,
            catalogRevision = revision
        )

    /**
     * An explicit "the caller observed zero in-flight calls" request.
     *
     * These tests exercise the reload/rollback machinery, not the observation: the tri-state
     * contract keeps an *unobservable* request deferred on a READY generation, so a test that
     * expects an immediate replacement must state that it observed an idle generation.
     */
    private fun observedIdle() = DshApplyRequest(inFlightCalls = 0, source = "test-observed-idle")

    @Test
    fun anUnchangedBindingIsReusedAndRepeatedApplyIsIdempotent() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)

        val first = owner.ensureApplied()
        assertEquals(DshApplyStatus.APPLIED, first.status)
        assertEquals(1L, first.epoch)
        assertEquals(DshRuntimePhase.READY, first.snapshot.phase)
        assertEquals(1, deps.hostStarts)
        assertEquals(1, deps.seedCalls)

        val second = owner.ensureApplied()
        assertEquals(DshApplyStatus.UNCHANGED, second.status)
        assertEquals(1L, second.epoch)
        assertEquals(first.requested, second.applied)
        // No second process, no second seed, no epoch churn.
        assertEquals(1, deps.hostStarts)
        assertEquals(1, deps.seedCalls)

        val attached = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, attached.phase)
        assertEquals(1L, attached.epoch)
        assertEquals(1, deps.hostStarts)
        assertNotNull(owner.webEntry())
        owner.stop()
    }

    @Test
    fun aChangedGatewayOrCatalogIsAppliedAndConfirmedByANewGeneration() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val first = owner.ensureApplied()
        val firstBinding = first.applied
        assertNotNull(firstBinding)

        // Model list changed but the loopback endpoint (port + token) did not.
        deps.endpoint = gateway("token-1", "rev-2")
        val catalogChanged = owner.ensureApplied(observedIdle())
        assertEquals(DshApplyStatus.APPLIED, catalogChanged.status)
        assertEquals("model-catalog", catalogChanged.change)
        assertEquals(2L, catalogChanged.epoch)
        assertEquals(2, deps.hostStarts)
        assertEquals(gateway("token-1", "rev-2"), deps.seededEndpoints.last())
        assertEquals(2L, owner.webEntry()?.epoch)

        // The gateway was rebound to a new port + token (main-process rebuild shape).
        deps.endpoint = gateway("token-2", "rev-2", port = 90)
        val rebound = owner.ensureApplied(observedIdle())
        assertEquals(DshApplyStatus.APPLIED, rebound.status)
        assertEquals("gateway-port+gateway-token", rebound.change)
        assertEquals(3L, rebound.epoch)
        assertEquals(3, deps.hostStarts)
        assertEquals(gateway("token-2", "rev-2", port = 90), deps.seededEndpoints.last())
        assertNotEquals(firstBinding, rebound.applied)
        owner.stop()
    }

    @Test
    fun inFlightWorkDefersTheReloadAndAForcedApplyTerminatesIt() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val first = owner.ensureApplied()
        deps.endpoint = gateway("token-2", "rev-2")

        val deferred = owner.ensureApplied(DshApplyRequest(inFlightCalls = 3))
        assertEquals(DshApplyStatus.DEFERRED_IN_FLIGHT, deferred.status)
        assertNull(deferred.applied)
        assertEquals(first.applied, deferred.retained)
        assertEquals(1L, deferred.epoch)
        assertEquals(DshRuntimePhase.READY, deferred.snapshot.phase)
        assertEquals(1, deps.hostStarts)
        assertEquals(1, deps.seedCalls)
        // The old generation keeps its authenticated entry: nothing was torn down.
        assertNotNull(owner.webEntry())

        // A forced apply is the explicit decision to cut the in-flight work instead of leaving
        // it on a gateway generation whose token is about to be replaced.
        val forced = owner.ensureApplied(DshApplyRequest(inFlightCalls = 3, force = true))
        assertEquals(DshApplyStatus.APPLIED, forced.status)
        assertEquals(3, forced.inFlightTerminated)
        assertEquals(2L, forced.epoch)
        assertEquals(2, deps.hostStarts)
        assertEquals(2L, owner.webEntry()?.epoch)
        owner.stop()
    }

    @Test
    fun unobservableWorkDefersTheReloadInsteadOfReadingItAsZero() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val first = owner.ensureApplied()

        deps.endpoint = gateway("token-1", "rev-2")
        // No in-flight observation at all: not the same fact as "observed zero calls".
        val deferred = owner.ensureApplied(DshApplyRequest(inFlightCalls = null))

        assertEquals(DshApplyStatus.DEFERRED_IN_FLIGHT, deferred.status)
        assertTrue(deferred.inFlightUnobservable)
        assertNull(deferred.inFlightCalls)
        assertNull(deferred.applied)
        assertEquals(first.applied, deferred.retained)
        assertEquals(1L, deferred.epoch)
        assertEquals(1, deps.hostStarts)
        // The live generation keeps its authenticated entry: nothing was cut or replaced.
        assertNotNull(owner.webEntry())
        owner.stop()
    }

    @Test
    fun aFailedApplyRestoresThePreviousBindingWhenItsGatewayIsStillUsable() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val first = owner.ensureApplied()
        val previousBinding = first.applied

        // A catalog-only change keeps the same gateway generation, so the previous binding can be
        // restored on a failure; the new home is refused (for example a provider schema change).
        deps.failSeedForRevisions += "rev-2"
        deps.endpoint = gateway("token-1", "rev-2")
        val rolledBack = owner.ensureApplied(observedIdle())

        assertEquals(DshApplyStatus.ROLLED_BACK, rolledBack.status)
        assertNotNull(rolledBack.detail)
        assertEquals(previousBinding, rolledBack.applied)
        assertEquals(previousBinding, rolledBack.retained)
        assertNotEquals(rolledBack.requested, rolledBack.applied)
        assertEquals(DshRuntimePhase.READY, rolledBack.snapshot.phase)
        // The failed attempt seeded the new revision, the rollback seeded the previous one.
        assertEquals("rev-2", deps.seededEndpoints[deps.seededEndpoints.size - 2]?.catalogRevision)
        assertEquals("rev-1", deps.seededEndpoints.last()?.catalogRevision)
        // One process for the first generation, one for the restored generation.
        assertEquals(2, deps.hostStarts)
        assertEquals(3L, rolledBack.epoch)
        assertEquals(3L, owner.webEntry()?.epoch)
        owner.stop()
    }

    @Test
    fun aFailedRebindDoesNotRestoreAHostWhoseGatewayIsGone() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val first = owner.ensureApplied()
        val previousBinding = first.applied

        // The app process started a new gateway generation (new port), so restoring the previous
        // binding would give a READY host with a dead model channel. That is not a success.
        deps.failSeedFor += "http://127.0.0.1:90/v1"
        deps.endpoint = gateway("token-2", "rev-2", port = 90)
        val failed = owner.ensureApplied(observedIdle())

        assertEquals(DshApplyStatus.FAILED, failed.status)
        assertNull(failed.applied)
        assertEquals(previousBinding, failed.retained)
        assertEquals(DshRuntimePhase.FAILED, failed.snapshot.phase)
        // The attempted generation failed its seed, so no second Node process was created.
        assertEquals(1, deps.hostStarts)
        assertNull(owner.webEntry())
    }

    @Test
    fun aFailedApplyAndFailedRollbackNeverClaimASuccessfulReload() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val first = owner.ensureApplied()
        val previousBinding = first.applied

        // Catalog-only change (rollback is allowed) whose seed fails for both bindings.
        deps.failAllSeeds = true
        deps.endpoint = gateway("token-1", "rev-2")
        val failed = owner.ensureApplied(observedIdle())

        assertEquals(DshApplyStatus.FAILED, failed.status)
        assertNull(failed.applied)
        assertEquals(previousBinding, failed.retained)
        assertEquals(DshRuntimePhase.FAILED, failed.snapshot.phase)
        assertEquals(DshFailureCode.GATEWAY_UNAVAILABLE, failed.snapshot.failure?.code)
        // No Node process was created for either attempt, so nothing is claimed or leaked.
        assertEquals(1, deps.hostStarts)
        assertNull(owner.webEntry())

        // The failure is retryable: once the seed succeeds the requested binding is applied.
        deps.failAllSeeds = false
        val retried = owner.ensureApplied()
        assertEquals(DshApplyStatus.APPLIED, retried.status)
        assertEquals(DshRuntimePhase.READY, retried.snapshot.phase)
        assertEquals(2, deps.hostStarts)
        owner.stop()
    }

    @Test
    fun anUnobservableGatewayDoesNotTearDownTheReadyGeneration() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val first = owner.ensureApplied()

        // The catalog read failed / the gateway is gone: an unobservable state, not a request to
        // switch the host to account-only mode.
        deps.endpoint = null
        val deferred = owner.ensureApplied()

        assertEquals(DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE, deferred.status)
        assertNull(deferred.applied)
        assertEquals(first.applied, deferred.retained)
        assertEquals(1L, deferred.epoch)
        assertEquals(DshRuntimePhase.READY, owner.snapshot.value.phase)
        assertEquals(1, deps.hostStarts)
        assertEquals(1, deps.seedCalls)
        assertNotNull(owner.webEntry())

        // An explicit force is the only way to move a live model channel to account-only mode.
        val forced = owner.ensureApplied(DshApplyRequest(force = true))
        assertEquals(DshApplyStatus.APPLIED, forced.status)
        assertEquals("gateway-unbound", forced.change)
        assertEquals(2L, forced.epoch)
        assertNull(deps.seededEndpoints.last())
        owner.stop()
    }

    @Test
    fun aMainProcessRebuildWithAnIdenticalCatalogStillReloads() = runTest {
        val root = root()
        val deps = ApplyDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshApplyStatus.APPLIED, owner.ensureApplied().status)

        // Same port, same token, same catalog, but a new composition behind the gateway.
        deps.hostBuild = "engine-2"
        val rebuilt = owner.ensureApplied(observedIdle())

        assertEquals(DshApplyStatus.APPLIED, rebuilt.status)
        assertEquals("host-build", rebuilt.change)
        assertEquals(2L, rebuilt.epoch)
        assertEquals(2, deps.hostStarts)
        assertTrue(deps.logs.any { it.contains("host-build") })
        owner.stop()
    }
}

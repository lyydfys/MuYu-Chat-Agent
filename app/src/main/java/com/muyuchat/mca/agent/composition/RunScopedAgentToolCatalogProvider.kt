package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import com.muyuchat.core.agent.runtime.run.AgentRecordedCatalog
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpClientFactory
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpEgressDecision
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpEgressRequest
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpRegistry
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpRunGrantBinder
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerLoad
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerRecord
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerStore
import com.muyuchat.mca.agent.extensions.mcp.AgentNetworkEgressGuard
import com.muyuchat.mca.agent.security.AgentEgressDecision
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressGrantStore
import com.muyuchat.mca.agent.security.AgentEgressOrigins
import com.muyuchat.mca.agent.security.AgentEgressScope
import com.muyuchat.mca.agent.security.AgentEgressTransport
import com.muyuchat.mca.agent.security.AgentNetworkEgressPolicy
import com.muyuchat.mca.agent.security.AgentOriginResult
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssembler
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssemblyResult
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adds the MCP tools authorized for exactly one run on top of an existing per-run base catalog.
 *
 * Authorization contract (P0 security review F-02):
 * - **This provider never mints a grant from a config toggle.** When a [grantBinder] is supplied it
 *   is the one explicit run-start binding step: it converts a user-scoped [AgentMcpAuthorization]
 *   (exact canonical origin/transport + explicit data classes) into a run-scoped grant. No durable
 *   authorization means [delegate] and no MCP registry/socket.
 * - MCP is usable only when [grantStore] contains an active, run-scoped grant created for this exact
 *   run. A usable grant must match this `runId` (and `taskId` when set), the current policy version,
 *   the transport class and the canonical origin, and it must cover [AgentDataClass.PRIVATE].
 * - LOCAL_ONLY / offline-forced runs never open a socket, even when a local grant exists.
 * - [delegate] is the already-composed base chain (built-ins + Skills + memory), so this class never
 *   rebuilds or widens that surface; it merges only MCP adapters and rejects any key conflict.
 * - [release] revokes every run-scoped grant observed for this execution, closes the registry and
 *   then releases [delegate], so a completed execution's authorization cannot be reused.
 *
 * Approval status: every MCP tool stays `EXTERNAL_SIDE_EFFECT`, so the existing per-call approval
 * (`AgentPermissionBroker` + invocation ledger) still decides execution. This provider does not
 * bypass approvals and must not be treated as evidence that a real MCP server was tested.
 */
class RunScopedAgentToolCatalogProvider(
    private val delegate: AgentRunToolCatalogProvider,
    private val serverStore: AgentMcpServerStore,
    private val grantStore: AgentEgressGrantStore,
    private val grantBinder: AgentMcpRunGrantBinder? = null,
    private val credentialProvider: com.muyuchat.mca.agent.extensions.mcp.McpCredentialProvider =
        com.muyuchat.mca.agent.extensions.mcp.McpCredentialProvider { null },
    private val clientFactory: AgentMcpClientFactory = AgentMcpClientFactory.default(credentialProvider),
    private val schemaValidator: SupportedToolSchemaValidator = SupportedToolSchemaValidator(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AgentRunToolCatalogProvider {
    private data class RunCatalog(
        val catalog: AgentToolCatalog,
        val registry: AgentMcpRegistry?
    )

    private val policy = AgentNetworkEgressPolicy()
    private val assembler = AgentToolCatalogAssembler()
    private val mutex = Mutex()
    private val active = LinkedHashMap<String, RunCatalog>()
    private val runGrantIds = LinkedHashMap<String, LinkedHashSet<String>>()

    override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog =
        mutex.withLock {
            active[run.runId]?.catalog ?: run {
                grantBinder?.bind(run, task)
                resolveLocked(run, delegate.catalogFor(run, task))
            }
        }

    /**
     * Restore path: reuse the current base + MCP surface only as far as it still matches the
     * recorded identities. A new durable authorization may be re-bound here (it is user-scoped and
     * still valid), but the filtering step guarantees the restored set is never wider than recorded.
     */
    override suspend fun catalogForRecorded(
        run: AgentRun,
        task: AgentTask,
        recorded: AgentRecordedCatalog
    ): AgentToolCatalog? = try {
        com.muyuchat.core.agent.runtime.run.AgentToolCatalogFiltering.filter(
            catalogFor(run, task),
            recorded
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    override suspend fun release(run: AgentRun) {
        val (registry, grantIds) = mutex.withLock {
            val activeCatalog = active.remove(run.runId)
            val grants = runGrantIds.remove(run.runId)?.toList().orEmpty()
            activeCatalog?.registry to grants
        }
        try {
            // Revoke before closing so a resumed execution cannot observe an active grant even if
            // closing the registry takes time. Revoke is the store's CAS API; no run-wide shortcut
            // exists on the narrow port.
            revokeRunGrants(grantIds)
        } finally {
            try {
                registry?.let { closeQuietly(it) }
            } finally {
                delegate.release(run)
            }
        }
    }

    private suspend fun resolveLocked(run: AgentRun, baseCatalog: AgentToolCatalog): AgentToolCatalog {
        val now = nowMillis()
        val runGrants = activeRunGrants(run, now)
        if (runGrants.isNotEmpty()) {
            runGrantIds.getOrPut(run.runId) { LinkedHashSet() }
                .addAll(runGrants.map { it.grantId })
        }
        if (runGrants.isEmpty()) return baseCatalog
        if (run.policy.networkPolicy != AgentNetworkPolicy.NETWORK_ENABLED || run.policy.offlineForced) {
            return baseCatalog
        }
        val usableGrants = runGrants.filter { grant ->
            grant.policyVersion == run.policy.policyVersion &&
                (grant.expiresAtMillis == null || grant.expiresAtMillis > now)
        }
        if (usableGrants.isEmpty()) return baseCatalog
        // MCP tools are always EXTERNAL_SIDE_EFFECT/PRIVATE; a grant that does not cover PRIVATE
        // cannot authorize their egress even if it names the same origin.
        val privateGrants = usableGrants.filter { AgentDataClass.PRIVATE in it.allowedDataClasses }
        if (privateGrants.isEmpty()) return baseCatalog

        val servers = when (val loaded = serverStore.load()) {
            is AgentMcpServerLoad.Loaded -> loaded.servers.filter {
                it.enabled && it.allowAgentNetwork
            }
            is AgentMcpServerLoad.Corrupt -> emptyList()
        }
        if (servers.isEmpty()) return baseCatalog

        val scope = AgentEgressScope(
            runId = run.runId,
            networkPolicy = run.policy.networkPolicy,
            policyVersion = run.policy.policyVersion
        )
        val allowedOrigins = LinkedHashSet<String>()
        val eligibleServerIds = ArrayList<String>(servers.size)
        for (server in servers) {
            val transport = transportOf(server)
            val canonical = canonicalOrigin(server, transport) ?: continue
            // Reuse the policy's typed decision instead of re-deriving grant eligibility here.
            val decision = policy.checkTransport(scope, transport, canonical, privateGrants)
            if (decision !is AgentEgressDecision.Allowed) continue
            allowedOrigins += canonical
            eligibleServerIds += server.serverId
        }
        if (eligibleServerIds.isEmpty()) return baseCatalog

        val grantedOrigins: Set<String> = allowedOrigins.toSet()
        val registry = AgentMcpRegistry(
            store = serverStore,
            egressGuard = AgentNetworkEgressGuard { request -> guardEgress(request, grantedOrigins) },
            clientFactory = clientFactory,
            schemaValidator = schemaValidator
        )
        try {
            coroutineScope {
                eligibleServerIds.map { id -> async { registry.connect(id) } }.awaitAll()
            }
            val adapters: List<AgentToolExecutor> = registry.snapshot().adapters
            if (adapters.isEmpty()) {
                closeQuietly(registry)
                return baseCatalog
            }
            val assembly = assembler.assemble(
                builtInCatalog = baseCatalog,
                // The delegate already applied this run's Skill narrowing; passing no Skills here
                // preserves exactly that surface and only merges the MCP adapters.
                enabledSkills = emptyList(),
                mcpTools = adapters
            )
            if (assembly !is AgentToolCatalogAssemblyResult.Assembled) {
                closeQuietly(registry)
                return baseCatalog
            }
            active[run.runId] = RunCatalog(assembly.catalog, registry)
            return assembly.catalog
        } catch (cancelled: CancellationException) {
            closeQuietly(registry)
            throw cancelled
        } catch (_: Throwable) {
            closeQuietly(registry)
            return baseCatalog
        }
    }

    /**
     * Active grants that are scoped to this exact run. Storage-level filters (revoked/expired) are
     * re-checked defensively; a store failure fails closed. Task-scoped templates (`runId == null`)
     * are not consumed directly: only [grantBinder] may turn an explicit durable server
     * authorization into a run-scoped grant.
     */
    private suspend fun activeRunGrants(run: AgentRun, nowMillis: Long): List<AgentEgressGrant> {
        val grants = try {
            grantStore.listActiveForTask(run.taskId, nowMillis, MAX_GRANTS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return emptyList()
        }
        return grants.filter { grant ->
            grant.runId == run.runId &&
                !grant.revoked &&
                (grant.taskId == null || grant.taskId == run.taskId)
        }
    }

    private suspend fun revokeRunGrants(grantIds: List<String>) {
        if (grantIds.isEmpty()) return
        val now = nowMillis()
        for (grantId in grantIds) {
            try {
                grantStore.revoke(grantId, now, RUN_END_REVOKE_REASON)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Release is best-effort by contract (AgentRunCatalogRelease) and must not replace
                // the run outcome. The grant remains run-scoped and policy-version fenced; revoke
                // failures are surfaced only through the store's own diagnostics.
            }
        }
    }

    private fun guardEgress(
        request: AgentMcpEgressRequest,
        allowedOrigins: Set<String>
    ): AgentMcpEgressDecision =
        if (request.origin in allowedOrigins) {
            AgentMcpEgressDecision.Allowed
        } else {
            AgentMcpEgressDecision.Denied(
                "MCP_EGRESS_NOT_GRANTED",
                "No explicit run-scoped egress grant exists for this MCP origin."
            )
        }

    private fun transportOf(server: AgentMcpServerRecord): AgentEgressTransport =
        if (server.allowLocal) AgentEgressTransport.LOCAL_SERVER else AgentEgressTransport.REMOTE_MCP

    /**
     * Derives the canonical *origin* from a server endpoint. [AgentNetworkEgressPolicy] rejects an
     * origin with a path, but MCP endpoints normally look like `https://host/mcp`; the transport
     * path is not part of the egress grant (which is an origin), so the path is dropped here first.
     * The LOCAL_SERVER/REMOTE_MCP address-class rules are re-checked after canonicalization so a
     * mislabelled server can never be connected under the wrong transport class.
     */
    private fun canonicalOrigin(
        server: AgentMcpServerRecord,
        transport: AgentEgressTransport
    ): String? {
        val uri = runCatching { URI(server.endpoint.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank()) return null
        val port = uri.port
        if (port != -1 && port !in 1..65_535) return null
        val candidate = if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
        val canonical = when (val result = policy.canonicalOrigin(candidate, transport)) {
            is AgentOriginResult.Valid -> result.origin
            is AgentOriginResult.Invalid -> return null
        }
        if (transport == AgentEgressTransport.LOCAL_SERVER && !AgentEgressOrigins.isLocalOrPrivate(canonical)) {
            return null
        }
        if (transport == AgentEgressTransport.REMOTE_MCP && AgentEgressOrigins.isLocalOrPrivate(canonical)) {
            return null
        }
        return canonical
    }

    private suspend fun closeQuietly(registry: AgentMcpRegistry) {
        try {
            registry.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The registry is being discarded; one failed close must not hide the run outcome.
        }
    }

    private companion object {
        const val MAX_GRANTS = 200
        const val RUN_END_REVOKE_REASON = "mcp_run_released"
    }
}

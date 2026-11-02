package com.muyuchat.mca.agent.extensions.mcp

import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Factory seam so tests can build an [McpClient] over a fake transport or a loopback fixture. */
fun interface AgentMcpClientFactory {
    fun create(config: McpServerConfig): McpClient

    companion object {
        fun default(
            credentials: McpCredentialProvider = McpCredentialProvider { null }
        ): AgentMcpClientFactory {
            val sharedClient = OkHttpMcpTransport.defaultClient()
            return AgentMcpClientFactory { config ->
                McpClient(
                    config = config,
                    transport = OkHttpMcpTransport(sharedClient),
                    credentials = credentials
                )
            }
        }
    }
}

/**
 * One observed catalog drift for an already-connected server.
 *
 * Keys are full namespaced tool keys (`mcp:<serverId>:<remoteName>`), sorted; [generation] is the
 * connection-catalog generation that produced this change. Removing/disabling a server or an
 * initial connect never produces this event.
 */
data class AgentMcpCatalogChange(
    val serverId: String,
    val generation: Long,
    val addedKeys: List<String>,
    val removedKeys: List<String>,
    val changedSchemaKeys: List<String>
) {
    val affectedKeys: List<String>
        get() = (addedKeys + removedKeys + changedSchemaKeys).distinct().sorted()
}

/**
 * X09 callback: a connected server's tool catalog drifted (tools added/removed or a schema hash
 * changed). The Lead routes this to the same non-terminal-run PENDING approval invalidation used by
 * Skill revocation. Implementations must be quick and non-blocking.
 */
fun interface AgentMcpCatalogChangeListener {
    fun onCatalogChanged(change: AgentMcpCatalogChange)

    companion object {
        val NONE: AgentMcpCatalogChangeListener = AgentMcpCatalogChangeListener { }
    }
}

/** Typed per-server lifecycle state. `generation` fences callbacks from a superseded attempt. */
sealed interface AgentMcpServerStatus {    val serverId: String
    val displayName: String
    val generation: Long

    data class Disabled(
        override val serverId: String,
        override val displayName: String,
        override val generation: Long
    ) : AgentMcpServerStatus

    data class Disconnected(
        override val serverId: String,
        override val displayName: String,
        override val generation: Long
    ) : AgentMcpServerStatus

    data class Connecting(
        override val serverId: String,
        override val displayName: String,
        override val generation: Long
    ) : AgentMcpServerStatus

    data class Connected(
        override val serverId: String,
        override val displayName: String,
        override val generation: Long,
        val toolCount: Int,
        val origin: String
    ) : AgentMcpServerStatus {
        init {
            require(toolCount >= 0) { "toolCount must not be negative" }
            require(origin.isNotBlank()) { "origin must not be blank" }
        }
    }

    data class Failed(
        override val serverId: String,
        override val displayName: String,
        override val generation: Long,
        val code: String
    ) : AgentMcpServerStatus {
        init {
            require(code.isNotBlank()) { "failure code must not be blank" }
        }
    }
}

/**
 * Immutable registry view for one run/catalog assembly.
 *
 * [adapters] is empty for disabled/failed/disconnected servers, so an unavailable server can never
 * contribute tools. [servers] and [adapters] are sorted deterministically.
 */
data class AgentMcpRegistrySnapshot(
    val servers: List<AgentMcpServerStatus>,
    val adapters: List<AgentToolExecutor>,
    val storeProblemCode: String? = null
) {
    val connectedServerIds: Set<String>
        get() = servers.filterIsInstance<AgentMcpServerStatus.Connected>()
            .mapTo(LinkedHashSet()) { it.serverId }
}

/**
 * Durable-config-driven MCP connection lifecycle (architecture 12.2).
 *
 * - configs load from [AgentMcpServerStore]; enabled servers connect on demand;
 * - [AgentNetworkEgressGuard] is consulted *before* a client is created or a socket opens;
 * - each connect/disconnect/disable/remove increments a per-server generation. A late
 *   `initialize`/tool-discovery completion is applied only while its generation is still current,
 *   so a stale callback can never re-add tools after revocation or a newer attempt;
 * - the registry never touches Android UI/composition threads; all methods are suspend or cheap
 *   synchronized snapshot reads, and file I/O runs on [ioDispatcher];
 * - connection attempts are bounded by [connectTimeoutMillis] (typed MCP_CONNECT_TIMEOUT);
 * - a refresh/reconnect of an already-connected server that changes its tool set or any schema hash
 *   bumps the catalog generation and emits exactly one [AgentMcpCatalogChange] (X09); an initial
 *   connect, disconnect, disable and remove never emit drift;
 * - [snapshot] is the single source for the per-run catalog assembler.
 *
 * `McpClient.disconnect()` is called exactly once for every client that reached Connected or was
 * superseded; the transport is shared by [AgentMcpClientFactory.default] and lives with the process.
 */
class AgentMcpRegistry(
    private val store: AgentMcpServerStore,
    private val egressGuard: AgentNetworkEgressGuard,
    private val clientFactory: AgentMcpClientFactory = AgentMcpClientFactory.default(),
    private val schemaValidator: AgentToolSchemaValidator = SupportedToolSchemaValidator(),
    private val connectTimeoutMillis: Long = McpProtocol.DEFAULT_DEADLINE_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    catalogChangeListener: AgentMcpCatalogChangeListener = AgentMcpCatalogChangeListener.NONE
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val generations = HashMap<String, Long>()

    @Volatile
    private var storeProblemCode: String? = null

    @Volatile
    private var changeListener: AgentMcpCatalogChangeListener = catalogChangeListener

    private data class Entry(
        val status: AgentMcpServerStatus,
        val client: McpClient? = null,
        val adapters: List<AgentToolExecutor> = emptyList(),
        val configKey: String
    )

    private data class Completion(val applied: Boolean, val status: AgentMcpServerStatus)

    private data class RefreshContext(
        val client: McpClient,
        val generation: Long,
        val origin: String
    )

    private data class ConnectStart(
        val generation: Long,
        val previousClient: McpClient?,
        val previousWasConnected: Boolean,
        val previousAdapters: List<AgentToolExecutor>
    )

    private data class CatalogFinalize(
        val status: AgentMcpServerStatus,
        val change: AgentMcpCatalogChange?
    )

    /** Replaces the X09 listener; usually installed once by the composition/run owner. */
    fun setCatalogChangeListener(listener: AgentMcpCatalogChangeListener) {
        changeListener = listener
    }

    fun snapshot(): AgentMcpRegistrySnapshot = synchronized(lock) {
        AgentMcpRegistrySnapshot(
            servers = entries.values.map { it.status }.sortedBy { it.serverId },
            adapters = entries.values
                .flatMap { it.adapters }
                .distinctBy { it.descriptor.toolKey }
                .sortedBy { it.descriptor.toolKey },
            storeProblemCode = storeProblemCode
        )
    }

    /**
     * Loads the durable configs, drops removed servers, applies enabled/disabled changes and then
     * connects every enabled server sequentially. A corrupt store is reported without connecting or
     * overwriting anything.
     */
    suspend fun refreshAndConnectEnabled(): AgentMcpRegistrySnapshot {
        val load = withContext(ioDispatcher) { store.load() }
        if (load is AgentMcpServerLoad.Corrupt) {
            synchronized(lock) { storeProblemCode = load.reasonCode }
            return snapshot()
        }
        storeProblemCode = null
        val records = (load as AgentMcpServerLoad.Loaded).servers
        val closing = ArrayList<McpClient>()
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val (serverId, entry) = iterator.next()
                if (records.none { it.serverId == serverId }) {
                    bumpLocked(serverId)
                    entry.client?.let(closing::add)
                    iterator.remove()
                }
            }
            for (record in records) {
                val key = configKey(record)
                val entry = entries[record.serverId]
                if (!record.enabled &&
                    (entry == null || entry.configKey != key || entry.status !is AgentMcpServerStatus.Disabled)
                ) {
                    val generation = bumpLocked(record.serverId)
                    entry?.client?.let(closing::add)
                    entries[record.serverId] = Entry(
                        status = AgentMcpServerStatus.Disabled(record.serverId, record.displayName, generation),
                        configKey = key
                    )
                }
            }
        }
        closing.forEach { closeQuietly(it) }

        for (record in records) {
            if (!record.enabled) continue
            val key = configKey(record)
            val current = synchronized(lock) { entries[record.serverId] }
            if (current != null && current.configKey == key &&
                (current.status is AgentMcpServerStatus.Connected || current.status is AgentMcpServerStatus.Connecting)
            ) {
                continue
            }
            connectRecord(record)
        }
        return snapshot()
    }

    /**
     * Re-runs `tools/list` for one already-connected server (MCP `notifications/tools/list_changed`
     * or a periodic drift check). On a changed tool set/schema hash the catalog generation is bumped,
     * old adapters are dropped, [AgentMcpCatalogChange] is emitted exactly once, and the new
     * adapters are returned in subsequent snapshots. No-op for non-connected servers; a refresh
     * failure drops the tools and never emits a drift event.
     */
    suspend fun refreshTools(serverId: String): AgentMcpServerStatus {
        val entry = synchronized(lock) { entries[serverId] }
        val connected = entry?.status as? AgentMcpServerStatus.Connected
        if (connected == null) {
            return entry?.status ?: failedStatus(serverId, null, "MCP_SERVER_NOT_CONNECTED")
        }
        val client = entry.client ?: return connected
        val context = RefreshContext(client, connected.generation, connected.origin)

        val result = try {
            withTimeoutOrNull(connectTimeoutMillis) { client.refreshTools() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        if (result == null) return failRefresh(serverId, context, "MCP_REFRESH_TIMEOUT")
        return when (result) {
            is McpResult.Failed -> failRefresh(serverId, context, result.failure.code)
            is McpResult.Ok -> {
                val tools = result.value
                val adapters = try {
                    buildAdapters(serverId, client, tools)
                } catch (_: Exception) {
                    return failRefresh(serverId, context, "MCP_TOOL_ADAPTER_FAILED")
                }
                val outcome: Pair<AgentMcpServerStatus, AgentMcpCatalogChange?> = synchronized(lock) {
                    val current = entries[serverId]
                    if (current == null || current.status.generation != context.generation ||
                        current.client !== context.client
                    ) {
                        (current?.status
                            ?: AgentMcpServerStatus.Disconnected(serverId, serverId, context.generation)) to null
                    } else {
                        val change = detectCatalogChange(
                            serverId = serverId,
                            generation = context.generation,
                            previousAdapters = current.adapters,
                            nextAdapters = adapters
                        )
                        if (change == null) {
                            current.status to null
                        } else {
                            val nextGeneration = bumpLocked(serverId)
                            val status = AgentMcpServerStatus.Connected(
                                serverId = serverId,
                                displayName = current.status.displayName,
                                generation = nextGeneration,
                                toolCount = adapters.size,
                                origin = context.origin
                            )
                            entries[serverId] = Entry(
                                status = status,
                                client = context.client,
                                adapters = adapters,
                                configKey = current.configKey
                            )
                            status to change.copy(generation = nextGeneration)
                        }
                    }
                }
                outcome.second?.let { dispatchCatalogChange(it) }
                outcome.first
            }
        }
    }

    /** Refreshes every connected server, emitting one change callback per observed drift. */
    suspend fun refreshConnectedTools(): AgentMcpRegistrySnapshot {
        val ids = synchronized(lock) {
            entries.values.mapNotNull { entry ->
                (entry.status as? AgentMcpServerStatus.Connected)?.serverId
            }.distinct()
        }
        ids.forEach { refreshTools(it) }
        return snapshot()
    }

    /** Connects one enabled server, or reports its typed Disabled/Failed state. */
    suspend fun connect(serverId: String): AgentMcpServerStatus {
        val load = withContext(ioDispatcher) { store.load() }
        if (load is AgentMcpServerLoad.Corrupt) {
            synchronized(lock) { storeProblemCode = load.reasonCode }
            return failedStatus(serverId, null, load.reasonCode)
        }
        storeProblemCode = null
        val record = (load as AgentMcpServerLoad.Loaded).servers.firstOrNull { it.serverId == serverId }
            ?: return failedStatus(serverId, null, "MCP_SERVER_NOT_FOUND")
        if (!record.enabled) return syncDisabled(record)
        return connectRecord(record)
    }

    suspend fun disconnect(serverId: String): AgentMcpServerStatus {
        val existing = synchronized(lock) { entries[serverId] }
        var record: AgentMcpServerRecord? = null
        if (existing == null) {
            val load = withContext(ioDispatcher) { store.load() }
            if (load is AgentMcpServerLoad.Loaded) {
                record = load.servers.firstOrNull { it.serverId == serverId }
            }
        }
        val displayName = existing?.status?.displayName ?: record?.displayName
            ?: return failedStatus(serverId, null, "MCP_SERVER_NOT_FOUND")
        val fallbackKey = existing?.configKey
        val outcome: Pair<McpClient?, AgentMcpServerStatus> = synchronized(lock) {
            val current = entries[serverId]
            val generation = bumpLocked(serverId)
            val previous = current?.client
            val disconnected = AgentMcpServerStatus.Disconnected(serverId, displayName, generation)
            entries[serverId] = Entry(
                status = disconnected,
                configKey = current?.configKey ?: fallbackKey ?: "unknown"
            )
            previous to disconnected
        }
        outcome.first?.let { closeQuietly(it) }
        return outcome.second
    }

    /** Removes the durable config (if present) and closes its client; tools disappear immediately. */
    suspend fun remove(serverId: String): AgentMcpRegistrySnapshot {
        val mutation = withContext(ioDispatcher) { store.remove(serverId) }
        if (mutation is AgentMcpServerMutation.Rejected) {
            if (mutation.code.startsWith("MCP_STORE")) {
                synchronized(lock) { storeProblemCode = mutation.code }
                return snapshot()
            }
            // A config that is already gone must still not leave a live client/tool set behind.
            if (mutation.code != "MCP_SERVER_NOT_FOUND") return snapshot()
        }
        val previous = synchronized(lock) {
            val entry = entries.remove(serverId)
            bumpLocked(serverId)
            entry?.client
        }
        previous?.let { closeQuietly(it) }
        return snapshot()
    }

    /**
     * Persists the enabled flag. Enabling leaves the server Disconnected (the caller triggers
     * [connect] or [refreshAndConnectEnabled]); disabling closes the client and drops its tools.
     */
    suspend fun setEnabled(serverId: String, enabled: Boolean): AgentMcpServerStatus {
        val mutation = withContext(ioDispatcher) { store.setEnabled(serverId, enabled) }
        if (mutation is AgentMcpServerMutation.Rejected) {
            return failedStatus(serverId, null, mutation.code)
        }
        val applied = mutation as AgentMcpServerMutation.Applied
        val record = applied.servers.firstOrNull { it.serverId == serverId }
            ?: return failedStatus(serverId, null, "MCP_SERVER_NOT_FOUND")
        val outcome: Pair<McpClient?, AgentMcpServerStatus> = synchronized(lock) {
            val generation = bumpLocked(serverId)
            val previous = entries[serverId]?.client
            val next: AgentMcpServerStatus = if (enabled) {
                AgentMcpServerStatus.Disconnected(serverId, record.displayName, generation)
            } else {
                AgentMcpServerStatus.Disabled(serverId, record.displayName, generation)
            }
            entries[serverId] = Entry(status = next, configKey = configKey(record))
            previous to next
        }
        outcome.first?.let { closeQuietly(it) }
        return outcome.second
    }

    /** Closes every client and fences all in-flight attempts; the store is left untouched. */
    suspend fun close() {
        val clients = synchronized(lock) {
            val snapshotClients = entries.values.mapNotNull { it.client }
            val ids = entries.keys.toList()
            ids.forEach { serverId ->
                val entry = entries[serverId] ?: return@forEach
                val generation = bumpLocked(serverId)
                entries[serverId] = Entry(
                    status = AgentMcpServerStatus.Disconnected(serverId, entry.status.displayName, generation),
                    configKey = entry.configKey
                )
            }
            snapshotClients
        }
        clients.forEach { closeQuietly(it) }
    }

    private suspend fun connectRecord(record: AgentMcpServerRecord): AgentMcpServerStatus {
        if (!record.enabled) return syncDisabled(record)
        return when (val endpoint = AgentMcpEndpointPolicy.check(record.endpoint, record.allowLocal)) {
            is AgentMcpEndpointCheck.Rejected -> denyRecord(record, endpoint.code)
            is AgentMcpEndpointCheck.Allowed -> {
                when (val egress = egressGuard.check(
                    AgentMcpEgressRequest(record.serverId, endpoint.origin, record.allowLocal)
                )) {
                    is AgentMcpEgressDecision.Denied -> denyRecord(
                        record,
                        egress.code.ifBlank { "MCP_EGRESS_DENIED" }
                    )
                    is AgentMcpEgressDecision.Allowed -> attemptConnect(record, endpoint.origin)
                }
            }
        }
    }

    private suspend fun attemptConnect(record: AgentMcpServerRecord, origin: String): AgentMcpServerStatus {
        val start: ConnectStart = synchronized(lock) {
            val current = entries[record.serverId]
            val generation = bumpLocked(record.serverId)
            val previousClient = current?.client
            entries[record.serverId] = Entry(
                status = AgentMcpServerStatus.Connecting(record.serverId, record.displayName, generation),
                configKey = configKey(record)
            )
            ConnectStart(
                generation = generation,
                previousClient = previousClient,
                previousWasConnected = current?.status is AgentMcpServerStatus.Connected,
                previousAdapters = current?.adapters.orEmpty()
            )
        }
        val generation = start.generation
        start.previousClient?.let { closeQuietly(it) }

        val client = try {
            clientFactory.create(record.toClientConfig())
        } catch (_: Exception) {
            return finishFailure(record, generation, "MCP_CLIENT_FACTORY_FAILED")
        }
        val outcome = try {
            withTimeoutOrNull(connectTimeoutMillis) { client.connect() }
        } catch (cancelled: CancellationException) {
            finish(
                record,
                generation,
                AgentMcpServerStatus.Disconnected(record.serverId, record.displayName, generation),
                client = null,
                adapters = emptyList()
            )
            closeQuietly(client)
            throw cancelled
        }
        if (outcome == null) {
            val status = finishFailure(record, generation, "MCP_CONNECT_TIMEOUT")
            closeQuietly(client)
            return status
        }
        return when (outcome) {
            is McpResult.Failed -> {
                val status = finishFailure(record, generation, outcome.failure.code)
                closeQuietly(client)
                status
            }
            is McpResult.Ok -> {
                val adapters = try {
                    buildAdapters(record.serverId, client, client.currentTools())
                } catch (_: Exception) {
                    val status = finishFailure(record, generation, "MCP_TOOL_ADAPTER_FAILED")
                    closeQuietly(client)
                    return status
                }
                val finalize: CatalogFinalize? = synchronized(lock) {
                    val current = entries[record.serverId]
                    if (current == null || current.status.generation != generation) {
                        null
                    } else {
                        val drift = if (start.previousWasConnected) {
                            detectCatalogChange(
                                serverId = record.serverId,
                                generation = generation,
                                previousAdapters = start.previousAdapters,
                                nextAdapters = adapters
                            )
                        } else {
                            null
                        }
                        val storedGeneration = if (drift != null) bumpLocked(record.serverId) else generation
                        val status = AgentMcpServerStatus.Connected(
                            serverId = record.serverId,
                            displayName = record.displayName,
                            generation = storedGeneration,
                            toolCount = adapters.size,
                            origin = origin
                        )
                        entries[record.serverId] = Entry(
                            status = status,
                            client = client,
                            adapters = adapters,
                            configKey = configKey(record)
                        )
                        CatalogFinalize(status, drift?.copy(generation = storedGeneration))
                    }
                }
                if (finalize == null) {
                    closeQuietly(client)
                    return currentStatus(record.serverId)
                        ?: disconnectedStatus(record.serverId, generation)
                }
                finalize.change?.let { dispatchCatalogChange(it) }
                finalize.status
            }
        }
    }

    private suspend fun syncDisabled(record: AgentMcpServerRecord): AgentMcpServerStatus {
        val key = configKey(record)
        val outcome: Pair<McpClient?, AgentMcpServerStatus> = synchronized(lock) {
            val existing = entries[record.serverId]
            if (existing != null && existing.configKey == key && existing.status is AgentMcpServerStatus.Disabled) {
                null to existing.status
            } else {
                val generation = bumpLocked(record.serverId)
                val previous = existing?.client
                val disabled = AgentMcpServerStatus.Disabled(record.serverId, record.displayName, generation)
                entries[record.serverId] = Entry(status = disabled, configKey = key)
                previous to disabled
            }
        }
        outcome.first?.let { closeQuietly(it) }
        return outcome.second
    }

    private suspend fun denyRecord(record: AgentMcpServerRecord, code: String): AgentMcpServerStatus {
        val outcome: Pair<McpClient?, AgentMcpServerStatus> = synchronized(lock) {
            val generation = bumpLocked(record.serverId)
            val previous = entries[record.serverId]?.client
            val failed = AgentMcpServerStatus.Failed(
                serverId = record.serverId,
                displayName = record.displayName,
                generation = generation,
                code = boundedCode(code)
            )
            entries[record.serverId] = Entry(status = failed, configKey = configKey(record))
            previous to failed
        }
        outcome.first?.let { closeQuietly(it) }
        return outcome.second
    }

    private fun finishFailure(
        record: AgentMcpServerRecord,
        generation: Long,
        code: String
    ): AgentMcpServerStatus {
        val status = AgentMcpServerStatus.Failed(
            serverId = record.serverId,
            displayName = record.displayName,
            generation = generation,
            code = boundedCode(code)
        )
        return finish(record, generation, status, client = null, adapters = emptyList()).status
    }

    private fun finish(
        record: AgentMcpServerRecord,
        generation: Long,
        status: AgentMcpServerStatus,
        client: McpClient?,
        adapters: List<AgentToolExecutor>
    ): Completion = synchronized(lock) {
        val current = entries[record.serverId]
        if (current == null || current.status.generation != generation) {
            Completion(applied = false, status = current?.status ?: status)
        } else {
            entries[record.serverId] = Entry(
                status = status,
                client = client,
                adapters = adapters,
                configKey = configKey(record)
            )
            Completion(applied = true, status = status)
        }
    }

    private fun buildAdapters(
        serverId: String,
        client: McpClient,
        tools: List<McpRemoteTool>
    ): List<AgentToolExecutor> =
        McpToolCatalogFactory(client, schemaValidator)
            .build(serverId, tools, client.currentSession()?.endpointOrigin)
            .accepted
            .sortedBy { it.descriptor.toolKey }

    private fun detectCatalogChange(
        serverId: String,
        generation: Long,
        previousAdapters: List<AgentToolExecutor>,
        nextAdapters: List<AgentToolExecutor>
    ): AgentMcpCatalogChange? {
        val previous = previousAdapters.associateBy { it.descriptor.toolKey }
        val next = nextAdapters.associateBy { it.descriptor.toolKey }
        val added = (next.keys - previous.keys).sorted()
        val removed = (previous.keys - next.keys).sorted()
        val changedSchema = (previous.keys intersect next.keys)
            .filter { key ->
                previous.getValue(key).descriptor.schemaHash != next.getValue(key).descriptor.schemaHash
            }
            .sorted()
        if (added.isEmpty() && removed.isEmpty() && changedSchema.isEmpty()) return null
        return AgentMcpCatalogChange(
            serverId = serverId,
            generation = generation,
            addedKeys = added,
            removedKeys = removed,
            changedSchemaKeys = changedSchema
        )
    }

    private fun dispatchCatalogChange(change: AgentMcpCatalogChange) {
        try {
            changeListener.onCatalogChanged(change)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // X09 consumer faults must never break the connection/refresh state machine.
        }
    }

    private fun currentStatus(serverId: String): AgentMcpServerStatus? =
        synchronized(lock) { entries[serverId]?.status }

    private fun disconnectedStatus(serverId: String, generation: Long): AgentMcpServerStatus {
        val displayName = currentStatus(serverId)?.displayName ?: serverId
        return AgentMcpServerStatus.Disconnected(serverId, displayName, generation)
    }

    private suspend fun failRefresh(
        serverId: String,
        context: RefreshContext,
        code: String
    ): AgentMcpServerStatus {
        var closeClient: McpClient? = null
        val status: AgentMcpServerStatus = synchronized(lock) {
            val current = entries[serverId]
            if (current == null || current.status.generation != context.generation ||
                current.client !== context.client
            ) {
                current?.status ?: AgentMcpServerStatus.Disconnected(serverId, serverId, context.generation)
            } else {
                val generation = bumpLocked(serverId)
                val failed = AgentMcpServerStatus.Failed(
                    serverId = serverId,
                    displayName = current.status.displayName,
                    generation = generation,
                    code = boundedCode(code)
                )
                closeClient = context.client
                entries[serverId] = Entry(status = failed, configKey = current.configKey)
                failed
            }
        }
        closeClient?.let { closeQuietly(it) }
        return status
    }

    private fun failedStatus(serverId: String, displayName: String?, code: String): AgentMcpServerStatus.Failed {
        val generation = synchronized(lock) { bumpLocked(serverId) }
        return AgentMcpServerStatus.Failed(
            serverId = serverId,
            displayName = displayName ?: serverId,
            generation = generation,
            code = boundedCode(code)
        )
    }

    private suspend fun closeQuietly(client: McpClient?) {
        if (client == null) return
        try {
            client.disconnect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best-effort local close; the remote session may already be gone.
        }
    }

    private fun bumpLocked(serverId: String): Long {
        val next = (generations[serverId] ?: 0L) + 1L
        generations[serverId] = next
        return next
    }

    private fun boundedCode(raw: String): String = raw.trim().take(64).ifBlank { "MCP_FAILED" }

    private fun configKey(record: AgentMcpServerRecord): String =
        "${record.endpoint}|${record.allowLocal}|${record.displayName}|${record.enabled}"
}


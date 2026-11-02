package com.muyuchat.mca.agent.extensions.mcp

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.mca.agent.security.AgentEgressTransport
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit, durable user authorization for one MCP server to participate in Agent runs.
 *
 * This is a user-scope grant, not a per-call permission and not a wildcard:
 * - [origin] is the canonical origin snapshotted from the endpoint at the exact moment the user
 *   authorized the server, so editing the endpoint later invalidates the authorization;
 * - [transport] is fixed to the server's explicit local flag, so a REMOTE_MCP authorization can
 *   never be reused for a loopback destination (and vice versa);
 * - [dataClasses] is the read boundary. MCP tools always project
 *   `AgentDataClass.PRIVATE`/`EXTERNAL_SIDE_EFFECT`; an authorization that does not explicitly
 *   include [AgentDataClass.PRIVATE] is rejected when it is created and can never cover a run.
 *
 * Writes are a separate boundary: every projected MCP tool keeps `EXTERNAL_SIDE_EFFECT`, so the
 * per-call approval (ask/deny/allow-once) is still required. This authorization never bypasses it
 * and never turns MCP annotations into a read-only claim.
 */
data class AgentMcpAuthorization(
    val origin: String,
    val transport: AgentEgressTransport,
    val dataClasses: Set<AgentDataClass>,
    val grantedAtMillis: Long
) {
    init {
        require(origin.isNotBlank()) { "MCP authorization origin must not be blank" }
        require(origin.length <= MAX_ORIGIN_CHARS) { "MCP authorization origin is too long" }
        require(origin.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "MCP authorization origin must be a single line"
        }
        require(transport == AgentEgressTransport.REMOTE_MCP || transport == AgentEgressTransport.LOCAL_SERVER) {
            "MCP authorization transport must be REMOTE_MCP or LOCAL_SERVER"
        }
        require(dataClasses.isNotEmpty()) { "MCP authorization must name at least one data class" }
        // No wildcard default: MCP tools carry PRIVATE, so an authorization without it is useless
        // and must never be persisted as if it granted something.
        require(AgentDataClass.PRIVATE in dataClasses) {
            "MCP authorization must explicitly include PRIVATE"
        }
        require(grantedAtMillis > 0L) { "MCP authorization time must be positive" }
    }

    /** True only when every MCP tool's projected data class is covered by this explicit grant. */
    fun coversProjectedTools(): Boolean = AgentDataClass.PRIVATE in dataClasses

    internal fun toJson(): JSONObject = JSONObject()
        .put("origin", origin)
        .put("transport", transport.name)
        .put("dataClasses", JSONArray(dataClasses.map { it.name }.sorted()))
        .put("grantedAtMillis", grantedAtMillis)

    companion object {
        /** Bounded like [AgentMcpServerRecord.MAX_DISPLAY_NAME_CHARS]; an origin is short. */
        const val MAX_ORIGIN_CHARS: Int = 512

        internal fun fromJson(json: JSONObject): AgentMcpAuthorization {
            if (json.length() != 4 || !json.has("origin") || !json.has("transport") ||
                !json.has("dataClasses") || !json.has("grantedAtMillis")
            ) {
                throw IllegalArgumentException("MCP authorization has an unsupported shape")
            }
            val transport = AgentEgressTransport.entries
                .firstOrNull { it.name == json.optString("transport") }
                ?: throw IllegalArgumentException("unknown MCP authorization transport")
            val classesJson = json.optJSONArray("dataClasses")
                ?: throw IllegalArgumentException("missing MCP authorization data classes")
            val classes = LinkedHashSet<AgentDataClass>(classesJson.length())
            for (index in 0 until classesJson.length()) {
                val raw = classesJson.optString(index, null)
                    ?: throw IllegalArgumentException("invalid MCP authorization data class")
                classes += AgentDataClass.entries.firstOrNull { it.name == raw }
                    ?: throw IllegalArgumentException("unknown MCP authorization data class")
            }
            if (classes.size != classesJson.length()) {
                throw IllegalArgumentException("duplicate MCP authorization data class")
            }
            return AgentMcpAuthorization(
                origin = json.optString("origin"),
                transport = transport,
                dataClasses = classes,
                grantedAtMillis = json.optLong("grantedAtMillis", -1L)
            )
        }
    }
}

/**
 * One durable user-authored MCP server entry.
 *
 * The token itself is never stored here. A server may carry an opaque [credentialRefId] that points
 * into the Keystore-backed credential store; [toClientConfig] then builds `McpAuth.SecretReference`.
 * Legacy records without the field keep `McpAuth.None`.
 */
data class AgentMcpServerRecord(
    val serverId: String,
    val displayName: String,
    val endpoint: String,
    val enabled: Boolean = true,
    val allowLocal: Boolean = false,
    val createdAtMillis: Long = 0L,
    /**
     * Explicit user config consent for this server to be considered by Agent runs. A config toggle
     * is only one half of the decision: an authorized [agentAuthorization] is additionally required,
     * and the run itself must be network-enabled. Neither value is a per-call permission.
     * A missing value on a legacy JSON record decodes to false.
     */
    val allowAgentNetwork: Boolean = false,
    /**
     * Durable server-scope authorization recorded when the user turns on Agent network access.
     * Null means "not authorized" (legacy records, or the user revoked it); the run-start binder
     * then has nothing to bind and fails closed. Never a wildcard and never a credential.
     */
    val agentAuthorization: AgentMcpAuthorization? = null,
    /**
     * Optional opaque reference into the Keystore-backed MCP credential store. The token itself is
     * never serialized here; a null value keeps `McpAuth.None` for legacy/plain servers.
     */
    val credentialRefId: String? = null
) {
    init {
        require(serverId.matches(SERVER_ID)) { "serverId must be a stable lowercase id" }
        requireValidDisplayName(displayName)
        require(createdAtMillis >= 0L) { "createdAtMillis must not be negative" }
        if (credentialRefId != null) {
            require(credentialRefId.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) {
                "credentialRefId must be a stable opaque id"
            }
        }
        if (agentAuthorization != null) {
            require(allowAgentNetwork) { "an MCP authorization requires the Agent-network consent flag" }
            require(agentAuthorization.transport == transportOf(allowLocal)) {
                "MCP authorization transport must match the local-server flag"
            }
        }
    }

    fun toClientConfig(): McpServerConfig = McpServerConfig(
        serverId = serverId,
        displayName = displayName,
        endpoint = endpoint,
        auth = credentialRefId?.let { McpAuth.SecretReference(it) } ?: McpAuth.None,
        enabled = enabled,
        allowLocalServer = allowLocal
    )

    internal fun toJson(): JSONObject {
        val json = JSONObject()
            .put("serverId", serverId)
            .put("displayName", displayName)
            .put("endpoint", endpoint)
            .put("enabled", enabled)
            .put("allowLocal", allowLocal)
            .put("allowAgentNetwork", allowAgentNetwork)
            .put("createdAtMillis", createdAtMillis)
        agentAuthorization?.let { json.put("agentAuthorization", it.toJson()) }
        credentialRefId?.let { json.put("credentialRefId", it) }
        return json
    }

    companion object {
        const val MAX_DISPLAY_NAME_CHARS: Int = 128
        private val SERVER_ID = Regex("^[a-z0-9][a-z0-9-]{1,63}$")

        internal fun transportOf(allowLocal: Boolean): AgentEgressTransport =
            if (allowLocal) AgentEgressTransport.LOCAL_SERVER else AgentEgressTransport.REMOTE_MCP

        fun requireValidDisplayName(displayName: String) {
            require(displayName.isNotBlank()) { "displayName must not be blank" }
            require(displayName.length <= MAX_DISPLAY_NAME_CHARS) {
                "displayName exceeds $MAX_DISPLAY_NAME_CHARS characters"
            }
            require(displayName.none { it == '\n' || it == '\r' || it == '\u0000' || it.isISOControl() }) {
                "displayName must be a single line without control characters"
            }
        }

        internal fun fromJson(json: JSONObject): AgentMcpServerRecord {
            val serverId = json.optString("serverId")
            val displayName = json.optString("displayName")
            val endpoint = json.optString("endpoint")
            if (!serverId.matches(SERVER_ID)) throw IllegalArgumentException("invalid serverId")
            requireValidDisplayName(displayName)
            if (!json.has("enabled")) throw IllegalArgumentException("missing enabled")
            val enabled = json.getBoolean("enabled")
            val allowLocal = if (json.has("allowLocal")) json.getBoolean("allowLocal") else false
            val createdAt = if (json.has("createdAtMillis")) json.getLong("createdAtMillis") else 0L
            // Legacy files predate the consent field: absent means "not allowed". A present value
            // must be a real JSON boolean; a string/number/null fails closed as a corrupt store.
            // `getBoolean` is deliberately not used here because JSON-java coerces "true"/"false".
            val allowAgentNetwork = if (json.has("allowAgentNetwork")) {
                val raw = json.opt("allowAgentNetwork")
                if (raw !is Boolean) throw IllegalArgumentException("allowAgentNetwork must be a boolean")
                raw
            } else {
                false
            }
            val authorization = if (json.has("agentAuthorization")) {
                val raw = json.opt("agentAuthorization")
                if (raw !is JSONObject) throw IllegalArgumentException("agentAuthorization must be an object")
                val parsed = AgentMcpAuthorization.fromJson(raw)
                if (!allowAgentNetwork) {
                    throw IllegalArgumentException("an MCP authorization without consent is inconsistent")
                }
                if (parsed.transport != transportOf(allowLocal)) {
                    throw IllegalArgumentException("MCP authorization transport does not match allowLocal")
                }
                // The authorization origin must be exactly the canonical origin of the persisted
                // endpoint. Hand-edited files and endpoint drift therefore decode as corrupt and
                // fail closed instead of reusing a stale grant for a new destination.
                val expectedOrigin = when (val check = AgentMcpEndpointPolicy.check(endpoint, allowLocal)) {
                    is AgentMcpEndpointCheck.Allowed -> check.origin
                    is AgentMcpEndpointCheck.Rejected -> throw IllegalArgumentException(check.code)
                }
                if (parsed.origin != expectedOrigin) {
                    throw IllegalArgumentException("MCP authorization origin does not match the endpoint")
                }
                parsed
            } else {
                null
            }
            return AgentMcpServerRecord(
                serverId = serverId,
                displayName = displayName,
                endpoint = endpoint,
                enabled = enabled,
                allowLocal = allowLocal,
                createdAtMillis = createdAt,
                allowAgentNetwork = allowAgentNetwork,
                agentAuthorization = authorization,
                credentialRefId = json.optString("credentialRefId", "").ifBlank { null }
            )
        }
    }
}

sealed interface AgentMcpServerLoad {
    data class Loaded(val servers: List<AgentMcpServerRecord>) : AgentMcpServerLoad

    data class Corrupt(val reasonCode: String) : AgentMcpServerLoad
}

sealed interface AgentMcpServerMutation {
    data class Applied(val servers: List<AgentMcpServerRecord>) : AgentMcpServerMutation

    data class Rejected(val code: String, val message: String) : AgentMcpServerMutation
}

/**
 * Durable JSON registry for MCP server configs at `files/agent/mcp/servers.json`.
 *
 * - reads are bounded and every persisted record is re-validated;
 * - a corrupt/oversized/unknown-schema file is reported as typed [AgentMcpServerLoad.Corrupt] and is
 *   never overwritten by a later mutation ("corrupt fenced");
 * - writes are atomic (temp file + ATOMIC_MOVE), so a crash leaves either the old or the new file;
 * - duplicate ids and a bounded server count are rejected; no token/header/credential is persisted;
 * - turning Agent network access on records an explicit [AgentMcpAuthorization] whose origin and
 *   transport are snapshotted from the persisted endpoint; turning it off deletes it.
 */
class AgentMcpServerStore(
    filesDir: File,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val directory: File = File(File(filesDir, "agent"), "mcp")
    private val configFile: File = File(directory, CONFIG_FILE_NAME)
    private val lock = Any()

    val path: File get() = configFile

    fun load(): AgentMcpServerLoad = synchronized(lock) { loadLocked() }

    fun add(record: AgentMcpServerRecord): AgentMcpServerMutation = mutate { servers ->
        if (servers.any { it.serverId == record.serverId }) {
            return@mutate AgentMcpServerMutation.Rejected(
                "MCP_DUPLICATE_SERVER_ID",
                "A server with this id is already registered."
            )
        }
        validateRecord(record)?.let { code ->
            return@mutate AgentMcpServerMutation.Rejected(code, "The server config is not acceptable.")
        }
        if (servers.size >= MAX_SERVERS) {
            return@mutate AgentMcpServerMutation.Rejected(
                "MCP_SERVER_LIMIT",
                "At most $MAX_SERVERS MCP servers may be registered."
            )
        }
        AgentMcpServerMutation.Applied(sort(servers + record))
    }

    fun setEnabled(serverId: String, enabled: Boolean): AgentMcpServerMutation = mutate { servers ->
        val index = servers.indexOfFirst { it.serverId == serverId }
        if (index < 0) return@mutate notFound(serverId)
        val updated = servers.toMutableList()
        updated[index] = updated[index].copy(enabled = enabled)
        AgentMcpServerMutation.Applied(sort(updated))
    }

    /**
     * Turns the user's Agent-network switch for one server on or off.
     *
     * On records an explicit durable authorization for this exact server: the canonical origin and
     * transport are snapshotted now, and the data boundary is exactly the MCP tool class
     * ([AgentDataClass.PRIVATE]) — there is no wildcard parameter and no implicit "all classes".
     * Off removes both the consent flag and the authorization. This is still not a per-call
     * permission: every MCP tool remains `EXTERNAL_SIDE_EFFECT` and needs its own approval.
     *
     * Setting the same value again is an atomic no-op that still returns
     * [AgentMcpServerMutation.Applied]; unknown or disabled ids are rejected.
     */
    fun setAgentNetworkAllowed(serverId: String, allowed: Boolean): AgentMcpServerMutation =
        if (allowed) authorizeForAgent(serverId) else revokeAgentAuthorization(serverId)

    /** Records the durable server-scope authorization; see [setAgentNetworkAllowed]. */
    fun authorizeForAgent(
        serverId: String,
        dataClasses: Set<AgentDataClass> = setOf(AgentDataClass.PRIVATE)
    ): AgentMcpServerMutation = mutate { servers ->
        val index = servers.indexOfFirst { it.serverId == serverId }
        if (index < 0) return@mutate notFound(serverId)
        val record = servers[index]
        if (!record.enabled) {
            return@mutate AgentMcpServerMutation.Rejected(
                "MCP_SERVER_DISABLED",
                "A disabled MCP server cannot be authorized for Agent runs."
            )
        }
        val origin = when (val check = AgentMcpEndpointPolicy.check(record.endpoint, record.allowLocal)) {
            is AgentMcpEndpointCheck.Allowed -> check.origin
            is AgentMcpEndpointCheck.Rejected -> return@mutate AgentMcpServerMutation.Rejected(
                check.code,
                "The MCP endpoint is not acceptable for authorization."
            )
        }
        val authorization = try {
            AgentMcpAuthorization(
                origin = origin,
                transport = AgentMcpServerRecord.transportOf(record.allowLocal),
                dataClasses = dataClasses.toSet(),
                grantedAtMillis = clockMillis().coerceAtLeast(1L)
            )
        } catch (_: IllegalArgumentException) {
            return@mutate AgentMcpServerMutation.Rejected(
                "MCP_AUTHORIZATION_SCOPE_INVALID",
                "The MCP authorization scope is not acceptable."
            )
        }
        val updated = servers.toMutableList()
        updated[index] = record.copy(allowAgentNetwork = true, agentAuthorization = authorization)
        AgentMcpServerMutation.Applied(sort(updated))
    }

    /** Revokes the durable server-scope authorization; the next run can no longer bind it. */
    fun revokeAgentAuthorization(serverId: String): AgentMcpServerMutation = mutate { servers ->
        val index = servers.indexOfFirst { it.serverId == serverId }
        if (index < 0) return@mutate notFound(serverId)
        val updated = servers.toMutableList()
        updated[index] = updated[index].copy(allowAgentNetwork = false, agentAuthorization = null)
        AgentMcpServerMutation.Applied(sort(updated))
    }

    fun remove(serverId: String): AgentMcpServerMutation = mutate { servers ->
        if (servers.none { it.serverId == serverId }) return@mutate notFound(serverId)
        AgentMcpServerMutation.Applied(servers.filterNot { it.serverId == serverId })
    }

    private fun notFound(serverId: String): AgentMcpServerMutation =
        AgentMcpServerMutation.Rejected("MCP_SERVER_NOT_FOUND", "No MCP server with id '$serverId' is registered.")

    private fun mutate(transform: (List<AgentMcpServerRecord>) -> AgentMcpServerMutation): AgentMcpServerMutation =
        synchronized(lock) {
            when (val loaded = loadLocked()) {
                is AgentMcpServerLoad.Corrupt -> AgentMcpServerMutation.Rejected(
                    loaded.reasonCode,
                    "The MCP server config store is corrupt; it will not be overwritten."
                )
                is AgentMcpServerLoad.Loaded -> when (val next = transform(loaded.servers)) {
                    is AgentMcpServerMutation.Applied -> {
                        val failure = persist(next.servers)
                        if (failure != null) {
                            AgentMcpServerMutation.Rejected(failure, "The MCP server config could not be persisted.")
                        } else {
                            AgentMcpServerMutation.Applied(sort(next.servers))
                        }
                    }
                    is AgentMcpServerMutation.Rejected -> next
                }
            }
        }

    private fun loadLocked(): AgentMcpServerLoad {
        if (!configFile.exists()) return AgentMcpServerLoad.Loaded(emptyList())
        if (!configFile.isFile) return AgentMcpServerLoad.Corrupt("MCP_STORE_NOT_A_FILE")
        if (configFile.length() > MAX_FILE_BYTES) return AgentMcpServerLoad.Corrupt("MCP_STORE_TOO_LARGE")
        val text = try {
            configFile.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return AgentMcpServerLoad.Corrupt("MCP_STORE_IO_FAILED")
        }
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return AgentMcpServerLoad.Corrupt("MCP_STORE_CORRUPT_JSON")
        }
        val version = root.optInt("schemaVersion", -1)
        if (version !in MIN_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) {
            return AgentMcpServerLoad.Corrupt("MCP_STORE_SCHEMA_UNSUPPORTED")
        }
        val array = root.optJSONArray("servers") ?: return AgentMcpServerLoad.Corrupt("MCP_STORE_MISSING_SERVERS")
        val records = ArrayList<AgentMcpServerRecord>(array.length())
        for (index in 0 until array.length()) {
            val element = array.optJSONObject(index)
                ?: return AgentMcpServerLoad.Corrupt("MCP_STORE_INVALID_RECORD")
            val record = try {
                AgentMcpServerRecord.fromJson(element)
            } catch (_: Exception) {
                return AgentMcpServerLoad.Corrupt("MCP_STORE_INVALID_RECORD")
            }
            val endpointFailure = validateRecord(record)
            if (endpointFailure != null) return AgentMcpServerLoad.Corrupt(endpointFailure)
            records += record
        }
        if (records.map { it.serverId }.distinct().size != records.size) {
            return AgentMcpServerLoad.Corrupt("MCP_STORE_DUPLICATE_ID")
        }
        return AgentMcpServerLoad.Loaded(sort(records))
    }

    private fun validateRecord(record: AgentMcpServerRecord): String? {
        AgentMcpServerRecord.requireValidDisplayName(record.displayName)
        val check = AgentMcpEndpointPolicy.check(record.endpoint, record.allowLocal)
        if (check is AgentMcpEndpointCheck.Rejected) return check.code
        val authorization = record.agentAuthorization ?: return null
        // An authorization is bound to the exact canonical origin captured when the user granted it.
        // A record whose endpoint no longer matches is not silently "re-authorized" on read.
        val allowed = check as AgentMcpEndpointCheck.Allowed
        if (authorization.origin != allowed.origin) return "MCP_AUTHORIZATION_ORIGIN_DRIFT"
        return null
    }

    private fun persist(servers: List<AgentMcpServerRecord>): String? {
        val temporary = File(directory, "$CONFIG_FILE_NAME.tmp")
        return try {
            if (!directory.exists() && !directory.mkdirs()) return "MCP_STORE_DIRECTORY_FAILED"
            val array = JSONArray()
            servers.forEach { array.put(it.toJson()) }
            val text = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("servers", array)
                .toString()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return "MCP_STORE_TOO_LARGE"
            temporary.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            null
        } catch (_: IOException) {
            runCatching { temporary.delete() }
            "MCP_STORE_IO_FAILED"
        }
    }

    private fun sort(servers: List<AgentMcpServerRecord>): List<AgentMcpServerRecord> =
        servers.sortedBy { it.serverId }

    companion object {
        const val CONFIG_FILE_NAME: String = "servers.json"
        const val SCHEMA_VERSION: Int = 2
        /** v1 lacked credentialRefId; it decodes to null and is re-persisted as v2. */
        const val MIN_SUPPORTED_SCHEMA_VERSION: Int = 1
        const val MAX_SERVERS: Int = 16
        const val MAX_FILE_BYTES: Int = 256 * 1024
    }
}

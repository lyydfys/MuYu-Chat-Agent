package com.muyuchat.core.dsh.runtime.config

import java.io.File
import java.net.URI
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Typed reason one `mcp-servers.json` document did not produce a usable server declaration.
 *
 * The codes are stable identifiers for diagnostics and tests. A refusal never fails a DSH host
 * generation: MCP is an optional add-on, so an unreadable or invalid declaration degrades to "no
 * MCP row" instead of blocking the boot. No value read from the file (command body, argument, URL,
 * env/header value) is ever attached to a diagnostic, so a secret cannot reach a log through this
 * path.
 */
enum class DshMcpConfigFailureCode {
    /** The path exists but is not a readable regular file. */
    FILE_UNREADABLE,
    /** The document exceeds [DshMcpServerConfigLoader.MAX_FILE_BYTES]. */
    FILE_TOO_LARGE,
    /** The document is not JSON at all. */
    JSON_INVALID,
    /** The root is not `{version, servers}` with a `servers` array. */
    ROOT_SHAPE_INVALID,
    /** `version` is not the schema version this runtime understands (never half-read). */
    SCHEMA_VERSION_UNSUPPORTED,
    /** An object carried a key this schema does not define. The foreign key is never echoed. */
    UNKNOWN_FIELD,
    /** A required key is absent. */
    MISSING_FIELD,
    /** A present key has the wrong JSON type. */
    FIELD_TYPE_INVALID,
    /** `transport` is neither `stdio` nor `streamable-http`. */
    TRANSPORT_UNSUPPORTED,
    /** `serverName` is not `[A-Za-z0-9][A-Za-z0-9_-]{0,31}`. */
    SERVER_NAME_INVALID,
    /** Two entries declared the same `serverName`; only the first is accepted. */
    SERVER_NAME_DUPLICATE,
    /** `command` is blank, over-long or carries a control character. */
    COMMAND_INVALID,
    /** `args` is not a bounded array of control-free strings. */
    ARG_INVALID,
    /** `env` is not a bounded map of identifier keys to control-free strings. */
    ENV_INVALID,
    /** `url` is not an `http`/`https` URL with a host. */
    URL_INVALID,
    /** `headers` is not a bounded map of header-name keys to control-free strings. */
    HEADER_INVALID,
    /** `servers` declares more entries than [DshMcpServerConfigLoader.MAX_SERVERS]. */
    TOO_MANY_SERVERS,
    /** `capabilities` is not a bounded, duplicate-free array of known capability tokens. */
    CAPABILITY_INVALID,
    /**
     * A declared capability has no serving code path in the pinned payload (today: `prompts`).
     * Accepting it would promise a surface the runtime never exposes.
     */
    CAPABILITY_UNSUPPORTED,
    /**
     * A stdio `env` key uses the harness-reserved `DSH_*` namespace. The official client scrubs
     * those names from the inherited environment; an explicit declaration must not re-introduce
     * them into the MCP child.
     */
    ENV_KEY_RESERVED
}

/**
 * One typed refusal. [serverIndex] locates the entry (`-1` for a whole-file refusal) and [field]
 * names the schema field from a fixed vocabulary; values from the file are never included.
 */
data class DshMcpConfigDiagnostic(
    val code: DshMcpConfigFailureCode,
    val serverIndex: Int,
    val serverName: String?,
    val field: String?
) {
    /** Stable, value-free location string for logs ("file" or "servers[2]"). */
    val location: String get() = if (serverIndex >= 0) "servers[$serverIndex]" else "file"
}

/** One accepted server declaration, in the shape the official `dsh-mcp-client` row needs. */
sealed interface DshMcpServerSpec {
    val serverName: String

    /**
     * Capabilities the document expects from this server. Declaring `resources` is honest because
     * the pinned payload serves it through the official `dsh-mcp-resources` tools; declaring
     * `prompts` is refused by the reader because no payload plugin implements `prompts/list` or
     * `prompts/get`. The list is never forwarded to the official client row and does not switch a
     * capability on: tools and resources are exposed whenever the server advertises them.
     */
    val capabilities: Set<DshMcpCapability> get() = emptySet()

    /** Overlay row id; unique because [serverName] is unique and the prefix is MCA-specific. */
    val rowId: String get() = DshMcpOverlayRows.ROW_ID_PREFIX + serverName
}

data class DshMcpStdioServer(
    override val serverName: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
    override val capabilities: Set<DshMcpCapability> = emptySet()
) : DshMcpServerSpec

data class DshMcpHttpServer(
    override val serverName: String,
    val url: String,
    val headers: Map<String, String>,
    override val capabilities: Set<DshMcpCapability> = emptySet()
) : DshMcpServerSpec

/** Result of reading one declaration file: accepted servers plus typed, value-free refusals. */
data class DshMcpServerCatalog(
    val servers: List<DshMcpServerSpec>,
    val diagnostics: List<DshMcpConfigDiagnostic>
) {
    companion object {
        /** Absent file: no server, no diagnostic, so the overlay keeps today's zero-MCP-row bytes. */
        val ABSENT = DshMcpServerCatalog(emptyList(), emptyList())
    }
}

/**
 * Reads the MCA-owned MCP server declaration (`<DSH_HOME>/mcp-servers.json`).
 *
 * This is deliberately *not* a second MCP implementation and not a YAML surface: it is a small,
 * strictly typed JSON file whose only purpose is to say which official `@deepseek-ai/dsh-mcp-client`
 * rows the seeder should compose. The official package keeps ownership of transports, handshakes
 * and tool naming (`mcp__<serverName>__<rawName>`).
 *
 * Contract:
 * - absent file -> [DshMcpServerCatalog.ABSENT] (zero rows, byte-identical overlay to a build that
 *   knew nothing about MCP);
 * - unknown keys, unknown `version`, an oversized document or more than [MAX_SERVERS] entries refuse
 *   the whole file (a partially understood document must not be half-applied);
 * - an invalid entry is refused on its own and the remaining entries stay usable;
 * - every emitted scalar is bounded and control-character free, and the overlay renderer always
 *   double-quotes values, so no file content can become YAML structure;
 * - an optional `capabilities` array states what the entry expects. `tools` and `resources` are
 *   served by the pinned payload (`resources` through the official `list_mcp_resources` /
 *   `read_mcp_resource` model tools), so they are accepted and preserved in the declaration;
 *   `prompts` is refused with [DshMcpConfigFailureCode.CAPABILITY_UNSUPPORTED] because no pinned
 *   plugin calls `prompts/list` or `prompts/get`. The array is never forwarded to the official
 *   client row;
 * - a stdio `env` key in the harness-reserved `DSH_*` namespace is refused
 *   ([DshMcpConfigFailureCode.ENV_KEY_RESERVED]). Everything else is the official client's
 *   contract: it starts from a scrubbed parent environment and merges the explicit map last, so
 *   this schema cannot promise an allowlist and does not pretend to ([DshMcpStdioEnvContract]).
 *
 * Tool counts are not declarable: one server can publish any number of tools at connect time, and
 * those tools share the model gateway's per-request budget with built-in DSH tools
 * (`DSH_MAX_MODEL_VISIBLE_TOOLS`). The declaration bounds what it can see (servers and each
 * server's scalar fields); the gateway bounds the model-facing catalog.
 */
internal object DshMcpServerConfigLoader {

    /** File name directly below `$DSH_HOME`; MCA-owned and rewritten by nothing else. */
    const val FILE_NAME = "mcp-servers.json"

    const val SCHEMA_VERSION = 1

    /**
     * Version written only when at least one entry declares `capabilities`.
     *
     * The capability array is an additive field, but this reader refuses unknown keys and older
     * builds therefore cannot read a file that carries it. Keeping capability-free documents on
     * [SCHEMA_VERSION] preserves the original bytes for every existing declaration; a document
     * that uses the new field is explicitly [CAPABILITY_SCHEMA_VERSION] so an older build fails
     * closed with `SCHEMA_VERSION_UNSUPPORTED` instead of a vague unknown-field refusal.
     */
    const val CAPABILITY_SCHEMA_VERSION = 2

    /** Upper bound on official client instances one generation may compose. */
    const val MAX_SERVERS = 8
    const val MAX_FILE_BYTES = 65_536
    const val MAX_SERVER_NAME_CHARS = 32
    const val MAX_COMMAND_CHARS = 512
    const val MAX_ARGS = 32
    const val MAX_ARG_CHARS = 1_024
    const val MAX_ENV_ENTRIES = 32
    const val MAX_ENV_KEY_CHARS = 64
    const val MAX_ENV_VALUE_CHARS = 512
    const val MAX_HEADERS = 16
    const val MAX_HEADER_NAME_CHARS = 64
    const val MAX_HEADER_VALUE_CHARS = 1_024
    const val MAX_URL_CHARS = 2_048

    const val TRANSPORT_STDIO = "stdio"
    const val TRANSPORT_STREAMABLE_HTTP = "streamable-http"

    private val ROOT_KEYS = setOf("version", "servers")
    private val STDIO_KEYS = setOf("transport", "serverName", "command", "args", "env", "capabilities")
    private val HTTP_KEYS = setOf("transport", "serverName", "url", "headers", "capabilities")

    /** Public tool names are `mcp__<serverName>__<rawName>`, so the name must stay a simple token. */
    private val SERVER_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,${MAX_SERVER_NAME_CHARS - 1}}")

    /**
     * True when [name] is a server name the official client can expose as `mcp__<name>__*`.
     * Public callers (a write port or a settings surface) can pre-check a field without reading
     * a declaration file; the loader still applies the same rule when it reads the document.
     */
    fun isValidServerName(name: String): Boolean = SERVER_NAME_PATTERN.matches(name)
    private val ENV_KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]{0,${MAX_ENV_KEY_CHARS - 1}}")
    private val HEADER_NAME_PATTERN =
        Regex("[A-Za-z0-9][A-Za-z0-9-]{0,${MAX_HEADER_NAME_CHARS - 1}}")

    /** Never throws: every refusal is a typed diagnostic, never an exception into the seeder. */
    fun load(file: File): DshMcpServerCatalog {
        if (!file.exists()) return DshMcpServerCatalog.ABSENT
        if (!file.isFile) return fileRefusal(DshMcpConfigFailureCode.FILE_UNREADABLE)
        if (file.length() > MAX_FILE_BYTES) return fileRefusal(DshMcpConfigFailureCode.FILE_TOO_LARGE)
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return fileRefusal(DshMcpConfigFailureCode.FILE_UNREADABLE)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            return fileRefusal(DshMcpConfigFailureCode.FILE_TOO_LARGE)
        }
        return parse(text)
    }

    internal fun parse(text: String): DshMcpServerCatalog {
        val root = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return fileRefusal(DshMcpConfigFailureCode.JSON_INVALID)
        }
        for (key in root.keys()) {
            if (key !in ROOT_KEYS) return fileRefusal(DshMcpConfigFailureCode.UNKNOWN_FIELD)
        }
        val version = root.opt("version")
        if (version !is Number) {
            return fileRefusal(DshMcpConfigFailureCode.ROOT_SHAPE_INVALID, field = "version")
        }
        val schemaVersion = version.toInt()
        if (schemaVersion < SCHEMA_VERSION || schemaVersion > CAPABILITY_SCHEMA_VERSION) {
            return fileRefusal(DshMcpConfigFailureCode.SCHEMA_VERSION_UNSUPPORTED, field = "version")
        }
        val servers = root.opt("servers")
        if (servers !is JSONArray) {
            return fileRefusal(DshMcpConfigFailureCode.ROOT_SHAPE_INVALID, field = "servers")
        }
        if (servers.length() > MAX_SERVERS) {
            return fileRefusal(DshMcpConfigFailureCode.TOO_MANY_SERVERS, field = "servers")
        }
        if (schemaVersion == SCHEMA_VERSION) {
            // `capabilities` only exists from CAPABILITY_SCHEMA_VERSION. A v1 document carrying it
            // is not half-applied: the whole file is refused with a version diagnostic.
            for (index in 0 until servers.length()) {
                val entry = servers.opt(index)
                if (entry is JSONObject && entry.has("capabilities")) {
                    return fileRefusal(DshMcpConfigFailureCode.SCHEMA_VERSION_UNSUPPORTED, field = "version")
                }
            }
        }
        val accepted = ArrayList<DshMcpServerSpec>(servers.length())
        val diagnostics = ArrayList<DshMcpConfigDiagnostic>()
        val taken = LinkedHashSet<String>()
        for (index in 0 until servers.length()) {
            val entry = servers.opt(index)
            if (entry !is JSONObject) {
                diagnostics += DshMcpConfigDiagnostic(
                    DshMcpConfigFailureCode.ROOT_SHAPE_INVALID,
                    index,
                    null,
                    "servers[$index]"
                )
                continue
            }
            when (val parsed = parseServer(index, entry, taken)) {
                is Parsed.Accepted -> {
                    taken += parsed.spec.serverName
                    accepted += parsed.spec
                }
                is Parsed.Refused -> diagnostics += parsed.diagnostic
            }
        }
        return DshMcpServerCatalog(accepted, diagnostics)
    }

    private fun parseServer(index: Int, entry: JSONObject, taken: Set<String>): Parsed {
        val transportValue = entry.opt("transport")
            ?: return refused(index, DshMcpConfigFailureCode.MISSING_FIELD, "transport")
        if (transportValue !is String) {
            return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "transport")
        }
        val transport = transportValue.trim()
        val allowedKeys = when (transport) {
            TRANSPORT_STDIO -> STDIO_KEYS
            TRANSPORT_STREAMABLE_HTTP -> HTTP_KEYS
            else -> return refused(index, DshMcpConfigFailureCode.TRANSPORT_UNSUPPORTED, "transport")
        }
        for (key in entry.keys()) {
            // A foreign key is refused without echoing it: a user document could carry a secret in
            // an unexpected key, and the entry index is enough to locate the mistake.
            if (key !in allowedKeys) return refused(index, DshMcpConfigFailureCode.UNKNOWN_FIELD, "unknown")
        }
        val nameValue = entry.opt("serverName")
            ?: return refused(index, DshMcpConfigFailureCode.MISSING_FIELD, "serverName")
        if (nameValue !is String) {
            return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "serverName")
        }
        if (!SERVER_NAME_PATTERN.matches(nameValue)) {
            // Not echoed: the raw value is exactly what a schema violation may contain.
            return refused(index, DshMcpConfigFailureCode.SERVER_NAME_INVALID, "serverName")
        }
        if (nameValue in taken) {
            return refused(
                index,
                DshMcpConfigFailureCode.SERVER_NAME_DUPLICATE,
                "serverName",
                nameValue
            )
        }
        val capabilities = LinkedHashSet<DshMcpCapability>()
        val capabilitiesValue = entry.opt("capabilities")
        if (capabilitiesValue != null) {
            if (capabilitiesValue !is JSONArray) {
                return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "capabilities", nameValue)
            }
            if (capabilitiesValue.length() > DshMcpCapability.entries.size) {
                return refused(index, DshMcpConfigFailureCode.CAPABILITY_INVALID, "capabilities", nameValue)
            }
            for (position in 0 until capabilitiesValue.length()) {
                val token = capabilitiesValue.opt(position) as? String
                    ?: return refused(
                        index,
                        DshMcpConfigFailureCode.CAPABILITY_INVALID,
                        "capabilities[$position]",
                        nameValue
                    )
                val capability = DshMcpCapability.fromToken(token)
                    ?: return refused(
                        index,
                        DshMcpConfigFailureCode.CAPABILITY_INVALID,
                        "capabilities[$position]",
                        nameValue
                    )
                if (!capabilities.add(capability)) {
                    return refused(index, DshMcpConfigFailureCode.CAPABILITY_INVALID, "capabilities", nameValue)
                }
                if (!capability.servedByPinnedPayload) {
                    // The pinned payload has no prompts code path. Refusing the entry is the honest
                    // outcome: accepting it would silently withhold the declared capability.
                    return refused(
                        index,
                        DshMcpConfigFailureCode.CAPABILITY_UNSUPPORTED,
                        "capabilities",
                        nameValue
                    )
                }
            }
        }
        return if (transport == TRANSPORT_STDIO) {
            parseStdio(index, entry, nameValue, capabilities)
        } else {
            parseHttp(index, entry, nameValue, capabilities)
        }
    }

    private fun parseStdio(
        index: Int,
        entry: JSONObject,
        serverName: String,
        capabilities: Set<DshMcpCapability>
    ): Parsed {
        val commandValue = entry.opt("command")
            ?: return refused(index, DshMcpConfigFailureCode.MISSING_FIELD, "command", serverName)
        if (commandValue !is String) {
            return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "command", serverName)
        }
        if (commandValue.isBlank() || !isSafeScalar(commandValue, MAX_COMMAND_CHARS)) {
            return refused(index, DshMcpConfigFailureCode.COMMAND_INVALID, "command", serverName)
        }
        val args = ArrayList<String>()
        val argsValue = entry.opt("args")
        if (argsValue != null) {
            if (argsValue !is JSONArray) {
                return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "args", serverName)
            }
            if (argsValue.length() > MAX_ARGS) {
                return refused(index, DshMcpConfigFailureCode.ARG_INVALID, "args", serverName)
            }
            for (position in 0 until argsValue.length()) {
                val item = argsValue.opt(position)
                if (item !is String || !isSafeScalar(item, MAX_ARG_CHARS, allowEmpty = true)) {
                    return refused(
                        index,
                        DshMcpConfigFailureCode.ARG_INVALID,
                        "args[$position]",
                        serverName
                    )
                }
                args += item
            }
        }
        val env = LinkedHashMap<String, String>()
        val envValue = entry.opt("env")
        if (envValue != null) {
            if (envValue !is JSONObject) {
                return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "env", serverName)
            }
            if (envValue.length() > MAX_ENV_ENTRIES) {
                return refused(index, DshMcpConfigFailureCode.ENV_INVALID, "env", serverName)
            }
            for (key in envValue.keys()) {
                if (!ENV_KEY_PATTERN.matches(key)) {
                    return refused(index, DshMcpConfigFailureCode.ENV_INVALID, "env key", serverName)
                }
                if (key.startsWith(DshMcpPinnedPayloadSurface.RESERVED_ENV_PREFIX, ignoreCase = true)) {
                    // The official client scrubs DSH_* names from the inherited environment; an
                    // explicit declaration is the only way one could re-enter the MCP child.
                    return refused(index, DshMcpConfigFailureCode.ENV_KEY_RESERVED, "env.$key", serverName)
                }
                val value = envValue.opt(key)
                if (value !is String || !isSafeScalar(value, MAX_ENV_VALUE_CHARS, allowEmpty = true)) {
                    // The field names the key (a schema identifier) but never the value.
                    return refused(index, DshMcpConfigFailureCode.ENV_INVALID, "env.$key", serverName)
                }
                env[key] = value
            }
        }
        return Parsed.Accepted(
            DshMcpStdioServer(
                serverName = serverName,
                command = commandValue,
                args = args,
                env = env,
                capabilities = capabilities
            )
        )
    }

    private fun parseHttp(
        index: Int,
        entry: JSONObject,
        serverName: String,
        capabilities: Set<DshMcpCapability>
    ): Parsed {
        val urlValue = entry.opt("url")
            ?: return refused(index, DshMcpConfigFailureCode.MISSING_FIELD, "url", serverName)
        if (urlValue !is String) {
            return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "url", serverName)
        }
        if (!isSafeScalar(urlValue, MAX_URL_CHARS) || !isHttpUrl(urlValue)) {
            return refused(index, DshMcpConfigFailureCode.URL_INVALID, "url", serverName)
        }
        val headers = LinkedHashMap<String, String>()
        val headersValue = entry.opt("headers")
        if (headersValue != null) {
            if (headersValue !is JSONObject) {
                return refused(index, DshMcpConfigFailureCode.FIELD_TYPE_INVALID, "headers", serverName)
            }
            if (headersValue.length() > MAX_HEADERS) {
                return refused(index, DshMcpConfigFailureCode.HEADER_INVALID, "headers", serverName)
            }
            for (key in headersValue.keys()) {
                if (!HEADER_NAME_PATTERN.matches(key)) {
                    return refused(index, DshMcpConfigFailureCode.HEADER_INVALID, "header name", serverName)
                }
                val value = headersValue.opt(key)
                if (value !is String || !isSafeScalar(value, MAX_HEADER_VALUE_CHARS, allowEmpty = true)) {
                    return refused(index, DshMcpConfigFailureCode.HEADER_INVALID, "headers.$key", serverName)
                }
                headers[key] = value
            }
        }
        return Parsed.Accepted(
            DshMcpHttpServer(
                serverName = serverName,
                url = urlValue,
                headers = headers,
                capabilities = capabilities
            )
        )
    }

    private fun refused(
        index: Int,
        code: DshMcpConfigFailureCode,
        field: String?,
        serverName: String? = null
    ): Parsed = Parsed.Refused(DshMcpConfigDiagnostic(code, index, serverName, field))

    private fun fileRefusal(
        code: DshMcpConfigFailureCode,
        field: String? = null
    ): DshMcpServerCatalog = DshMcpServerCatalog(
        servers = emptyList(),
        diagnostics = listOf(DshMcpConfigDiagnostic(code, serverIndex = -1, serverName = null, field = field))
    )

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme ?: return false
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return false
        }
        return !uri.host.isNullOrBlank()
    }

    /**
     * Bounded, control-character-free scalar. Newlines and other control characters are refused
     * outright (not merely escaped) so a declaration can never even attempt YAML structure.
     *
     * [allowEmpty] is true only for values where an empty string is meaningful (an argument, or an
     * explicitly empty env/header value); a command or URL is never allowed to be empty.
     */
    private fun isSafeScalar(value: String, maxChars: Int, allowEmpty: Boolean = false): Boolean {
        if ((value.isEmpty() && !allowEmpty) || value.length > maxChars) return false
        return value.none { it < ' ' || it == '\u007f' }
    }

    private sealed interface Parsed {
        data class Accepted(val spec: DshMcpServerSpec) : Parsed
        data class Refused(val diagnostic: DshMcpConfigDiagnostic) : Parsed
    }
}

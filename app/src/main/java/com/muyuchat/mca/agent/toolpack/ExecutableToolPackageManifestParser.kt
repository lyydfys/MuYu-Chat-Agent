package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageErrorCode
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJson
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJsonValue
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageVersion
import com.muyuchat.mca.agent.tools.AgentToolJson
import com.muyuchat.mca.agent.tools.AgentToolSchemaGuard
import org.json.JSONObject

/**
 * Strict parser for the executable `mca-tool-package.json` (format v2, N09–N12).
 *
 * Parsing is pure and side-effect free: bounded bytes in, immutable model out. It never reads a
 * host path, starts an engine or executes package content. Unknown keys are rejected; a dedicated
 * error is raised for executable/self-authorizing keys (`network`, `shell`, `native`, `hooks`, ...).
 *
 * The JSON reader is the existing strict reader of the inert package foundation re-used here (same
 * module): duplicate keys, trailing bytes, lone surrogates and non-integer numbers are refused
 * deterministically instead of depending on a platform JSON parser. Tool parameter schemas are
 * carried as a JSON string because they are validated by the frozen tool-schema subset, which is
 * independent of the manifest grammar.
 */
class ExecutableToolPackageManifestParser {

    fun parse(bytes: ByteArray): ExecutableToolPackageManifest {
        if (bytes.size.toLong() > ToolPackageLimits.MAX_MANIFEST_BYTES) {
            fail(ToolPackageErrorCode.MANIFEST_TOO_LARGE, "manifest exceeds the executable package budget")
        }
        val text = try {
            ToolPackageJson.decodeUtf8(bytes)
        } catch (failure: AgentToolPackageException) {
            throw mapJsonFailure(failure)
        }
        return parseDecoded(text)
    }

    fun parse(text: String): ExecutableToolPackageManifest {
        if (text.toByteArray(Charsets.UTF_8).size.toLong() > ToolPackageLimits.MAX_MANIFEST_BYTES) {
            fail(ToolPackageErrorCode.MANIFEST_TOO_LARGE, "manifest exceeds the executable package budget")
        }
        return parseDecoded(text)
    }

    private fun parseDecoded(text: String): ExecutableToolPackageManifest {
        val root = readRoot(text)
        rejectUnknownFields(root.fields, ROOT_FIELDS, "manifest")

        val formatVersion = requireInteger(root, "schemaVersion", "manifest")
        if (formatVersion != ToolPackageLimits.FORMAT_VERSION.toLong()) {
            fail(
                ToolPackageErrorCode.MANIFEST_UNSUPPORTED_FORMAT,
                "manifest schemaVersion must be ${ToolPackageLimits.FORMAT_VERSION} for an executable package"
            )
        }
        val hostApiVersion = requireInteger(root, "hostApiVersion", "manifest")
        if (hostApiVersion != ToolPackageLimits.HOST_API_VERSION.toLong()) {
            fail(
                ToolPackageErrorCode.MANIFEST_UNSUPPORTED_HOST_API_VERSION,
                "manifest hostApiVersion must be ${ToolPackageLimits.HOST_API_VERSION}"
            )
        }

        val packageId = ToolPackageLimits.requirePackageId(
            requireString(root, "packageId", "manifest"),
            "manifest.packageId"
        )
        val packageVersion = requireString(root, "packageVersion", "manifest")
        if (packageVersion.length > ToolPackageLimits.MAX_VERSION_CHARS ||
            ToolPackageVersion.parseOrNull(packageVersion) == null
        ) {
            fail(
                ToolPackageErrorCode.MANIFEST_INVALID_VERSION,
                "manifest.packageVersion must be a bounded semantic version"
            )
        }

        val displayName = requireString(root, "displayName", "manifest")
        if (displayName.isBlank() || displayName.length > ToolPackageLimits.MAX_DISPLAY_NAME_CHARS) {
            fail(ToolPackageErrorCode.MANIFEST_INVALID_FIELD, "manifest.displayName is outside the bounded range")
        }
        val description = optionalString(root, "description", "manifest").orEmpty()
        if (description.toByteArray(Charsets.UTF_8).size > ToolPackageLimits.MAX_DESCRIPTION_BYTES) {
            fail(ToolPackageErrorCode.MANIFEST_INVALID_FIELD, "manifest.description exceeds its byte budget")
        }

        val entry = parseScriptPath(requireString(root, "entry", "manifest"), "manifest.entry")
        val hostApis = parseHostApis(root.fields["hostApis"])
        val tools = parseTools(root.fields["tools"])
        val dependencies = parseDependencies(root.fields[DEPENDENCIES_FIELD], packageId)

        // A manifest written before the dependency lock existed must keep byte-identical canonical
        // bytes. An explicit empty array is normalised away for the same reason.
        val canonicalRoot = if (dependencies.isEmpty()) {
            ToolPackageJsonValue.ObjectValue(root.fields - DEPENDENCIES_FIELD)
        } else {
            root
        }
        val canonicalJson = ToolPackageJson.canonical(canonicalRoot)
        val manifestSha256 = ToolPackageJson.sha256Hex(canonicalJson.toByteArray(Charsets.UTF_8))
        return ExecutableToolPackageManifest(
            formatVersion = formatVersion.toInt(),
            packageId = packageId,
            packageVersion = packageVersion,
            hostApiVersion = hostApiVersion.toInt(),
            displayName = displayName,
            description = description,
            entry = entry,
            hostApis = hostApis,
            tools = tools,
            dependencies = dependencies,
            canonicalJson = canonicalJson,
            manifestSha256 = manifestSha256
        )
    }

    /**
     * Optional exact dependency lock. Entries are order-preserving in canonical bytes, unique by
     * package id, cannot name the package itself and are fully bounded before any store access.
     */
    private fun parseDependencies(
        value: ToolPackageJsonValue?,
        packageId: String
    ): List<ToolPackageDependency> {
        if (value == null) return emptyList()
        val array = value as? ToolPackageJsonValue.ArrayValue
            ?: fail(
                ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
                "manifest.dependencies must be an array"
            )
        if (array.items.isEmpty()) return emptyList()
        if (array.items.size > ToolPackageLimits.MAX_DEPENDENCIES) {
            fail(
                ToolPackageErrorCode.MANIFEST_DEPENDENCY_TOO_MANY,
                "manifest.dependencies declares more than ${ToolPackageLimits.MAX_DEPENDENCIES} entries"
            )
        }
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val context = "manifest.dependencies[$index]"
            val obj = item as? ToolPackageJsonValue.ObjectValue
                ?: fail(
                    ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
                    "$context must be an object"
                )
            for (key in obj.fields.keys) {
                if (key !in DEPENDENCY_FIELDS) {
                    fail(
                        ToolPackageErrorCode.MANIFEST_DEPENDENCY_UNKNOWN_FIELD,
                        "$context contains an unsupported field '${safeLabel(key)}'"
                    )
                }
            }
            val dependencyId = requireDependencyString(obj, "packageId", context)
            if (!AgentToolPackageToolRef.isValidPackageId(dependencyId)) {
                fail(
                    ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
                    "$context.packageId must match [a-z0-9][a-z0-9-]{1,63}"
                )
            }
            if (dependencyId == packageId) {
                fail(
                    ToolPackageErrorCode.MANIFEST_DEPENDENCY_SELF,
                    "$context must not depend on the package itself"
                )
            }
            val version = requireDependencyString(obj, "version", context)
            if (version.isBlank() || version.length > ToolPackageLimits.MAX_VERSION_CHARS) {
                fail(
                    ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
                    "$context.version is outside the bounded range"
                )
            }
            val contentHash = requireDependencyString(obj, "contentHash", context)
            if (!CONTENT_HASH.matches(contentHash)) {
                fail(
                    ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
                    "$context.contentHash must be a lowercase SHA-256 value"
                )
            }
            if (!seen.add(dependencyId)) {
                fail(
                    ToolPackageErrorCode.MANIFEST_DEPENDENCY_DUPLICATE,
                    "manifest.dependencies repeats a package id"
                )
            }
            ToolPackageDependency(
                packageId = dependencyId,
                version = version,
                contentHash = contentHash
            )
        }
    }

    private fun requireDependencyString(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): String = when (val value = obj.fields[key]) {
        null -> fail(
            ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
            "$context is missing required field '$key'"
        )
        is ToolPackageJsonValue.StringValue -> value.value
        else -> fail(
            ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
            "$context.$key must be a string"
        )
    }

    private fun parseHostApis(value: ToolPackageJsonValue?): List<String> {
        val array = value as? ToolPackageJsonValue.ArrayValue
            ?: fail(ToolPackageErrorCode.MANIFEST_HOST_API_INVALID, "manifest.hostApis must be an array")
        if (array.items.size > ToolPackageLimits.MAX_HOST_APIS) {
            fail(
                ToolPackageErrorCode.MANIFEST_HOST_API_TOO_MANY,
                "manifest.hostApis declares more than ${ToolPackageLimits.MAX_HOST_APIS} capabilities"
            )
        }
        val seen = HashSet<String>()
        return array.items.map { item ->
            val method = (item as? ToolPackageJsonValue.StringValue)?.value
                ?: fail(
                    ToolPackageErrorCode.MANIFEST_HOST_API_INVALID,
                    "manifest.hostApis must contain method ids"
                )
            if (!AgentToolPackageToolRef.isValidHostOperationId(method) ||
                ToolPackageHostApis.find(method) == null
            ) {
                fail(
                    ToolPackageErrorCode.MANIFEST_HOST_API_UNKNOWN,
                    "manifest.hostApis names a host API this build does not expose"
                )
            }
            if (!seen.add(method)) {
                fail(
                    ToolPackageErrorCode.MANIFEST_HOST_API_DUPLICATE,
                    "manifest.hostApis repeats a host API"
                )
            }
            method
        }
    }

    private fun parseTools(value: ToolPackageJsonValue?): List<ExecutableToolPackageTool> {
        val array = value as? ToolPackageJsonValue.ArrayValue
            ?: fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "manifest.tools must be an array")
        if (array.items.isEmpty()) {
            fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "an executable package must declare at least one tool")
        }
        if (array.items.size > ToolPackageLimits.MAX_TOOLS) {
            fail(
                ToolPackageErrorCode.MANIFEST_TOO_MANY_TOOLS,
                "an executable package may declare at most ${ToolPackageLimits.MAX_TOOLS} tools"
            )
        }
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val tool = parseTool(index, item)
            if (!seen.add(tool.name)) {
                fail(ToolPackageErrorCode.MANIFEST_DUPLICATE_TOOL, "manifest repeats a tool name")
            }
            tool
        }
    }

    private fun parseTool(index: Int, value: ToolPackageJsonValue): ExecutableToolPackageTool {
        val context = "tools[$index]"
        val obj = value as? ToolPackageJsonValue.ObjectValue
            ?: fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "$context must be an object")
        rejectUnknownFields(obj.fields, TOOL_FIELDS, context)

        val name = requireString(obj, "name", context)
        if (!AgentToolPackageToolRef.isValidToolName(name)) {
            fail(ToolPackageErrorCode.MANIFEST_INVALID_ID, "$context.name must be lowercase snake case")
        }
        val displayName = requireString(obj, "displayName", context)
        if (displayName.isBlank() || displayName.length > ToolPackageLimits.MAX_DISPLAY_NAME_CHARS) {
            fail(ToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context.displayName is outside the bounded range")
        }
        val description = requireString(obj, "description", context)
        if (description.isBlank() ||
            description.toByteArray(Charsets.UTF_8).size > ToolPackageLimits.MAX_TOOL_DESCRIPTION_BYTES
        ) {
            fail(ToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context.description is outside the bounded range")
        }
        val entry = parseScriptPath(requireString(obj, "entry", context), "$context.entry")
        val schemaJson = parseToolSchema(requireString(obj, "inputSchema", context), context)
        val timeoutMillis = when (val raw = obj.fields["timeoutMillis"]) {
            null -> ToolPackageLimits.DEFAULT_TOOL_TIMEOUT_MILLIS
            is ToolPackageJsonValue.IntegerValue -> raw.value
            else -> fail(
                ToolPackageErrorCode.MANIFEST_TOOL_TIMEOUT_INVALID,
                "$context.timeoutMillis must be an integer"
            )
        }
        if (timeoutMillis !in ToolPackageLimits.MIN_TOOL_TIMEOUT_MILLIS..ToolPackageLimits.MAX_TOOL_TIMEOUT_MILLIS) {
            fail(
                ToolPackageErrorCode.MANIFEST_TOOL_TIMEOUT_INVALID,
                "$context.timeoutMillis is outside the bounded range"
            )
        }
        return ExecutableToolPackageTool(
            name = name,
            displayName = displayName,
            description = description,
            entry = entry,
            inputSchemaJson = schemaJson,
            timeoutMillis = timeoutMillis
        )
    }

    private fun parseToolSchema(raw: String, context: String): String {
        if (raw.toByteArray(Charsets.UTF_8).size > ToolPackageLimits.MAX_TOOL_SCHEMA_BYTES) {
            fail(ToolPackageErrorCode.MANIFEST_INVALID_SCHEMA, "$context.inputSchema exceeds its byte budget")
        }
        val parsed = try {
            JSONObject(raw)
        } catch (_: Exception) {
            fail(ToolPackageErrorCode.MANIFEST_INVALID_SCHEMA, "$context.inputSchema is not a JSON object")
        }
        try {
            AgentToolSchemaGuard.requireSupported(raw)
        } catch (_: Exception) {
            fail(
                ToolPackageErrorCode.MANIFEST_INVALID_SCHEMA,
                "$context.inputSchema is not a supported MCA tool schema"
            )
        }
        return AgentToolJson.canonical(parsed)
    }

    private fun parseScriptPath(raw: String, field: String): String =
        ToolPackageScriptPaths.normalize(raw)
            ?: fail(
                ToolPackageErrorCode.MANIFEST_INVALID_ENTRY,
                "$field must be a normalised relative .js path without '..' or absolute roots"
            )

    private fun readRoot(text: String): ToolPackageJsonValue.ObjectValue {
        val value = try {
            ToolPackageJson.parse(text)
        } catch (failure: AgentToolPackageException) {
            throw mapJsonFailure(failure)
        }
        return value as? ToolPackageJsonValue.ObjectValue
            ?: fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "manifest root must be a JSON object")
    }

    private fun mapJsonFailure(failure: AgentToolPackageException): ToolPackageException {
        val code = when (failure.error.code) {
            AgentToolPackageErrorCode.MANIFEST_DUPLICATE_KEY -> ToolPackageErrorCode.MANIFEST_DUPLICATE_KEY
            AgentToolPackageErrorCode.MANIFEST_INVALID_UTF8 -> ToolPackageErrorCode.MANIFEST_INVALID_UTF8
            AgentToolPackageErrorCode.MANIFEST_TOO_LARGE -> ToolPackageErrorCode.MANIFEST_TOO_LARGE
            else -> ToolPackageErrorCode.MANIFEST_MALFORMED
        }
        val message = when (code) {
            ToolPackageErrorCode.MANIFEST_DUPLICATE_KEY -> "manifest repeats an object key"
            ToolPackageErrorCode.MANIFEST_INVALID_UTF8 -> "manifest is not valid UTF-8"
            ToolPackageErrorCode.MANIFEST_TOO_LARGE -> "manifest exceeds the executable package budget"
            else -> "manifest is not well-formed JSON"
        }
        return ToolPackageException(ToolPackageError(code, message))
    }

    private fun rejectUnknownFields(
        fields: Map<String, ToolPackageJsonValue>,
        allowed: Set<String>,
        context: String
    ) {
        for (key in fields.keys) {
            if (key in allowed) continue
            val code = if (key.lowercase() in ToolPackageLimits.FORBIDDEN_FIELDS) {
                ToolPackageErrorCode.MANIFEST_FORBIDDEN_FIELD
            } else {
                ToolPackageErrorCode.MANIFEST_UNKNOWN_FIELD
            }
            fail(code, "$context contains an unsupported field '${safeLabel(key)}'")
        }
    }

    private fun requireString(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): String = when (val value = obj.fields[key]) {
        null -> fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "$context is missing required field '$key'")
        is ToolPackageJsonValue.StringValue -> value.value
        else -> fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "$context.$key must be a string")
    }

    private fun optionalString(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): String? = when (val value = obj.fields[key]) {
        null -> null
        is ToolPackageJsonValue.StringValue -> value.value
        else -> fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "$context.$key must be a string")
    }

    private fun requireInteger(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): Long = when (val value = obj.fields[key]) {
        null -> fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "$context is missing required field '$key'")
        is ToolPackageJsonValue.IntegerValue -> value.value
        else -> fail(ToolPackageErrorCode.MANIFEST_MALFORMED, "$context.$key must be an integer")
    }

    private fun safeLabel(key: String): String = buildString {
        for (char in key.take(64)) {
            append(if (char.isISOControl() || char == '\'') '?' else char)
        }
    }

    private fun fail(code: ToolPackageErrorCode, message: String): Nothing = toolPackageFailure(code, message)

    private companion object {
        const val DEPENDENCIES_FIELD = "dependencies"

        val ROOT_FIELDS = setOf(
            "schemaVersion", "packageId", "packageVersion", "hostApiVersion",
            "displayName", "description", "entry", "hostApis", "tools", DEPENDENCIES_FIELD
        )
        val TOOL_FIELDS = setOf(
            "name", "displayName", "description", "entry", "inputSchema", "timeoutMillis"
        )
        val DEPENDENCY_FIELDS = setOf("packageId", "version", "contentHash")
        val CONTENT_HASH = Regex("^[0-9a-f]{64}$")
    }
}

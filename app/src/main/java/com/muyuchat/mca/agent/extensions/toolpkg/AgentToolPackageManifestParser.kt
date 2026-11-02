package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentToolPackageHostOperation
import com.muyuchat.core.agent.contract.AgentToolPackageHostParameter
import com.muyuchat.core.agent.contract.AgentToolPackageParameterType
import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import com.muyuchat.core.agent.contract.AgentToolSideEffect

/**
 * Strict parser for `mca-tool-package.json` (proposal P0-B).
 *
 * The package supplies **no schema and no executor**. Every tool must select an allowlisted,
 * code-owned read-only host operation from [hostCatalog]; `preset` may only fix parameters that the
 * host operation already declares, and `expose` may only narrow the model-facing subset. Unknown
 * root/tool/resource keys are rejected, with a dedicated error for executable/self-authorizing keys
 * (`script`, `wasm`, `dex`, `hooks`, `executor`, `network`, ...).
 *
 * Parsing is pure: it reads bounded bytes and returns an immutable model. It never opens a socket,
 * writes a file, creates Android objects or imports [com.muyuchat.core.agent.contract.AgentToolExecutor].
 */
class AgentToolPackageManifestParser(
    private val hostCatalog: AgentToolPackageHostCatalog
) {
    fun parse(bytes: ByteArray): AgentToolPackageManifest {
        if (bytes.size.toLong() > AgentToolPackageLimits.MAX_MANIFEST_BYTES) {
            manifestTooLarge()
        }
        val text = ToolPackageJson.decodeUtf8(bytes)
        return parseDecoded(text)
    }

    /** Convenience for already-decoded UTF-8; the size limit is re-applied to the UTF-8 bytes. */
    fun parse(text: String): AgentToolPackageManifest {
        if (text.toByteArray(Charsets.UTF_8).size.toLong() > AgentToolPackageLimits.MAX_MANIFEST_BYTES) {
            manifestTooLarge()
        }
        return parseDecoded(text)
    }

    private fun parseDecoded(text: String): AgentToolPackageManifest {
        val root = ToolPackageJson.parse(text) as? ToolPackageJsonValue.ObjectValue
            ?: fail(AgentToolPackageErrorCode.MANIFEST_MALFORMED, "manifest root must be a JSON object")
        rejectUnknownFields(root.fields, ROOT_FIELDS, "manifest")

        val schemaVersionValue = requireInteger(root, "schemaVersion", "manifest")
        if (schemaVersionValue != AgentToolPackageLimits.SCHEMA_VERSION.toLong()) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_UNSUPPORTED_SCHEMA_VERSION,
                "manifest schemaVersion must be ${AgentToolPackageLimits.SCHEMA_VERSION}"
            )
        }
        val schemaVersion = schemaVersionValue.toInt()
        val apiVersionValue = requireInteger(root, "apiVersion", "manifest")
        if (apiVersionValue != AgentToolPackageLimits.API_VERSION.toLong()) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_UNSUPPORTED_API_VERSION,
                "manifest apiVersion must be ${AgentToolPackageLimits.API_VERSION}"
            )
        }
        val apiVersion = apiVersionValue.toInt()

        val packageId = AgentToolPackageLimits.requirePackageId(
            requireString(root, "packageId", "manifest"),
            "packageId"
        )
        val packageVersion = requireString(root, "packageVersion", "manifest")
        ToolPackageVersion.requireVersion(packageVersion, "packageVersion")

        val displayName = requireString(root, "displayName", "manifest")
        requireBoundedDisplayName(displayName, "manifest displayName")
        val description = optionalString(root, "description", "manifest").orEmpty()
        requireAtMostUtf8(description, AgentToolPackageLimits.MAX_DESCRIPTION_BYTES, "manifest description")

        val requires = parseRequires(root.fields["requires"], packageId)
        val tools = parseTools(root.fields["tools"])
        val resources = parseResources(root.fields["resources"])

        val canonicalJson = ToolPackageJson.canonical(root)
        val manifestSha256 = ToolPackageJson.sha256Hex(canonicalJson.toByteArray(Charsets.UTF_8))
        return AgentToolPackageManifest(
            schemaVersion = schemaVersion,
            packageId = packageId,
            packageVersion = packageVersion,
            apiVersion = apiVersion,
            displayName = displayName,
            description = description,
            requires = requires,
            tools = tools,
            resources = resources,
            canonicalJson = canonicalJson,
            manifestSha256 = manifestSha256
        )
    }

    private fun parseRequires(
        value: ToolPackageJsonValue?,
        packageId: String
    ): List<AgentToolPackageDependency> {
        val array = when (value) {
            null -> return emptyList()
            is ToolPackageJsonValue.ArrayValue -> value
            else -> fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "manifest requires must be an array")
        }
        if (array.items.size > AgentToolPackageLimits.MAX_REQUIRES) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
                "manifest requires more than ${AgentToolPackageLimits.MAX_REQUIRES} dependencies"
            )
        }
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val context = "requires[$index]"
            val obj = item as? ToolPackageJsonValue.ObjectValue
                ?: fail(AgentToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID, "$context must be an object")
            rejectUnknownFields(obj.fields, DEPENDENCY_FIELDS, context)
            val dependencyId = AgentToolPackageLimits.requirePackageId(
                requireString(obj, "packageId", context),
                "$context.packageId"
            )
            if (dependencyId == packageId) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_DEPENDENCY_SELF,
                    "a tool package must not require itself"
                )
            }
            if (!seen.add(dependencyId)) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_DEPENDENCY_DUPLICATE,
                    "manifest repeats a dependency package id"
                )
            }
            val versionRange = requireString(obj, "version", context)
            if (ToolPackageVersionRange.parseOrNull(versionRange) == null) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID,
                    "$context.version must be '*', an exact version or a '>=/<=' range"
                )
            }
            AgentToolPackageDependency(dependencyId, versionRange.trim())
        }
    }

    private fun parseTools(value: ToolPackageJsonValue?): List<AgentToolPackageTool> {
        val array = value as? ToolPackageJsonValue.ArrayValue
            ?: fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "manifest tools must be an array")
        if (array.items.isEmpty()) {
            fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "a package must declare at least one tool")
        }
        if (array.items.size > AgentToolPackageLimits.MAX_TOOLS) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_TOO_MANY_TOOLS,
                "a package may declare at most ${AgentToolPackageLimits.MAX_TOOLS} tools"
            )
        }
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val tool = parseTool(index, item)
            if (!seen.add(tool.name)) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_DUPLICATE_TOOL,
                    "manifest repeats a tool name: '${tool.name}'"
                )
            }
            tool
        }
    }

    private fun parseTool(index: Int, value: ToolPackageJsonValue): AgentToolPackageTool {
        val context = "tools[$index]"
        val obj = value as? ToolPackageJsonValue.ObjectValue
            ?: fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context must be an object")
        rejectUnknownFields(obj.fields, TOOL_FIELDS, context)

        val name = requireString(obj, "name", context)
        if (!AgentToolPackageToolRef.isValidToolName(name)) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD,
                "$context.name must be lowercase snake case"
            )
        }
        val displayName = requireString(obj, "displayName", context)
        requireBoundedDisplayName(displayName, "$context.displayName")
        val description = requireString(obj, "description", context)
        if (description.isBlank()) {
            fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context.description must not be blank")
        }
        requireAtMostUtf8(description, AgentToolPackageLimits.MAX_TOOL_DESCRIPTION_BYTES, "$context.description")

        val hostOperationId = requireString(obj, "hostOperation", context)
        if (!AgentToolPackageToolRef.isValidHostOperationId(hostOperationId)) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_HOST_OPERATION_NOT_ALLOWED,
                "$context.hostOperation is not a dotted lowercase operation id"
            )
        }
        val operation = hostCatalog.find(hostOperationId)
            ?: fail(
                AgentToolPackageErrorCode.MANIFEST_HOST_OPERATION_NOT_ALLOWED,
                "$context.hostOperation is not an allowlisted code-owned read-only host operation"
            )
        if (operation.sideEffect != AgentToolSideEffect.READ_ONLY) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_HOST_OPERATION_NOT_ALLOWED,
                "$context.hostOperation is not read-only in this release"
            )
        }

        val preset = when (val rawPreset = obj.fields["preset"]) {
            null -> ToolPackageJsonValue.ObjectValue(emptyMap())
            is ToolPackageJsonValue.ObjectValue -> rawPreset
            else -> fail(AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID, "$context.preset must be an object")
        }
        if (preset.fields.size > AgentToolPackageLimits.MAX_PRESET_KEYS) {
            fail(AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID, "$context.preset declares too many keys")
        }
        preset.fields.forEach { (parameterName, parameterValue) ->
            val parameter = operation.parameter(parameterName)
                ?: fail(
                    AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                    "$context.preset names a parameter the host operation does not declare"
                )
            validatePresetValue(context, parameter, parameterValue)
        }

        val expose = parseExpose(obj.fields["expose"], context)
        for (exposedName in expose) {
            if (operation.parameter(exposedName) == null) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID,
                    "$context.expose names a parameter the host operation does not declare"
                )
            }
            if (preset.fields.containsKey(exposedName)) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID,
                    "$context.expose must not re-expose a preset-locked parameter"
                )
            }
        }
        for (parameter in operation.parameters) {
            if (!parameter.required) continue
            if (!preset.fields.containsKey(parameter.name) && parameter.name !in expose) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID,
                    "$context leaves a required host parameter neither preset nor exposed"
                )
            }
        }

        val timeoutMillis = when (val rawTimeout = obj.fields["timeoutMillis"]) {
            null -> operation.maxTimeoutMillis
            is ToolPackageJsonValue.IntegerValue -> {
                if (rawTimeout.value < AgentToolPackageLimits.MIN_TIMEOUT_MILLIS ||
                    rawTimeout.value > operation.maxTimeoutMillis
                ) {
                    fail(
                        AgentToolPackageErrorCode.MANIFEST_TIMEOUT_INVALID,
                        "$context.timeoutMillis must be between 1 and the host operation timeout"
                    )
                }
                rawTimeout.value
            }
            else -> fail(
                AgentToolPackageErrorCode.MANIFEST_TIMEOUT_INVALID,
                "$context.timeoutMillis must be an integer"
            )
        }

        return AgentToolPackageTool(
            name = name,
            displayName = displayName,
            description = description,
            hostOperation = operation.operationId,
            presetJson = ToolPackageJson.canonical(preset),
            presetKeys = preset.fields.keys.sorted(),
            exposedParameters = expose.sorted(),
            timeoutMillis = timeoutMillis
        )
    }

    private fun validatePresetValue(
        context: String,
        parameter: AgentToolPackageHostParameter,
        value: ToolPackageJsonValue
    ) {
        when (parameter.type) {
            AgentToolPackageParameterType.STRING -> {
                when (value) {
                    is ToolPackageJsonValue.StringValue -> {
                        val codePoints = value.value.codePointCount(0, value.value.length)
                        val minLength = parameter.minLength
                        if (minLength != null && codePoints < minLength) {
                            fail(
                                AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                                "$context.preset is below the host parameter minLength"
                            )
                        }
                        val maxLength = parameter.maxLength
                        if (maxLength != null && codePoints > maxLength) {
                            fail(
                                AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                                "$context.preset exceeds the host parameter maxLength"
                            )
                        }
                    }
                    ToolPackageJsonValue.NullValue -> if (!parameter.nullable) {
                        fail(
                            AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                            "$context.preset sets a non-nullable host parameter to null"
                        )
                    }
                    else -> fail(
                        AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                        "$context.preset value does not match the host parameter type"
                    )
                }
            }
            AgentToolPackageParameterType.INTEGER -> {
                when (value) {
                    is ToolPackageJsonValue.IntegerValue -> {
                        val minimum = parameter.minimum
                        val maximum = parameter.maximum
                        if (minimum != null && value.value < minimum) {
                            fail(
                                AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                                "$context.preset is below the host parameter minimum"
                            )
                        }
                        if (maximum != null && value.value > maximum) {
                            fail(
                                AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                                "$context.preset exceeds the host parameter maximum"
                            )
                        }
                    }
                    ToolPackageJsonValue.NullValue -> if (!parameter.nullable) {
                        fail(
                            AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                            "$context.preset sets a non-nullable host parameter to null"
                        )
                    }
                    else -> fail(
                        AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                        "$context.preset value does not match the host parameter type"
                    )
                }
            }
            AgentToolPackageParameterType.BOOLEAN -> {
                when (value) {
                    is ToolPackageJsonValue.BooleanValue -> Unit
                    ToolPackageJsonValue.NullValue -> if (!parameter.nullable) {
                        fail(
                            AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                            "$context.preset sets a non-nullable host parameter to null"
                        )
                    }
                    else -> fail(
                        AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                        "$context.preset value does not match the host parameter type"
                    )
                }
            }
        }
    }

    private fun parseExpose(value: ToolPackageJsonValue?, context: String): List<String> {
        val array = when (value) {
            null -> return emptyList()
            is ToolPackageJsonValue.ArrayValue -> value
            else -> fail(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID, "$context.expose must be an array")
        }
        if (array.items.size > AgentToolPackageLimits.MAX_EXPOSED_PARAMETERS) {
            fail(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID, "$context.expose declares too many parameters")
        }
        val seen = HashSet<String>()
        return array.items.map { item ->
            val name = (item as? ToolPackageJsonValue.StringValue)?.value
                ?: fail(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID, "$context.expose must contain strings")
            if (!AgentToolPackageToolRef.isValidParameterName(name)) {
                fail(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID, "$context.expose contains an invalid parameter name")
            }
            if (!seen.add(name)) {
                fail(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID, "$context.expose repeats a parameter name")
            }
            name
        }
    }

    private fun parseResources(value: ToolPackageJsonValue?): List<AgentToolPackageResource> {
        val array = when (value) {
            null -> return emptyList()
            is ToolPackageJsonValue.ArrayValue -> value
            else -> fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "manifest resources must be an array")
        }
        if (array.items.size > AgentToolPackageLimits.MAX_RESOURCES) {
            fail(
                AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD,
                "a package may declare at most ${AgentToolPackageLimits.MAX_RESOURCES} resources"
            )
        }
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val context = "resources[$index]"
            val obj = item as? ToolPackageJsonValue.ObjectValue
                ?: fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context must be an object")
            rejectUnknownFields(obj.fields, RESOURCE_FIELDS, context)
            val rawPath = requireString(obj, "path", context)
            val normalized = AgentToolPackagePathPolicy.normalizeFile(rawPath, context)
            if (normalized in AgentToolPackageLimits.RESERVED_RESOURCE_PATHS) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD,
                    "$context.path shadows a package control file"
                )
            }
            if (!seen.add(normalized)) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_DUPLICATE_RESOURCE,
                    "manifest repeats a normalized resource path"
                )
            }
            val mediaType = requireString(obj, "mediaType", context)
            val baseMediaType = mediaType.substringBefore(';').trim().lowercase()
            val extension = normalized.substringAfterLast('.', "").lowercase()
            val allowedMediaTypes = AgentToolPackageLimits.RESOURCE_MEDIA_TYPES[extension]
                ?: fail(
                    AgentToolPackageErrorCode.MANIFEST_RESOURCE_TYPE_UNSUPPORTED,
                    "$context is not a Markdown/text/metadata resource"
                )
            if (baseMediaType !in allowedMediaTypes) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_RESOURCE_TYPE_UNSUPPORTED,
                    "$context.mediaType does not match its text/metadata extension"
                )
            }
            val sha256 = requireString(obj, "sha256", context)
            if (!AgentToolPackageToolRef.isValidSha256(sha256)) {
                fail(
                    AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD,
                    "$context.sha256 must be lowercase SHA-256"
                )
            }
            AgentToolPackageResource(path = normalized, mediaType = baseMediaType, sha256 = sha256)
        }
    }

    private fun rejectUnknownFields(
        fields: Map<String, ToolPackageJsonValue>,
        allowed: Set<String>,
        context: String
    ) {
        for (key in fields.keys) {
            if (key in allowed) continue
            val code = if (key in AgentToolPackageLimits.EXECUTABLE_FIELD_NAMES) {
                AgentToolPackageErrorCode.MANIFEST_EXECUTABLE_FIELD
            } else {
                AgentToolPackageErrorCode.MANIFEST_UNKNOWN_FIELD
            }
            fail(code, "$context contains an unsupported field: '${safeFieldLabel(key)}'")
        }
    }

    private fun requireString(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): String = when (val value = obj.fields[key]) {
        null -> fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context is missing required field '$key'")
        is ToolPackageJsonValue.StringValue -> value.value
        else -> fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context.$key must be a string")
    }

    private fun optionalString(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): String? = when (val value = obj.fields[key]) {
        null -> null
        is ToolPackageJsonValue.StringValue -> value.value
        else -> fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context.$key must be a string")
    }

    private fun requireInteger(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): Long = when (val value = obj.fields[key]) {
        null -> fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context is missing required field '$key'")
        is ToolPackageJsonValue.IntegerValue -> value.value
        else -> fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$context.$key must be an integer")
    }

    private fun requireBoundedDisplayName(value: String, field: String) {
        if (value.isBlank()) {
            fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$field must not be blank")
        }
        if (value.length > AgentToolPackageLimits.MAX_DISPLAY_NAME_CHARS) {
            fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$field exceeds its length limit")
        }
    }

    private fun requireAtMostUtf8(value: String, maximum: Int, field: String) {
        if (value.toByteArray(Charsets.UTF_8).size > maximum) {
            fail(AgentToolPackageErrorCode.MANIFEST_INVALID_FIELD, "$field exceeds its byte limit")
        }
    }

    private fun manifestTooLarge(): Nothing = fail(
        AgentToolPackageErrorCode.MANIFEST_TOO_LARGE,
        "manifest exceeds ${AgentToolPackageLimits.MAX_MANIFEST_BYTES} bytes"
    )

    /**
     * Limits the diagnostic to a short printable label so a malicious key cannot inject control
     * characters or an unbounded string into an error message.
     */
    private fun safeFieldLabel(key: String): String = buildString {
        for (char in key.take(64)) {
            append(if (char.isISOControl()) '?' else char)
        }
    }

    private fun fail(code: AgentToolPackageErrorCode, message: String): Nothing =
        toolPackageFailure(code, message)

    private companion object {
        val ROOT_FIELDS = setOf(
            "schemaVersion", "packageId", "packageVersion", "apiVersion",
            "displayName", "description", "requires", "tools", "resources"
        )
        val TOOL_FIELDS = setOf(
            "name", "displayName", "description", "hostOperation",
            "preset", "expose", "timeoutMillis"
        )
        val RESOURCE_FIELDS = setOf("path", "mediaType", "sha256")
        val DEPENDENCY_FIELDS = setOf("packageId", "version")
    }
}

package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackagePathPolicy
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJsonValue
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageVersion

/**
 * Shared, pure parsing state for one third-party manifest import preview.
 *
 * The session owns the manifest object, the validated listing and the growing report. It has no file,
 * engine, clock or network dependency; every value accessor either returns a bounded value or throws
 * an [ThirdPartyImportRejection] carrying the exact field path.
 */
internal class ThirdPartyManifestSession(
    val root: ToolPackageJsonValue.ObjectValue,
    val listing: ThirdPartyNormalizedListing,
    val declaredContexts: List<ThirdPartyDeclaredContext>
) {
    val findings = ArrayList<ThirdPartyCompatibilityFinding>()

    fun add(
        category: ThirdPartyCompatibilityCategory,
        symbol: String,
        fieldPath: String,
        status: ThirdPartyCompatibilityStatus,
        reason: String,
        alternative: String? = null
    ) {
        findings += ThirdPartyCompatibilityFinding(
            category = category,
            symbol = symbol,
            fieldPath = fieldPath,
            status = status,
            reason = reason,
            alternative = alternative
        )
    }

    fun rejectUnknownFields(
        fields: Map<String, ToolPackageJsonValue>,
        allowed: Set<String>,
        context: String
    ) {
        for (key in fields.keys) {
            if (key in allowed) continue
            val code = if (key.lowercase() in ToolPackageLimits.FORBIDDEN_FIELDS) {
                ThirdPartyImportErrorCode.FORBIDDEN_FIELD
            } else {
                ThirdPartyImportErrorCode.UNKNOWN_FIELD
            }
            thirdpartyFailure(
                code,
                "$context.${safeManifestKey(key)}",
                "the manifest contains a field outside the supported third-party schema"
            )
        }
    }

    fun readLocalized(key: String): String {
        val value = root.fields[key] ?: return ""
        return validateLocalized(value, "manifest.$key")
    }

    /** Validates a LocalizedText value at an arbitrary field path and returns its preferred text. */
    fun validateLocalized(value: ToolPackageJsonValue, path: String): String {
        val text = when (value) {
            is ToolPackageJsonValue.StringValue -> value.value
            is ToolPackageJsonValue.ObjectValue -> {
                if (value.fields.isEmpty()) {
                    thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, path, "localized text is empty")
                }
                value.fields.forEach { (language, localized) ->
                    if (!THIRD_PARTY_LOCALE_KEY.matches(language) ||
                        (localized as? ToolPackageJsonValue.StringValue) == null
                    ) {
                        thirdpartyFailure(
                            ThirdPartyImportErrorCode.INVALID_FIELD,
                            path,
                            "localized entries must be language-keyed strings"
                        )
                    }
                }
                val preferred = value.fields["default"] ?: value.fields["en"] ?: value.fields["zh"]
                preferred?.let { (it as ToolPackageJsonValue.StringValue).value }
                    ?: (value.fields.values.first() as ToolPackageJsonValue.StringValue).value
            }
            else -> thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                path,
                "localized text must be a string or an object of strings"
            )
        }
        if (text.toByteArray(Charsets.UTF_8).size > THIRD_PARTY_MAX_LOCALIZED_BYTES) {
            thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, path, "localized text exceeds its budget")
        }
        return text
    }

    fun requireString(key: String, context: String): String = requireString(root, key, context)

    fun requireString(obj: ToolPackageJsonValue.ObjectValue, key: String, context: String): String =
        when (val value = obj.fields[key]) {
            null -> thirdpartyFailure(
                ThirdPartyImportErrorCode.MISSING_FIELD,
                "$context.$key",
                "missing required field '$key'"
            )
            is ToolPackageJsonValue.StringValue -> value.value
            else -> thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "$context.$key",
                "field '$key' must be a string"
            )
        }

    fun optionalString(key: String, context: String): String? = optionalString(root, key, context)

    fun optionalString(obj: ToolPackageJsonValue.ObjectValue, key: String, context: String): String? =
        when (val value = obj.fields[key]) {
            null -> null
            is ToolPackageJsonValue.StringValue -> value.value
            else -> thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "$context.$key",
                "field '$key' must be a string"
            )
        }

    fun optionalBoolean(obj: ToolPackageJsonValue.ObjectValue, key: String, context: String): Boolean? =
        when (val value = obj.fields[key]) {
            null -> null
            is ToolPackageJsonValue.BooleanValue -> value.value
            else -> thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "$context.$key",
                "field '$key' must be a boolean"
            )
        }

    fun optionalArray(key: String, context: String): ToolPackageJsonValue.ArrayValue? =
        when (val value = root.fields[key]) {
            null -> null
            is ToolPackageJsonValue.ArrayValue -> value
            else -> thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "$context.$key",
                "field '$key' must be an array"
            )
        }

    fun optionalStringArray(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): List<String> {
        val value = obj.fields[key] ?: return emptyList()
        val array = value as? ToolPackageJsonValue.ArrayValue
            ?: thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "$context.$key",
                "field '$key' must be an array of strings"
            )
        if (array.items.size > THIRD_PARTY_MAX_EXPORTS) {
            thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$context.$key", "too many exported names")
        }
        return array.items.mapIndexed { index, item ->
            val text = (item as? ToolPackageJsonValue.StringValue)?.value
                ?: thirdpartyFailure(
                    ThirdPartyImportErrorCode.INVALID_FIELD,
                    "$context.$key[$index]",
                    "export names must be strings"
                )
            requireBounded(text, "$context.$key[$index]")
            text
        }
    }

    fun optionalVersion(
        obj: ToolPackageJsonValue.ObjectValue,
        key: String,
        context: String
    ): String? {
        val value = optionalString(obj, key, context) ?: return null
        if (ToolPackageVersion.parseOrNull(value) == null) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "$context.$key",
                "version bounds must be major.minor.patch semantic versions"
            )
        }
        return value
    }

    fun asObject(value: ToolPackageJsonValue, field: String): ToolPackageJsonValue.ObjectValue =
        value as? ToolPackageJsonValue.ObjectValue
            ?: thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, field, "expected an object")

    fun requireBounded(value: String, field: String) {
        if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > THIRD_PARTY_MAX_TEXT_BYTES) {
            thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, field, "text field is outside its budget")
        }
    }

    fun requireBoundedCount(items: List<ToolPackageJsonValue>, max: Int, field: String) {
        if (items.size > max) {
            thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, field, "array exceeds its item budget")
        }
    }
}

internal fun normalizeThirdPartyPackageId(raw: String): String? {
    val mapped = buildString(raw.length) {
        raw.lowercase().forEach { character ->
            append(if (character in 'a'..'z' || character in '0'..'9' || character == '-') {
                character
            } else {
                '-'
            })
        }
    }
    val collapsed = mapped.replace(Regex("-+"), "-").trim('-')
    return collapsed.takeIf { AgentToolPackageToolRef.isValidPackageId(it) }
}

internal fun normalizeThirdPartyResourcePath(raw: String, isDirectory: Boolean, field: String): String = try {
    if (isDirectory) {
        AgentToolPackagePathPolicy.normalizeDirectory(raw)
    } else {
        AgentToolPackagePathPolicy.normalizeFile(raw)
    }
} catch (_: AgentToolPackageException) {
    thirdpartyFailure(
        ThirdPartyImportErrorCode.INVALID_PATH,
        field,
        "resource path must be a normalised relative package path"
    )
}

internal fun isExecutableThirdPartyResource(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in THIRD_PARTY_EXECUTABLE_SUFFIXES

internal fun safeManifestKey(key: String): String = buildString {
    for (character in key.take(64)) {
        append(if (character.isISOControl() || character == '\'' || character == '"') '?' else character)
    }
}

internal const val THIRD_PARTY_SUPPORTED_SCHEMA_VERSION = 1L
internal const val THIRD_PARTY_DEFAULT_VERSION = "0.0.0"
internal const val THIRD_PARTY_DEFAULT_API_VERSION = "1.0.0"
internal const val THIRD_PARTY_MAX_REQUIRES = 16
internal const val THIRD_PARTY_MAX_SUBPACKAGES = 32
internal const val THIRD_PARTY_MAX_RESOURCES = 192
internal const val THIRD_PARTY_MAX_WASM_MODULES = 16
internal const val THIRD_PARTY_MAX_TEMPLATES = 16
internal const val THIRD_PARTY_MAX_EXPORTS = 64
internal const val THIRD_PARTY_MAX_AUTHORS = 16
internal const val THIRD_PARTY_MAX_LOCALIZED_BYTES = 4096
internal const val THIRD_PARTY_MAX_TEXT_BYTES = 512

internal val THIRD_PARTY_SUPPORTED_API_VERSIONS = setOf("1.0.0", "1.0.1")
internal val THIRD_PARTY_API_VERSION = Regex("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")
internal val THIRD_PARTY_TOOLPKG_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
internal val THIRD_PARTY_ENTITY_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
internal val THIRD_PARTY_LOCALE_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")

internal val THIRD_PARTY_ROOT_FIELDS = setOf(
    "schema_version", "toolpkg_id", "version", "api_version", "requires", "author",
    "main", "display_name", "description", "logo", "subpackages", "resources",
    "wasm_modules", "workflow_templates", "workspace_templates"
)
internal val THIRD_PARTY_DEPENDENCY_FIELDS = setOf("id", "description", "min_version", "max_version")
internal val THIRD_PARTY_SUBPACKAGE_FIELDS =
    setOf("id", "entry", "enabled_by_default", "display_name", "description")
internal val THIRD_PARTY_RESOURCE_FIELDS = setOf("key", "path", "mime")
internal val THIRD_PARTY_WASM_FIELDS = setOf("id", "path", "exports", "source_language", "abi")
internal val THIRD_PARTY_WORKFLOW_TEMPLATE_FIELDS =
    setOf("id", "display_name", "description", "resource_key")
internal val THIRD_PARTY_WORKSPACE_TEMPLATE_FIELDS =
    setOf("id", "display_name", "description", "resource_key", "project_type")
internal val THIRD_PARTY_EXECUTABLE_SUFFIXES = setOf(
    "js", "mjs", "cjs", "ts", "wasm", "dex", "jar", "so", "apk", "class", "aar",
    "elf", "exe", "dll", "bin"
)
internal val THIRD_PARTY_BINARY_SUFFIXES = setOf(
    "wasm", "dex", "jar", "so", "apk", "class", "aar", "elf", "exe", "dll", "bin"
)

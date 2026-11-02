package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJsonValue
import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageVersion

/** Identity and executable-surface fields of one third-party manifest. */
internal data class ThirdPartyManifestHeader(
    val toolPkgId: String,
    val normalizedPackageId: String,
    val version: String,
    val apiVersion: String,
    val displayName: String,
    val description: String,
    val mainEntry: String,
    val subpackages: List<ThirdPartySubpackageDescriptor>,
    val dependencies: List<ThirdPartyDependencyDescriptor>
)

/** Reads schema/identity/version/API/main/subpackage declarations and appends their report rows. */
internal class ThirdPartyManifestHeaderReader(private val session: ThirdPartyManifestSession) {

    fun read(): ThirdPartyManifestHeader {
        session.rejectUnknownFields(session.root.fields, THIRD_PARTY_ROOT_FIELDS, "manifest")
        readSchema()
        val (toolPkgId, normalizedPackageId) = readIdentity()
        val version = readVersion()
        val apiVersion = readApiVersion()
        val displayName = session.readLocalized("display_name").ifBlank { toolPkgId }
        val description = session.readLocalized("description")
        readAuthor()
        val dependencies = readDependencies(toolPkgId)
        val mainEntry = readMain()
        val subpackages = readSubpackages()
        return ThirdPartyManifestHeader(
            toolPkgId = toolPkgId,
            normalizedPackageId = normalizedPackageId,
            version = version,
            apiVersion = apiVersion,
            displayName = displayName,
            description = description,
            mainEntry = mainEntry,
            subpackages = subpackages,
            dependencies = dependencies
        )
    }

    private fun readSchema() {
        val value = session.root.fields["schema_version"]
            ?: thirdpartyFailure(
                ThirdPartyImportErrorCode.MISSING_FIELD,
                "manifest.schema_version",
                "third-party manifest is missing schema_version"
            )
        val number = (value as? ToolPackageJsonValue.IntegerValue)?.value
            ?: thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "manifest.schema_version",
                "schema_version must be an integer"
            )
        if (number != THIRD_PARTY_SUPPORTED_SCHEMA_VERSION) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "manifest.schema_version",
                "only third-party schema_version $THIRD_PARTY_SUPPORTED_SCHEMA_VERSION is supported"
            )
        }
        session.add(
            ThirdPartyCompatibilityCategory.FORMAT,
            "schema_version=1",
            "manifest.schema_version",
            ThirdPartyCompatibilityStatus.SUPPORTED,
            "third-party schema_version 1 is understood by the import adapter."
        )
    }

    private fun readIdentity(): Pair<String, String> {
        val raw = session.requireString("toolpkg_id", "manifest")
        if (!THIRD_PARTY_TOOLPKG_ID.matches(raw)) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_ID,
                "manifest.toolpkg_id",
                "toolpkg_id must be 1..128 characters of letters, digits, '.', '_' or '-'"
            )
        }
        val normalized = normalizeThirdPartyPackageId(raw)
            ?: thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_ID,
                "manifest.toolpkg_id",
                "toolpkg_id cannot be normalised to an MCA package id"
            )
        val translated = normalized != raw
        session.add(
            ThirdPartyCompatibilityCategory.IDENTITY,
            "toolpkg_id",
            "manifest.toolpkg_id",
            if (translated) ThirdPartyCompatibilityStatus.TRANSLATED else ThirdPartyCompatibilityStatus.SUPPORTED,
            if (translated) {
                "third-party id is normalised to an MCA-safe package id; the original id stays in the descriptor."
            } else {
                "The third-party id is already a valid MCA package id."
            },
            if (translated) normalized else null
        )
        return raw to normalized
    }

    private fun readVersion(): String {
        val declared = session.optionalString("version", "manifest")
        val version = declared ?: THIRD_PARTY_DEFAULT_VERSION
        if (ToolPackageVersion.parseOrNull(version) == null) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "manifest.version",
                "version must be a major.minor.patch semantic version"
            )
        }
        session.add(
            ThirdPartyCompatibilityCategory.VERSION,
            "version",
            "manifest.version",
            if (declared == null) ThirdPartyCompatibilityStatus.TRANSLATED else ThirdPartyCompatibilityStatus.SUPPORTED,
            if (declared == null) {
                "third-party allows an omitted version; the import preview records $THIRD_PARTY_DEFAULT_VERSION."
            } else {
                "The declared semantic version is kept unchanged."
            }
        )
        return version
    }

    private fun readApiVersion(): String {
        val apiVersion = session.optionalString("api_version", "manifest") ?: THIRD_PARTY_DEFAULT_API_VERSION
        if (!THIRD_PARTY_API_VERSION.matches(apiVersion) || apiVersion !in THIRD_PARTY_SUPPORTED_API_VERSIONS) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.UNSUPPORTED_API_VERSION,
                "manifest.api_version",
                "the manifest requires a third-party ToolPkg API version this build does not understand"
            )
        }
        session.add(
            ThirdPartyCompatibilityCategory.API,
            "api_version",
            "manifest.api_version",
            ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
            "MCA has no third-party ToolPkg API host layer; the import is preview-only.",
            "A narrowed third-party host-API compatibility layer must be wired before any entry can run."
        )
        return apiVersion
    }

    private fun readAuthor() {
        when (val value = session.root.fields["author"]) {
            null -> Unit
            is ToolPackageJsonValue.StringValue -> session.requireBounded(value.value, "manifest.author")
            is ToolPackageJsonValue.ArrayValue -> {
                if (value.items.size > THIRD_PARTY_MAX_AUTHORS) {
                    thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "manifest.author", "too many authors")
                }
                value.items.forEachIndexed { index, item ->
                    val author = (item as? ToolPackageJsonValue.StringValue)?.value
                        ?: thirdpartyFailure(
                            ThirdPartyImportErrorCode.INVALID_FIELD,
                            "manifest.author[$index]",
                            "author entries must be strings"
                        )
                    session.requireBounded(author, "manifest.author[$index]")
                }
            }
            else -> thirdpartyFailure(
                ThirdPartyImportErrorCode.INVALID_FIELD,
                "manifest.author",
                "author must be a string or an array of strings"
            )
        }
    }

    private fun readDependencies(toolPkgId: String): List<ThirdPartyDependencyDescriptor> {
        val array = session.optionalArray("requires", "manifest") ?: return emptyList()
        session.requireBoundedCount(array.items, THIRD_PARTY_MAX_REQUIRES, "manifest.requires")
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val field = "manifest.requires[$index]"
            val obj = session.asObject(item, field)
            session.rejectUnknownFields(obj.fields, THIRD_PARTY_DEPENDENCY_FIELDS, field)
            val id = session.requireString(obj, "id", field)
            if (!THIRD_PARTY_TOOLPKG_ID.matches(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_ID, "$field.id", "dependency id is invalid")
            }
            if (id == toolPkgId) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$field.id", "a package cannot require itself")
            }
            if (!seen.add(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$field.id", "dependency id is repeated")
            }
            session.requireString(obj, "description", field)
            val minVersion = session.optionalVersion(obj, "min_version", field)
            val maxVersion = session.optionalVersion(obj, "max_version", field)
            session.add(
                ThirdPartyCompatibilityCategory.DEPENDENCY,
                id,
                field,
                ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
                "MCA has no third-party dependency-resolution/load-order runtime; the dependency is not loaded.",
                "Import the dependency as an explicit package and resolve it through an MCA-owned graph."
            )
            ThirdPartyDependencyDescriptor(id, minVersion, maxVersion)
        }
    }

    private fun readMain(): String {
        val raw = session.requireString("main", "manifest")
        val entry = ToolPackageScriptPaths.normalize(raw)
            ?: thirdpartyFailure(
                ThirdPartyImportErrorCode.UNSUPPORTED_ENTRY,
                "manifest.main",
                "main must be a normalised relative .js path without '..' or absolute roots"
            )
        if (!session.listing.hasFile(entry)) {
            thirdpartyFailure(
                ThirdPartyImportErrorCode.ENTRY_MISSING,
                "manifest.main",
                "the declared main entry is not present in the package listing"
            )
        }
        session.add(
            ThirdPartyCompatibilityCategory.ENTRY,
            "main",
            "manifest.main",
            ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
            "MCA has a Rhino script runtime but no third-party main-context registerToolPkg/hook/IPC layer; main is not executed.",
            "Translate main registration to an MCA package definition or provide a narrowed third-party main context."
        )
        return entry
    }

    private fun readSubpackages(): List<ThirdPartySubpackageDescriptor> {
        val array = session.optionalArray("subpackages", "manifest") ?: return emptyList()
        session.requireBoundedCount(array.items, THIRD_PARTY_MAX_SUBPACKAGES, "manifest.subpackages")
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val field = "manifest.subpackages[$index]"
            val obj = session.asObject(item, field)
            session.rejectUnknownFields(obj.fields, THIRD_PARTY_SUBPACKAGE_FIELDS, field)
            val id = session.requireString(obj, "id", field)
            if (!THIRD_PARTY_ENTITY_ID.matches(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_ID, "$field.id", "subpackage id is invalid")
            }
            if (!seen.add(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$field.id", "subpackage id is repeated")
            }
            val rawEntry = session.requireString(obj, "entry", field)
            val entry = ToolPackageScriptPaths.normalize(rawEntry)
                ?: thirdpartyFailure(
                    ThirdPartyImportErrorCode.UNSUPPORTED_ENTRY,
                    "$field.entry",
                    "subpackage entry must be a normalised relative .js path"
                )
            if (!session.listing.hasFile(entry)) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.ENTRY_MISSING,
                    "$field.entry",
                    "the declared subpackage entry is not present in the package listing"
                )
            }
            val enabled = session.optionalBoolean(obj, "enabled_by_default", field) ?: false
            obj.fields["display_name"]?.let { session.validateLocalized(it, "$field.display_name") }
            obj.fields["description"]?.let { session.validateLocalized(it, "$field.description") }
            session.add(
                ThirdPartyCompatibilityCategory.ENTRY,
                id,
                field,
                ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
                "Subpackages run in the third-party sandbox context and register <subpackage_id>:<tool_name> tools; MCA does not host that context.",
                "A third-party sandbox adapter must translate METADATA tools before this entry can run."
            )
            ThirdPartySubpackageDescriptor(id, entry, enabled)
        }
    }
}

/** Classifies the four documented third-party execution contexts; unknown names are typed rejections. */
internal class ThirdPartyManifestContextReader(private val session: ThirdPartyManifestSession) {

    fun read(subpackages: List<ThirdPartySubpackageDescriptor>): List<ThirdPartyContextCompatibility> {
        val explicit = HashSet<ThirdPartyExecutionContext>()
        session.declaredContexts.forEachIndexed { index, declared ->
            val context = ThirdPartyExecutionContext.fromWireName(declared.name)
                ?: thirdpartyFailure(
                    ThirdPartyImportErrorCode.UNKNOWN_CONTEXT,
                    declared.fieldPath.ifBlank { "contexts[$index]" },
                    "unknown third-party execution context"
                )
            if (!explicit.add(context)) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.INVALID_FIELD,
                    declared.fieldPath.ifBlank { "contexts[$index]" },
                    "the same context is declared twice"
                )
            }
        }
        val uiDeclared = ThirdPartyExecutionContext.UI in explicit || session.listing.files.keys.any {
            it.endsWith(".ui.js") || (it.startsWith("ui/") && it.endsWith(".js"))
        }
        val providerDeclared = ThirdPartyExecutionContext.PROVIDER in explicit || session.listing.files.keys.any {
            it.endsWith(".js") && (it.startsWith("provider/") || it.endsWith(".provider.js"))
        }
        val sandboxDeclared = ThirdPartyExecutionContext.SANDBOX in explicit || subpackages.isNotEmpty()
        return listOf(
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.MAIN,
                declared = true,
                status = ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
                reason = "MCA has no third-party main registration/hook/IPC context; manifest.main is never executed.",
                alternative = "Provide a narrowed main-context compatibility layer or translate registration."
            ),
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.UI,
                declared = uiDeclared,
                status = ThirdPartyCompatibilityStatus.UNSUPPORTED,
                reason = "MCA has no Compose-DSL UI module runtime and will not create a bridged WebView for package UI.",
                alternative = "Render a structured form derived from a reviewed schema instead of executing UI scripts."
            ),
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.SANDBOX,
                declared = sandboxDeclared,
                status = ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
                reason = "MCA does not host the third-party sandbox API or <subpackage_id>:<tool_name> catalog.",
                alternative = "Translate METADATA tools to MCA package tools before enabling the context."
            ),
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.PROVIDER,
                declared = providerDeclared,
                status = ThirdPartyCompatibilityStatus.UNSUPPORTED,
                reason = "MCA has no third-party AI-provider registration or credential bridge; provider code is never loaded.",
                alternative = "Use the MCA model catalog; do not register package-owned providers."
            )
        )
    }
}

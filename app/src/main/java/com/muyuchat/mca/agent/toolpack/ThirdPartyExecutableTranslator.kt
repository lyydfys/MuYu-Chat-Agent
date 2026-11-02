package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import com.muyuchat.mca.agent.tools.AgentToolJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * One third-party host-call symbol and its exact MCA equivalent.
 *
 * The mapping is code-owned and deliberately tiny. A translated script may only name these
 * symbols; everything else is a typed translation refusal, not a silent drop. [semantics] is
 * surfaced in the compatibility report because the MCA host surface is stricter than a third-party's
 * (bounded reads, artifacts-only writes, one per-call approval per write).
 */
data class ThirdPartyHostApiMapping(
    val thirdpartySymbol: String,
    val mcaHostApi: String,
    val access: ToolPackageHostAccess,
    val semantics: String
)

/** Stable translation refusal vocabulary; the wire code is content-free and branchable. */
enum class ThirdPartyTranslationErrorCode(val wireCode: String) {
    PREVIEW_REJECTED("THIRD_PARTY_TRANSLATION_PREVIEW_REJECTED"),
    ARCHIVE_INVALID("THIRD_PARTY_TRANSLATION_ARCHIVE_INVALID"),
    WASM_UNSUPPORTED("THIRD_PARTY_TRANSLATION_WASM_UNSUPPORTED"),
    TEMPLATE_UNSUPPORTED("THIRD_PARTY_TRANSLATION_TEMPLATE_UNSUPPORTED"),
    DEPENDENCY_UNSUPPORTED("THIRD_PARTY_TRANSLATION_DEPENDENCY_UNSUPPORTED"),
    RESOURCE_UNSUPPORTED("THIRD_PARTY_TRANSLATION_RESOURCE_UNSUPPORTED"),
    UI_UNSUPPORTED("THIRD_PARTY_TRANSLATION_UI_UNSUPPORTED"),
    PROVIDER_UNSUPPORTED("THIRD_PARTY_TRANSLATION_PROVIDER_UNSUPPORTED"),
    HOOK_UNSUPPORTED("THIRD_PARTY_TRANSLATION_HOOK_UNSUPPORTED"),
    ENTRY_MISSING("THIRD_PARTY_TRANSLATION_ENTRY_MISSING"),
    SCRIPT_PATH_UNSUPPORTED("THIRD_PARTY_TRANSLATION_SCRIPT_PATH_UNSUPPORTED"),
    NO_TOOLS("THIRD_PARTY_TRANSLATION_NO_TOOLS"),
    UNSUPPORTED_SYMBOL("THIRD_PARTY_TRANSLATION_UNSUPPORTED_SYMBOL"),
    FORBIDDEN_SYMBOL("THIRD_PARTY_TRANSLATION_FORBIDDEN_SYMBOL"),
    REQUIRE_INVALID("THIRD_PARTY_TRANSLATION_REQUIRE_INVALID"),
    HOST_API_UNMAPPED("THIRD_PARTY_TRANSLATION_HOST_API_UNMAPPED"),
    INTERNAL_INVARIANT("THIRD_PARTY_TRANSLATION_INTERNAL_INVARIANT")
}

/**
 * Result of translating one parsed third-party ToolPkg into an MCA executable-v2 package.
 *
 * This object is inert data: it carries no executor and performs no store write. A caller must
 * still install it through [ToolPackageInstallStore]/[ToolPackageLifecycleService] and that
 * lifecycle must independently verify/enable it.
 */
sealed interface ThirdPartyTranslationResult {
    data class Translated(val translated: ThirdPartyTranslatedExecutablePackage) : ThirdPartyTranslationResult

    data class Rejected(
        val code: ThirdPartyTranslationErrorCode,
        /** Exact manifest/listing field or package script path, never a host absolute path. */
        val fieldPath: String,
        /** The unsupported third-party symbol/context, when one applies. */
        val symbol: String? = null,
        val safeMessage: String
    ) : ThirdPartyTranslationResult
}

/**
 * One real translated MCA executable-v2 package.
 *
 * [manifestJson] is the canonical `mca-tool-package.json` text; [scripts] are the original
 * relative paths with the translation prelude prepended (one `Tools.Files` shim per module). The
 * package id is derived from the third-party id with a `thirdparty-` prefix so it can never collide with a
 * hand-authored MCA package id by accident.
 */
data class ThirdPartyTranslatedExecutablePackage(
    val thirdpartyToolPkgId: String,
    val mcaPackageId: String,
    val manifestJson: String,
    val scripts: Map<String, String>,
    val toolNames: List<String>,
    val mappedHostApis: List<String>,
    val report: ThirdPartyCompatibilityReport
)

/**
 * Translates a parsed third-party ToolPkg into an MCA executable-v2 package under a fixed, code-owned
 * compatibility contract.
 *
 * ## Supported subset (the only contract this class claims)
 *
 * - One enabled third-party subpackage becomes one MCA tool, or (when no subpackage is enabled) the
 *   manifest `main` script becomes one MCA tool.
 * - Each translated entry must be a CommonJS module that exports `main(input)`; `require` may only
 *   use relative package paths (the Rhino loader enforces this too).
 * - The only third-party host symbols translated are `Tools.Files.read/list/search/write`; they map to
 *   the existing MCA v2 host APIs (`workspace.read_text/list/search_text`, `artifact.write_text`).
 *   Every write still goes through the real per-call [ToolPackageHostApprovalPort] in the bridge.
 * - The MCA argument envelope is a bounded flat object (`path/outPath/content/query`); packages
 *   that need other argument names are not claimed as supported.
 *
 * ## Always rejected (never faked)
 *
 * WASM modules, workflow/workspace templates, third-party dependencies, declared resources (they cannot
 * be installed by the v2 JS-only archive policy), `ui`/`provider` contexts, and any script token
 * outside the mapping allowlist (`Java`, `Packages`, `Android`, `ToolPkg`, `registerTool*`,
 * network/browser/terminal/UI/memory/workflow symbols, non-relative `require`). A rejection names
 * the exact symbol/path and produces no manifest and no scripts; nothing is installed.
 *
 * `main`-context registration is **not** executed. When subpackages are translated, the third-party
 * `main` registration script is deliberately not carried at all; the report says so.
 */
class ThirdPartyExecutableTranslator {

    fun translate(
        descriptor: ThirdPartyToolPackageDescriptor,
        previewReport: ThirdPartyCompatibilityReport,
        scripts: Map<String, String>
    ): ThirdPartyTranslationResult {
        declarationRefusal(descriptor, previewReport)?.let { return it }

        val enabledSubpackages = descriptor.subpackages.filter { it.enabledByDefault }
        if (enabledSubpackages.isNotEmpty()) {
            return translateSubpackages(descriptor, previewReport, scripts, enabledSubpackages)
        }
        return translateMainEntry(descriptor, previewReport, scripts)
    }

    /** Declared surfaces MCA cannot host at all; refuse before reading any script. */
    private fun declarationRefusal(
        descriptor: ThirdPartyToolPackageDescriptor,
        report: ThirdPartyCompatibilityReport
    ): ThirdPartyTranslationResult.Rejected? {
        descriptor.wasmModules.firstOrNull()?.let { module ->
            return rejected(
                ThirdPartyTranslationErrorCode.WASM_UNSUPPORTED,
                "manifest.wasm_modules[0].path",
                module.path,
                "third-party WASM modules are not supported: MCA has no WAMR/WASM runtime."
            )
        }
        descriptor.workflowTemplates.firstOrNull()?.let { template ->
            return rejected(
                ThirdPartyTranslationErrorCode.TEMPLATE_UNSUPPORTED,
                "manifest.workflow_templates[0].id",
                template.id,
                "third-party workflow templates are not supported: MCA has no template import port."
            )
        }
        descriptor.workspaceTemplates.firstOrNull()?.let { template ->
            return rejected(
                ThirdPartyTranslationErrorCode.TEMPLATE_UNSUPPORTED,
                "manifest.workspace_templates[0].id",
                template.id,
                "third-party workspace templates are not supported: MCA has no template import port."
            )
        }
        descriptor.dependencies.firstOrNull()?.let { dependency ->
            return rejected(
                ThirdPartyTranslationErrorCode.DEPENDENCY_UNSUPPORTED,
                "manifest.requires[0].id",
                dependency.id,
                "third-party dependencies/load order are not supported; MCA dependencies are a different format."
            )
        }
        descriptor.resources.firstOrNull()?.let { resource ->
            return rejected(
                ThirdPartyTranslationErrorCode.RESOURCE_UNSUPPORTED,
                "manifest.resources[0].key",
                resource.key,
                "third-party resources cannot be installed: the MCA v2 archive only carries JavaScript."
            )
        }
        if (report.context(ThirdPartyExecutionContext.UI).declared) {
            return rejected(
                ThirdPartyTranslationErrorCode.UI_UNSUPPORTED,
                "contexts.ui",
                "ui",
                "The third-party UI context is not supported: no bridged WebView or Compose-DSL runtime."
            )
        }
        if (report.context(ThirdPartyExecutionContext.PROVIDER).declared) {
            return rejected(
                ThirdPartyTranslationErrorCode.PROVIDER_UNSUPPORTED,
                "contexts.provider",
                "provider",
                "The third-party provider context is not supported: MCA owns its model/credential catalog."
            )
        }
        return null
    }

    private fun translateSubpackages(
        descriptor: ThirdPartyToolPackageDescriptor,
        previewReport: ThirdPartyCompatibilityReport,
        scripts: Map<String, String>,
        subpackages: List<ThirdPartySubpackageDescriptor>
    ): ThirdPartyTranslationResult {
        if (subpackages.size > ToolPackageLimits.MAX_TOOLS) {
            return rejected(
                ThirdPartyTranslationErrorCode.NO_TOOLS,
                "manifest.subpackages",
                null,
                "The package declares more enabled subpackages than one MCA package can export."
            )
        }
        // The registration script is never executed, so it is not carried into the MCA package.
        val carried = LinkedHashMap<String, String>()
        for ((path, source) in scripts) {
            if (path == descriptor.mainEntry) continue
            validatedPath(path)?.let { return it }
            carried[path] = source
        }
        val targets = ArrayList<TranslationTarget>()
        for ((path, source) in carried) {
            scanScript(path, source)?.let { return it }
        }
        for (subpackage in subpackages) {
            val source = carried[subpackage.entry]
                ?: return rejected(
                    ThirdPartyTranslationErrorCode.ENTRY_MISSING,
                    "manifest.subpackages[].entry",
                    subpackage.entry,
                    "The enabled subpackage entry is not present in the supplied scripts."
                )
            val name = toolName(subpackage.id, targets.map { it.toolName }.toSet())
            targets += TranslationTarget(
                toolName = name,
                displayName = subpackage.id,
                entry = subpackage.entry,
                hostCalls = hostCalls(source)
            )
        }
        return buildTranslated(
            descriptor = descriptor,
            previewReport = previewReport,
            carried = carried,
            targets = targets,
            subpackageContext = true
        )
    }

    private fun translateMainEntry(
        descriptor: ThirdPartyToolPackageDescriptor,
        previewReport: ThirdPartyCompatibilityReport,
        scripts: Map<String, String>
    ): ThirdPartyTranslationResult {
        val carried = LinkedHashMap<String, String>()
        for ((path, source) in scripts) {
            validatedPath(path)?.let { return it }
            carried[path] = source
        }
        val mainSource = carried[descriptor.mainEntry]
            ?: return rejected(
                ThirdPartyTranslationErrorCode.ENTRY_MISSING,
                "manifest.main",
                descriptor.mainEntry,
                "The third-party main entry is not present in the supplied scripts."
            )
        for ((path, source) in carried) {
            scanScript(path, source)?.let { return it }
        }
        val name = toolName("main", emptySet())
        val targets = listOf(
            TranslationTarget(
                toolName = name,
                displayName = descriptor.displayName,
                entry = descriptor.mainEntry,
                hostCalls = hostCalls(mainSource)
            )
        )
        return buildTranslated(
            descriptor = descriptor,
            previewReport = previewReport,
            carried = carried,
            targets = targets,
            subpackageContext = false
        )
    }

    private fun buildTranslated(
        descriptor: ThirdPartyToolPackageDescriptor,
        previewReport: ThirdPartyCompatibilityReport,
        carried: Map<String, String>,
        targets: List<TranslationTarget>,
        subpackageContext: Boolean
    ): ThirdPartyTranslationResult {
        if (targets.isEmpty()) {
            return rejected(
                ThirdPartyTranslationErrorCode.NO_TOOLS,
                "manifest",
                null,
                "The package does not declare a translatable entry."
            )
        }
        val hostApis = targets.flatMap { it.hostCalls }.toSortedSet().toList()
        for (hostApi in hostApis) {
            if (ToolPackageHostApis.find(hostApi) == null) {
                return rejected(
                    ThirdPartyTranslationErrorCode.HOST_API_UNMAPPED,
                    "translated.hostApis",
                    hostApi,
                    "A translated host call has no code-owned MCA v2 host API."
                )
            }
        }
        val mcaPackageId = mcaPackageId(descriptor)
        val manifest = buildManifest(descriptor, mcaPackageId, targets, hostApis)
        val manifestJson = AgentToolJson.canonical(manifest)
        val translatedScripts = prefixScripts(carried)
        // Fail closed if the generated manifest is not an admissible MCA v2 package; the caller
        // must never receive a half-translated object that the store will only reject later.
        try {
            ExecutableToolPackageLoader().load(manifestJson, translatedScripts)
        } catch (failure: ToolPackageException) {
            return rejected(
                ThirdPartyTranslationErrorCode.INTERNAL_INVARIANT,
                "translated.manifest",
                null,
                "The generated MCA package manifest is not admissible (${failure.error.code.agentCode})."
            )
        }
        val report = translatedReport(descriptor, previewReport, targets, hostApis, subpackageContext)
        if (!report.runnable) {
            return rejected(
                ThirdPartyTranslationErrorCode.INTERNAL_INVARIANT,
                "translated.report",
                null,
                "The translated compatibility report is not runnable."
            )
        }
        return ThirdPartyTranslationResult.Translated(
            ThirdPartyTranslatedExecutablePackage(
                thirdpartyToolPkgId = descriptor.toolPkgId,
                mcaPackageId = mcaPackageId,
                manifestJson = manifestJson,
                scripts = translatedScripts,
                toolNames = targets.map { it.toolName },
                mappedHostApis = hostApis,
                report = report
            )
        )
    }

    private fun buildManifest(
        descriptor: ThirdPartyToolPackageDescriptor,
        mcaPackageId: String,
        targets: List<TranslationTarget>,
        hostApis: List<String>
    ): JSONObject {
        val tools = JSONArray()
        for (target in targets) {
            tools.put(
                JSONObject()
                    .put("name", target.toolName)
                    .put("displayName", boundedChars(target.displayName, 128).ifBlank { target.toolName })
                    .put(
                        "description",
                        boundedUtf8(
                            "Translated third-party entry '${target.displayName}'. Accepts path/outPath/" +
                                "content/query; host calls are limited to the translated MCA file APIs.",
                            ToolPackageLimits.MAX_TOOL_DESCRIPTION_BYTES
                        )
                    )
                    .put("entry", target.entry)
                    .put("inputSchema", GENERIC_INPUT_SCHEMA)
                    .put("timeoutMillis", 30_000L)
            )
        }
        return JSONObject()
            .put("schemaVersion", ToolPackageLimits.FORMAT_VERSION)
            .put("packageId", mcaPackageId)
            .put("packageVersion", descriptor.version)
            .put("hostApiVersion", ToolPackageLimits.HOST_API_VERSION)
            .put("displayName", boundedChars(descriptor.displayName.ifBlank { descriptor.toolPkgId }, 128))
            .put(
                "description",
                boundedUtf8(
                    "Translated third-party ToolPkg '${descriptor.toolPkgId}' v${descriptor.version}. " +
                        descriptor.description,
                    ToolPackageLimits.MAX_DESCRIPTION_BYTES
                )
            )
            .put("entry", targets.first().entry)
            .put("hostApis", JSONArray(hostApis))
            .put("tools", tools)
    }

    private fun translatedReport(
        descriptor: ThirdPartyToolPackageDescriptor,
        previewReport: ThirdPartyCompatibilityReport,
        targets: List<TranslationTarget>,
        hostApis: List<String>,
        subpackageContext: Boolean
    ): ThirdPartyCompatibilityReport {
        val findings = ArrayList<ThirdPartyCompatibilityFinding>()
        findings += finding(
            ThirdPartyCompatibilityCategory.FORMAT,
            "schema_version=1",
            "manifest.schema_version",
            ThirdPartyCompatibilityStatus.SUPPORTED,
            "third-party schema_version 1 is translated into an MCA executable-v2 manifest."
        )
        findings += finding(
            ThirdPartyCompatibilityCategory.IDENTITY,
            descriptor.toolPkgId,
            "manifest.toolpkg_id",
            ThirdPartyCompatibilityStatus.TRANSLATED,
            "third-party id '${descriptor.toolPkgId}' becomes MCA package '${mcaPackageId(descriptor)}'."
        )
        findings += finding(
            ThirdPartyCompatibilityCategory.VERSION,
            descriptor.version,
            "manifest.version",
            ThirdPartyCompatibilityStatus.TRANSLATED,
            "The third-party semantic version is reused as the MCA package version."
        )
        for (target in targets) {
            findings += finding(
                ThirdPartyCompatibilityCategory.ENTRY,
                target.toolName,
                "translated.tools[${target.toolName}]",
                ThirdPartyCompatibilityStatus.TRANSLATED,
                "third-party entry '${target.displayName}' becomes one MCA tool exporting main(input)."
            )
        }
        for (hostApi in hostApis) {
            val mapping = MAPPINGS.values.first { it.mcaHostApi == hostApi }
            findings += finding(
                ThirdPartyCompatibilityCategory.API,
                mapping.thirdpartySymbol,
                "translated.hostApis[$hostApi]",
                ThirdPartyCompatibilityStatus.TRANSLATED,
                "Mapped to '$hostApi'. ${mapping.semantics}"
            )
        }
        findings += finding(
            ThirdPartyCompatibilityCategory.CONTEXT,
            "main",
            "contexts.main",
            ThirdPartyCompatibilityStatus.TRANSLATED,
            "The third-party registration/main context is not executed; only CommonJS tools exporting " +
                "main(input) are run, in the MCA script runtime."
        )
        if (subpackageContext) {
            findings += finding(
                ThirdPartyCompatibilityCategory.CONTEXT,
                "sandbox",
                "contexts.sandbox",
                ThirdPartyCompatibilityStatus.TRANSLATED,
                "Enabled subpackages become MCA tools; there is no third-party sandbox API/session, no " +
                    "<subpackage_id>:<tool_name> catalog and no cross-package state."
            )
        }
        findings += finding(
            ThirdPartyCompatibilityCategory.API,
            "argument-envelope",
            "translated.inputSchema",
            ThirdPartyCompatibilityStatus.TRANSLATED,
            "Translated tools share a bounded flat argument envelope (path/outPath/content/query); " +
                "scripts requiring other argument names are not claimed as supported."
        )
        // Keep undeclared unsupported contexts visible in the report without making them blocking.
        val contexts = listOf(
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.MAIN,
                declared = true,
                status = ThirdPartyCompatibilityStatus.TRANSLATED,
                reason = "main is not executed; only exported CommonJS main(input) entries are translated."
            ),
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.UI,
                declared = false,
                status = ThirdPartyCompatibilityStatus.UNSUPPORTED,
                reason = previewReport.context(ThirdPartyExecutionContext.UI).reason
            ),
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.SANDBOX,
                declared = subpackageContext,
                status = if (subpackageContext) {
                    ThirdPartyCompatibilityStatus.TRANSLATED
                } else {
                    ThirdPartyCompatibilityStatus.UNSUPPORTED
                },
                reason = if (subpackageContext) {
                    "Enabled subpackages are translated to MCA tools without the third-party session API."
                } else {
                    "This package declares no enabled subpackage."
                }
            ),
            ThirdPartyContextCompatibility(
                context = ThirdPartyExecutionContext.PROVIDER,
                declared = false,
                status = ThirdPartyCompatibilityStatus.UNSUPPORTED,
                reason = previewReport.context(ThirdPartyExecutionContext.PROVIDER).reason
            )
        )
        return ThirdPartyCompatibilityReport(findings, contexts)
    }

    private fun scanScript(path: String, source: String): ThirdPartyTranslationResult.Rejected? {
        for (forbidden in FORBIDDEN_SYMBOLS) {
            if (forbidden.regex.containsMatchIn(source)) {
                return rejected(
                    ThirdPartyTranslationErrorCode.FORBIDDEN_SYMBOL,
                    path,
                    forbidden.symbol,
                    "The script uses a host symbol MCA will not translate (${forbidden.symbol})."
                )
            }
        }
        for (match in REQUIRE_CALL.findAll(source)) {
            val specifier = match.groupValues[1]
            if (!specifier.startsWith("./") && !specifier.startsWith("../")) {
                return rejected(
                    ThirdPartyTranslationErrorCode.REQUIRE_INVALID,
                    path,
                    specifier.take(128),
                    "Only relative package modules can be required; absolute/built-in modules are not translated."
                )
            }
        }
        for (match in TOOLS_SYMBOL.findAll(source)) {
            val symbol = match.value.replace(Regex("\\s+"), "")
            if (symbol !in MAPPINGS) {
                return rejected(
                    ThirdPartyTranslationErrorCode.UNSUPPORTED_SYMBOL,
                    path,
                    symbol.take(128),
                    "The third-party host symbol is outside the translated allowlist."
                )
            }
        }
        if (HOOK_SYMBOL.containsMatchIn(source)) {
            return rejected(
                ThirdPartyTranslationErrorCode.HOOK_UNSUPPORTED,
                path,
                "hook",
                "third-party hook registration is not translated."
            )
        }
        return null
    }

    private fun hostCalls(source: String): List<String> {
        val calls = LinkedHashSet<String>()
        for (match in TOOLS_SYMBOL.findAll(source)) {
            val symbol = match.value.replace(Regex("\\s+"), "")
            MAPPINGS[symbol]?.let { calls += it.mcaHostApi }
        }
        return calls.toList()
    }

    private fun validatedPath(path: String): ThirdPartyTranslationResult.Rejected? {
        val normalized = ToolPackageScriptPaths.normalize(path)
        if (normalized == null || normalized != path) {
            return rejected(
                ThirdPartyTranslationErrorCode.SCRIPT_PATH_UNSUPPORTED,
                "translated.scripts",
                path.take(128),
                "A package script path is outside the MCA executable-v2 script policy (relative .js only)."
            )
        }
        return null
    }

    /** Prefix every carried module with the only translated host surface it may call. */
    private fun prefixScripts(scripts: Map<String, String>): Map<String, String> =
        scripts.mapValues { (_, source) -> PRELUDE + source }

    private fun toolName(raw: String, used: Set<String>): String {
        val mapped = buildString {
            raw.lowercase().forEach { character ->
                append(
                    if (character in 'a'..'z' || character in '0'..'9' || character == '_') {
                        character
                    } else {
                        '_'
                    }
                )
            }
        }.replace(Regex("_+"), "_").trim('_')
        var candidate = when {
            mapped.isEmpty() -> "thirdparty_tool"
            mapped.first() in 'a'..'z' -> mapped
            else -> "thirdparty_$mapped"
        }.take(64).trimEnd('_')
        if (!AgentToolPackageToolRef.isValidToolName(candidate)) candidate = "thirdparty_tool"
        var suffix = 2
        var unique = candidate
        while (unique in used) {
            val tail = "_$suffix"
            unique = candidate.take((64 - tail.length).coerceAtLeast(1)) + tail
            suffix += 1
        }
        return unique
    }

    private fun mcaPackageId(descriptor: ThirdPartyToolPackageDescriptor): String {
        val base = descriptor.normalizedPackageId
        val prefixed = if (base.startsWith(THIRD_PARTY_PREFIX)) base else THIRD_PARTY_PREFIX + base
        val candidate = prefixed.take(64).trim('-')
        if (AgentToolPackageToolRef.isValidPackageId(candidate)) return candidate
        // normalizeThirdPartyPackageId already enforces the MCA pattern; this is a defensive fallback.
        return (THIRD_PARTY_PREFIX + "package").take(64)
    }

    private fun finding(
        category: ThirdPartyCompatibilityCategory,
        symbol: String,
        fieldPath: String,
        status: ThirdPartyCompatibilityStatus,
        reason: String
    ): ThirdPartyCompatibilityFinding = ThirdPartyCompatibilityFinding(
        category = category,
        symbol = symbol.take(256),
        fieldPath = fieldPath.take(512),
        status = status,
        reason = reason
    )

    private fun rejected(
        code: ThirdPartyTranslationErrorCode,
        fieldPath: String,
        symbol: String?,
        message: String
    ): ThirdPartyTranslationResult.Rejected = ThirdPartyTranslationResult.Rejected(
        code = code,
        fieldPath = fieldPath.take(512),
        symbol = symbol?.take(256),
        safeMessage = message.take(512)
    )

    private fun boundedChars(text: String, max: Int): String = text.take(max)

    private fun boundedUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var end = text.length
        while (end > 0 && text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end -= 1
        return text.substring(0, end)
    }

    private data class TranslationTarget(
        val toolName: String,
        val displayName: String,
        val entry: String,
        val hostCalls: List<String>
    )

    private data class ForbiddenSymbol(val symbol: String, val regex: Regex)

    companion object {
        const val THIRD_PARTY_PREFIX: String = "thirdparty-"

        /**
         * Fixed, code-owned translation table. Adding a row is a security-relevant change: the
         * symbol must already exist in [ToolPackageHostApis] and must keep the per-call approval
         * semantics of the bridge.
         */
        val MAPPINGS: Map<String, ThirdPartyHostApiMapping> = linkedMapOf(
            "Tools.Files.read" to ThirdPartyHostApiMapping(
                thirdpartySymbol = "Tools.Files.read",
                mcaHostApi = ToolPackageHostApis.WORKSPACE_READ_TEXT,
                access = ToolPackageHostAccess.READ_ONLY,
                semantics = "Reads one bounded 16 KiB window at offset 0 from inputs/ or artifacts/ only."
            ),
            "Tools.Files.list" to ThirdPartyHostApiMapping(
                thirdpartySymbol = "Tools.Files.list",
                mcaHostApi = ToolPackageHostApis.WORKSPACE_LIST,
                access = ToolPackageHostAccess.READ_ONLY,
                semantics = "Lists direct children only; no recursion and no host path."
            ),
            "Tools.Files.search" to ThirdPartyHostApiMapping(
                thirdpartySymbol = "Tools.Files.search",
                mcaHostApi = ToolPackageHostApis.WORKSPACE_SEARCH_TEXT,
                access = ToolPackageHostAccess.READ_ONLY,
                semantics = "Literal case-sensitive search with bounded matches; no regex."
            ),
            "Tools.Files.write" to ThirdPartyHostApiMapping(
                thirdpartySymbol = "Tools.Files.write",
                mcaHostApi = ToolPackageHostApis.ARTIFACT_WRITE_TEXT,
                access = ToolPackageHostAccess.WRITE,
                semantics = "Writes under artifacts/ only, create-only (expectedOldHash=null), and " +
                    "requires a fresh per-call approval for the exact binding."
            )
        )

        private val FORBIDDEN_SYMBOLS: List<ForbiddenSymbol> = listOf(
            ForbiddenSymbol("Java", Regex("\\bJava\\b")),
            ForbiddenSymbol("Packages", Regex("\\bPackages\\b")),
            ForbiddenSymbol("Android", Regex("\\bAndroid\\b")),
            ForbiddenSymbol("ToolPkg", Regex("\\bToolPkg\\b")),
            ForbiddenSymbol("registerToolPkg", Regex("\\bregisterToolPkg\\b")),
            ForbiddenSymbol("registerTool", Regex("\\bregisterTool\\b")),
            ForbiddenSymbol("loadDex", Regex("\\bloadDex\\b")),
            ForbiddenSymbol("loadJar", Regex("\\bloadJar\\b")),
            ForbiddenSymbol("JavaAdapter", Regex("\\bJavaAdapter\\b")),
            ForbiddenSymbol("importClass", Regex("\\bimportClass\\b")),
            ForbiddenSymbol("fetch", Regex("\\bfetch\\s*\\(")),
            ForbiddenSymbol("XMLHttpRequest", Regex("\\bXMLHttpRequest\\b"))
        )

        private val TOOLS_SYMBOL =
            Regex("Tools\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*)*")
        private val REQUIRE_CALL = Regex("\\brequire\\s*\\(\\s*['\"]([^'\"]+)['\"]")
        private val HOOK_SYMBOL = Regex("\\b(?:hooks?|onBeforeTool|onAfterTool|beforeTool|afterTool)\\b")

        /** Bounded flat argument envelope shared by every translated tool. */
        const val GENERIC_INPUT_SCHEMA: String =
            """{"additionalProperties":false,"properties":{"content":{"maxLength":32768,"type":"string"},"outPath":{"maxLength":512,"type":"string"},"path":{"maxLength":512,"type":"string"},"query":{"maxLength":256,"type":"string"}},"type":"object"}"""

        private const val PRELUDE: String = """
var Tools = (typeof Tools === "undefined") ? {
  Files: {
    read: function (path) {
      return JSON.parse(host.call("workspace.read_text", JSON.stringify({ path: String(path), offset: 0, limit: 16384 }))).text;
    },
    list: function (path) {
      return JSON.parse(host.call("workspace.list", JSON.stringify({ path: String(path) })));
    },
    search: function (query, path) {
      return JSON.parse(host.call("workspace.search_text", JSON.stringify({ query: String(query), path: (path === null || typeof path === "undefined") ? "" : String(path), maxMatches: 20 })));
    },
    write: function (path, content) {
      return JSON.parse(host.call("artifact.write_text", JSON.stringify({ path: String(path), content: String(content), expectedOldHash: null })));
    }
  }
} : Tools;
"""
    }
}

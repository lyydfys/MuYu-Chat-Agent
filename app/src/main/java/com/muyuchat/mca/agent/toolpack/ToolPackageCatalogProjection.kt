package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor

/** One enabled package that could not be projected, with a typed, content-free reason. */
data class ToolPackageProjectionSkip(
    val packageId: String,
    val contentHash: String,
    val code: String,
    val safeMessage: String
)

/**
 * Result of projecting the installed package set into a run catalog.
 *
 * [catalog] is an immutable snapshot; [includedContentHashes] documents exactly which content the
 * snapshot was built from. [skipped] is not an error channel the caller may ignore: an enabled
 * package that failed verification (or declares write capabilities) is reported and contributes no
 * tools, so an unavailable package can never look usable.
 */
data class ToolPackageProjectionResult(
    val catalog: AgentToolCatalog,
    val includedContentHashes: List<String>,
    val skipped: List<ToolPackageProjectionSkip>
)

/**
 * `install -> verify -> enable -> catalog` projection (N09a/N12 minimal loop).
 *
 * Each projection re-reads the store, so an enable/disable/rollback is visible to the next run
 * without restarting anything. A package only appears when it is enabled **and** its stored bytes
 * still hash to the recorded content hash; write-capable packages are skipped with the typed
 * `TOOLPACK_WRITE_UNSUPPORTED` code because the production catalog has no per-call approval owner
 * injected, so a nested write could never be dispatched.
 *
 * The catalog does not cache executors across calls and never mutates a previously returned
 * snapshot, which keeps the "one run, one frozen tool set" rule intact.
 */
class ToolPackageCatalogProjection(
    private val store: ToolPackageInstallStore,
    private val runtime: ToolPackageScriptRuntime,
    private val approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL,
    private val allowWriteCapability: Boolean = false
) {
    suspend fun project(): ToolPackageProjectionResult {
        val records = store.activeRecords()
        val executors = LinkedHashMap<String, AgentToolExecutor>()
        val included = ArrayList<String>()
        val skipped = ArrayList<ToolPackageProjectionSkip>()
        for (record in records) {
            when (val verified = store.verify(record.contentHash)) {
                is ToolPackageVerifyResult.Rejected -> skipped += ToolPackageProjectionSkip(
                    packageId = record.packageId,
                    contentHash = record.contentHash,
                    code = verified.code,
                    safeMessage = verified.safeMessage
                )
                is ToolPackageVerifyResult.Valid -> {
                    val bundle = verified.bundle
                    if (bundle.manifest.declaresWriteCapability && !allowWriteCapability) {
                        skipped += ToolPackageProjectionSkip(
                            packageId = record.packageId,
                            contentHash = record.contentHash,
                            code = ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED.agentCode,
                            safeMessage = "Write-capable packages are not projected into a run catalog."
                        )
                        continue
                    }
                    val packageCatalog = try {
                        ToolPackageToolCatalog.project(bundle, runtime, approval, allowWriteCapability)
                    } catch (failure: ToolPackageException) {
                        skipped += ToolPackageProjectionSkip(
                            packageId = record.packageId,
                            contentHash = record.contentHash,
                            code = failure.error.code.agentCode,
                            safeMessage = failure.error.safeMessage
                        )
                        continue
                    }
                    for (descriptor in packageCatalog.snapshot()) {
                        val executor = packageCatalog.find(descriptor.toolKey)
                        if (executor == null) {
                            skipped += ToolPackageProjectionSkip(
                                packageId = record.packageId,
                                contentHash = record.contentHash,
                                code = "TOOLPACK_CATALOG_INCONSISTENT",
                                safeMessage = "The package catalog lost an executor it had just advertised."
                            )
                            continue
                        }
                        if (executors.put(descriptor.toolKey, executor) != null) {
                            skipped += ToolPackageProjectionSkip(
                                packageId = record.packageId,
                                contentHash = record.contentHash,
                                code = "TOOLPACK_TOOL_KEY_COLLISION",
                                safeMessage = "Two packages project to the same runtime tool key."
                            )
                        }
                    }
                    included += record.contentHash
                }
            }
        }
        return ToolPackageProjectionResult(
            catalog = InstalledPackageCatalog(executors),
            includedContentHashes = included.sorted(),
            skipped = skipped.sortedWith(compareBy({ it.packageId }, { it.code }))
        )
    }
}

/** Immutable snapshot over projected package executors (never mutated after construction). */
private class InstalledPackageCatalog(
    executors: Map<String, AgentToolExecutor>
) : AgentToolCatalog {
    private val byKey: Map<String, AgentToolExecutor> = LinkedHashMap(executors)
    private val descriptors: List<AgentToolDescriptor> =
        byKey.keys.sorted().map { key -> requireNotNull(byKey[key]).descriptor }

    override fun find(toolKey: String): AgentToolExecutor? = byKey[toolKey]

    override fun snapshot(): List<AgentToolDescriptor> = descriptors
}

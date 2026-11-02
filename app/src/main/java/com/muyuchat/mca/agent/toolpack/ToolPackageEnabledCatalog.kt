package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor

/**
 * Result of reading the enabled executable-package set for one catalog assembly.
 *
 * [executors] is immutable and sorted by tool key. [skipped] explains every enabled record that
 * contributed nothing (failed re-verification, write capability, projection error) so an unavailable
 * package can never look usable.
 */
data class ToolPackageEnabledCatalog(
    val executors: List<AgentToolExecutor>,
    val includedContentHashes: List<String>,
    val skipped: List<ToolPackageProjectionSkip>
) {
    val isEmpty: Boolean get() = executors.isEmpty()
}

/**
 * Host handler used when the composition root did not supply real host capabilities yet.
 *
 * It never reads or writes anything: a package that calls a declared host API gets a typed
 * `TOOLPACK_HOST_CALL_DENIED` instead of a silent success. Pure-computation packages keep working,
 * so the catalog is honest without requiring a half-wired bridge. The resource probe is refused with
 * the same code so a capability is never resolved against a handler that cannot touch it.
 */
fun denyToolPackageHostHandler(): ToolPackageHostOperationHandler =
    object : ToolPackageHostOperationHandler {
        private val denial = ToolPackageError(
            ToolPackageErrorCode.HOST_CALL_DENIED,
            "No host capability handler is wired for executable packages."
        )

        override suspend fun handle(request: ToolPackageHostOperationRequest): ToolPackageHostOperationResult =
            ToolPackageHostOperationResult.Denied(denial)

        override suspend fun resolveResource(
            request: ToolPackageHostResourceRequest
        ): ToolPackageHostResourceResolution = ToolPackageHostResourceResolution.Refused(denial)
    }

/** Production-safe default runtime: real Rhino engine + deny-all host bridge. */
fun defaultToolPackageRuntime(): ToolPackageScriptRuntime =
    ToolPackageScriptRuntime(RhinoToolPackageScriptEngine(), denyToolPackageHostHandler())

/**
 * The production catalog data source for executable packages (R4).
 *
 * Reads the durable store, keeps only records that are **enabled and not revoked**, re-verifies each
 * content hash, refuses write-capable packages and projects the rest through
 * [ToolPackageToolCatalog] over the supplied runtime. The returned list is sorted and contains no
 * duplicates; a package that fails any step is reported in [ToolPackageEnabledCatalog.skipped]
 * instead of being silently dropped.
 *
 * [approval] is the per-call write-approval seam. The composition root calls
 * `enabledToolPackageExecutors(store, runtime)` today, so the fail-closed
 * [ToolPackageHostApprovalPort.DENY_ALL] applies: a nested write host call is refused with
 * `TOOLPACK_HOST_API_WRITE_NOT_APPROVED`. Injecting a real owner here is the only supported way to
 * open nested writes; there is no per-run or per-script shortcut.
 *
 * The caller (composition root) merges these executors into the base built-in catalog. Nothing here
 * mutates the store, so an enable/disable takes effect on the next catalog assembly.
 */
suspend fun enabledToolPackageCatalog(
    store: ToolPackageInstallStore,
    runtime: ToolPackageScriptRuntime = defaultToolPackageRuntime(),
    approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL,
    allowWriteCapability: Boolean = false
): ToolPackageEnabledCatalog {
    val records = store.scan().records
        .filter { it.enabled && !it.revoked }
        .sortedBy { it.packageId }
    val byKey = LinkedHashMap<String, AgentToolExecutor>()
    val included = ArrayList<String>()
    val skipped = ArrayList<ToolPackageProjectionSkip>()
    for (record in records) {
        val verified = when (val result = store.verify(record.contentHash)) {
            is ToolPackageVerifyResult.Valid -> result
            is ToolPackageVerifyResult.Rejected -> {
                skipped += ToolPackageProjectionSkip(
                    packageId = record.packageId,
                    contentHash = record.contentHash,
                    code = result.code,
                    safeMessage = result.safeMessage
                )
                continue
            }
        }
        val manifest = verified.bundle.manifest
        if (manifest.declaresWriteCapability && !allowWriteCapability) {
            skipped += ToolPackageProjectionSkip(
                packageId = record.packageId,
                contentHash = record.contentHash,
                code = ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED.agentCode,
                safeMessage = "Write-capable packages are not projected into a run catalog."
            )
            continue
        }
        val packageCatalog = try {
            ToolPackageToolCatalog.project(verified.bundle, runtime, approval, allowWriteCapability)
        } catch (failure: ToolPackageException) {
            skipped += ToolPackageProjectionSkip(
                packageId = record.packageId,
                contentHash = record.contentHash,
                code = failure.error.code.agentCode,
                safeMessage = failure.error.safeMessage
            )
            continue
        }
        var added = 0
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
            if (byKey.put(descriptor.toolKey, executor) == null) {
                added += 1
            } else {
                // Two live records must never advertise the same runtime key (package ids are unique,
                // so this only happens with corrupted state): keep the first and report the rest.
                skipped += ToolPackageProjectionSkip(
                    packageId = record.packageId,
                    contentHash = record.contentHash,
                    code = "TOOLPACK_TOOL_KEY_COLLISION",
                    safeMessage = "Two packages project to the same runtime tool key."
                )
            }
        }
        if (added > 0) included += record.contentHash
    }
    return ToolPackageEnabledCatalog(
        executors = byKey.keys.sorted().map { requireNotNull(byKey[it]) },
        includedContentHashes = included.sorted(),
        skipped = skipped.sortedWith(compareBy({ it.packageId }, { it.code }))
    )
}

/** Convenience for the composition root: just the executors of [enabledToolPackageCatalog]. */
suspend fun enabledToolPackageExecutors(
    store: ToolPackageInstallStore,
    runtime: ToolPackageScriptRuntime = defaultToolPackageRuntime(),
    approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL,
    allowWriteCapability: Boolean = false
): List<AgentToolExecutor> =
    enabledToolPackageCatalog(store, runtime, approval, allowWriteCapability).executors

/**
 * Base catalog view that appends executable-package tools from a **supplier**.
 *
 * The composition root keeps a versioned [ToolPackageExecutorSnapshot] refreshed off the main thread
 * after startup and after every lifecycle change, and passes `{ snapshot.current() }` here once. The
 * first run catalog resolution awaits the first commit, so a cold-start run never freezes an empty
 * package surface.
 * Why a supplier instead of a one-shot merge:
 * - the base catalog is assembled synchronously, while reading the store is suspend I/O;
 * - a run still freezes its own `AgentToolCatalog` snapshot at run start
 *   (`AgentToolCatalogAssembler`), so a later enable/disable only affects the next run;
 * - a disabled/revoked/failed package simply stops appearing on the next snapshot read.
 *
 * Built-in keys always win: an executable package can never shadow a built-in tool key
 * (`pkg.` keys are disjoint by construction, this is defense in depth).
 */
fun AgentToolCatalog.augmentedWithPackageTools(
    packageExecutors: () -> List<AgentToolExecutor>
): AgentToolCatalog = PackageAugmentedAgentToolCatalog(this, packageExecutors)

private class PackageAugmentedAgentToolCatalog(
    private val base: AgentToolCatalog,
    private val packageExecutors: () -> List<AgentToolExecutor>
) : AgentToolCatalog {
    override fun find(toolKey: String): AgentToolExecutor? =
        base.find(toolKey) ?: packageExecutors().firstOrNull { it.descriptor.toolKey == toolKey }

    override fun snapshot(): List<AgentToolDescriptor> {
        val baseDescriptors = base.snapshot()
        val baseKeys = baseDescriptors.mapTo(HashSet()) { it.toolKey }
        val extra = packageExecutors()
            .map { it.descriptor }
            .filter { it.toolKey !in baseKeys }
            .distinctBy { it.toolKey }
        if (extra.isEmpty()) return baseDescriptors
        return (baseDescriptors + extra).sortedBy { it.toolKey }
    }
}

package com.muyuchat.core.dsh.maintenance

import java.io.File

/**
 * Self-check and precise repair (D22b).
 *
 * The check is split into observed runtime probes (injected by the adapter: payload hash, Node
 * executable, native ABI, home marker, official session reader, gateway binding, bridge) and the
 * managed-file inventory. Only a managed file with a known-good expected hash may be replaced, and
 * only from a source whose hash already matches. Sessions, the recycle bin, attachments, the user
 * workspace, credentials and third-party plugin data are never repair targets.
 */

enum class DshSelfCheckCheck {
    PAYLOAD_INTEGRITY,
    NODE_EXECUTABLE,
    NATIVE_ABI,
    HOME_MARKER,
    SESSION_READER,
    GATEWAY_BINDING,
    BRIDGE,
    MANAGED_FILE
}

data class DshSelfCheckProbe(val check: DshSelfCheckCheck, val ok: Boolean, val detail: String = "")

data class DshSelfCheckReport(val checks: List<DshSelfCheckProbe>, val atMillis: Long) {
    val healthy: Boolean get() = checks.all { it.ok }
}

/** Adapter-supplied real probes. A probe must observe the real condition, never "file exists". */
fun interface DshSelfCheckPort {
    fun run(): List<DshSelfCheckProbe>
}

/** Root a managed file is resolved against. */
enum class DshManagedFileRoot {
    /** `DSH_HOME` (`files/dsh/home`): seeded profile/config files MCA owns. */
    HOME,

    /**
     * The verified payload tree (`files/dsh/payload`): the official kernel and Node runtime
     * libraries. These are never user data, so they are the safe part of the tree to repair.
     */
    PAYLOAD
}

/**
 * One managed file this build is allowed to repair.
 *
 * [relativePath] is relative to [root]. A payload entry is exactly one path from the build's
 * payload manifest (`criticalFiles`), so its expected hash comes from the signed build input rather
 * than from whatever is currently on disk.
 */
data class DshManagedFile(
    val relativePath: String,
    val expectedSha256: String,
    val root: DshManagedFileRoot = DshManagedFileRoot.HOME
)

data class DshSelfCheckRepair(
    val relativePath: String,
    val expectedSha256: String,
    val source: File,
    val target: File
)

data class DshSelfCheckRepairPlan(
    val repairs: List<DshSelfCheckRepair>,
    val blocked: List<String>
) {
    val hasWork: Boolean get() = repairs.isNotEmpty()
}

sealed interface DshSelfCheckOutcome {
    data class Healthy(val report: DshSelfCheckReport) : DshSelfCheckOutcome

    /** Only managed files are missing/mismatched; a bounded repair is possible. */
    data class NeedsRepair(
        val report: DshSelfCheckReport,
        val mismatched: List<String>
    ) : DshSelfCheckOutcome

    /** A real runtime condition that file replacement cannot fix. */
    data class RequiresAttention(
        val report: DshSelfCheckReport,
        val reasons: List<String>
    ) : DshSelfCheckOutcome
}

class DshSelfCheckPolicy(
    private val managedFiles: List<DshManagedFile> = emptyList(),
    private val protectedPrefixes: List<String> = DEFAULT_PROTECTED_PREFIXES,
    /**
     * Root of the verified payload tree, or null when this build has none. A PAYLOAD-scoped file
     * is then unverifiable: it is skipped rather than reported as a mismatch, because a missing
     * payload root is not evidence that the file was tampered with.
     */
    private val payloadDir: File? = null
) {
    /** Managed files of both roots, in declaration order. */
    val managedFileCount: Int get() = managedFiles.size

    /** Never a repair target: user sessions, recycle bin, attachments, workspace, credentials. */
    fun isProtected(relativePath: String): Boolean {
        val normalized = relativePath.trimStart('/')
        if (!DshMaintenanceIo.isSafeRelativePath(normalized)) return true
        return protectedPrefixes.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix/")
        }
    }

    /** Reads the real hash of every managed file; returns relative paths that are not as declared. */
    fun findMismatched(homeDir: File): List<String> {
        val mismatched = ArrayList<String>()
        for (managed in managedFiles) {
            if (isProtected(managed.relativePath)) continue
            val root = rootOf(managed, homeDir) ?: continue
            val target = DshMaintenanceIo.resolveWithin(root, managed.relativePath) ?: continue
            if (!target.isFile) {
                mismatched.add(managed.relativePath)
                continue
            }
            if (!DshMaintenanceIo.sha256(target).equals(managed.expectedSha256, ignoreCase = true)) {
                mismatched.add(managed.relativePath)
            }
        }
        return mismatched
    }

    fun evaluate(report: DshSelfCheckReport, homeDir: File): DshSelfCheckOutcome {
        val reasons = ArrayList<String>()
        for (probe in report.checks.filterNot { it.ok }) {
            when (probe.check) {
                DshSelfCheckCheck.PAYLOAD_INTEGRITY,
                DshSelfCheckCheck.NODE_EXECUTABLE,
                DshSelfCheckCheck.NATIVE_ABI,
                DshSelfCheckCheck.HOME_MARKER,
                DshSelfCheckCheck.SESSION_READER,
                DshSelfCheckCheck.GATEWAY_BINDING,
                DshSelfCheckCheck.BRIDGE -> reasons.add("${probe.check.name}: ${probe.detail.take(120)}")
                DshSelfCheckCheck.MANAGED_FILE -> Unit
            }
        }
        if (reasons.isNotEmpty()) return DshSelfCheckOutcome.RequiresAttention(report, reasons)
        val mismatched = findMismatched(homeDir)
        if (mismatched.isEmpty()) return DshSelfCheckOutcome.Healthy(report)
        return DshSelfCheckOutcome.NeedsRepair(report, mismatched)
    }

    /**
     * Builds the exact repair plan. A source is usable only when it exists, has the expected hash
     * and every path is safe; every other case is reported as blocked and never "repaired".
     */
    fun planRepairs(homeDir: File, managedSourceRoot: File?, mismatched: List<String>): DshSelfCheckRepairPlan {
        val repairs = ArrayList<DshSelfCheckRepair>()
        val blocked = ArrayList<String>()
        for (relative in mismatched) {
            if (isProtected(relative)) {
                blocked.add("$relative (protected user data)")
                continue
            }
            val declaration = managedFiles.firstOrNull { it.relativePath == relative }
            if (declaration == null) {
                blocked.add("$relative (not declared as a managed file)")
                continue
            }
            val target = rootOf(declaration, homeDir)?.let { root ->
                DshMaintenanceIo.resolveWithin(root, relative)
            }
            if (target == null) {
                blocked.add("$relative (its managed root is unavailable)")
                continue
            }
            val source = managedSourceRoot?.let { DshMaintenanceIo.resolveWithin(it, relative) }
            if (source == null || !source.isFile) {
                blocked.add("$relative (managed source is absent)")
                continue
            }
            if (!DshMaintenanceIo.sha256(source).equals(declaration.expectedSha256, ignoreCase = true)) {
                blocked.add("$relative (managed source hash does not match the build manifest)")
                continue
            }
            repairs.add(DshSelfCheckRepair(relative, declaration.expectedSha256, source, target))
        }
        return DshSelfCheckRepairPlan(repairs, blocked)
    }

    /**
     * Applies only [plan]'s repairs through the maintenance engine (quarantine + atomic install +
     * official-reader health). A blocked item is never silently skipped: the caller receives the
     * transaction outcome plus the blocked list.
     */
    fun applyRepairs(
        engine: DshMaintenanceEngine,
        plan: DshSelfCheckRepairPlan,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceOutcome {
        if (!plan.hasWork) {
            return DshMaintenanceOutcome.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.SELF_CHECK_FAILED,
                    "no managed file can be repaired from this build",
                    detail = plan.blocked.joinToString("; ").take(300)
                )
            )
        }
        val steps = plan.repairs.map {
            DshMaintenanceStep(
                action = DshStepAction.INSTALL_FILE,
                target = it.target,
                staging = it.source,
                expectedSha256 = it.expectedSha256
            )
        }
        return engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.SELF_CHECK,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = steps,
                healthCheckRequired = true,
                detail = "repairs=${steps.size};blocked=${plan.blocked.size}"
            )
        )
    }

    private fun rootOf(managed: DshManagedFile, homeDir: File): File? = when (managed.root) {
        DshManagedFileRoot.HOME -> homeDir
        DshManagedFileRoot.PAYLOAD -> payloadDir
    }

    companion object {
        val DEFAULT_PROTECTED_PREFIXES = listOf(
            "sessions",
            "sessions-deleted",
            "attachments",
            "workspace",
            "config",
            ".credentials.yaml",
            ".credentials"
        )
    }
}

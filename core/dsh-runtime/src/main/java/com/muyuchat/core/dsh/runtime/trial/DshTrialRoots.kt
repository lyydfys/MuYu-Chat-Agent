package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import java.nio.file.Files

/**
 * Ownership rules of one isolated trial generation (D24).
 *
 * The trial is a *non-publishing* generation of the single runtime owner: it must never touch the
 * real `files/dsh/home`, `workspace`, `tmp` or the verified payload tree, and it must never accept a
 * candidate staging root from outside `files/dsh/closure`. This object is the only place that
 * decides whether the three app-provided roots and the candidate root are admissible; it never
 * creates, copies or deletes anything.
 *
 * Every refusal carries a *categorical, path-free* reason, because the reason is later projected
 * into a `DshTrialSnapshot.detail` (Binder-bound, secret- and path-free).
 */
internal object DshTrialRoots {

    /** Sibling root that owns every trial generation. */
    const val TRIAL_DIR_NAME = "trial"

    /** Sibling root that owns verified closure candidates (written by `:core:dsh-maintenance`). */
    const val CLOSURE_DIR_NAME = "closure"

    /** Staged candidate manifest; its presence is what makes a candidate root a candidate. */
    const val CANDIDATE_MANIFEST_NAME = "candidate.json"

    /**
     * Executable payload tree inside a staged candidate. The trial launches this tree — never the
     * installed payload — so a candidate that does not carry one is refused.
     */
    const val CANDIDATE_PAYLOAD_DIR_NAME = "payload"

    /** Admissible trial generation roots plus the trial transaction root that contains them. */
    data class Accepted(
        val trialRoot: File,
        val home: File,
        val workspace: File,
        val tmp: File,
        val candidate: File
    )

    sealed interface Resolution {
        data class Ok(val accepted: Accepted) : Resolution

        data class Refused(val reason: String) : Resolution
    }

    fun resolve(
        dataRoot: File,
        candidateRoot: File,
        homeRoot: File,
        workspaceRoot: File,
        tmpRoot: File
    ): Resolution {
        if (!dataRoot.isAbsolute || !candidateRoot.isAbsolute || !homeRoot.isAbsolute ||
            !workspaceRoot.isAbsolute || !tmpRoot.isAbsolute
        ) {
            return refused("the trial roots must be absolute app-private paths")
        }
        // All three data roots belong to exactly one trial transaction. The shared parent is the
        // trial data root (for example `files/dsh/trial/<txid>`), which keeps the seeder's sibling
        // markers and any later cleanup contained to one directory.
        val homeParent = homeRoot.absoluteFile.parentFile
            ?: return refused("the trial home root has no parent directory")
        val workspaceParent = workspaceRoot.absoluteFile.parentFile
        val tmpParent = tmpRoot.absoluteFile.parentFile
        if (workspaceParent == null || tmpParent == null || homeParent != workspaceParent ||
            homeParent != tmpParent
        ) {
            return refused("the trial home, workspace and tmp roots must share one trial root")
        }
        val trialRoot = homeParent
        val trialBase = canonicalOrNull(File(dataRoot, TRIAL_DIR_NAME))
            ?: return refused("the trial root cannot be resolved")
        val trialRootCanonical = canonicalOrNull(trialRoot)
            ?: return refused("the trial root cannot be resolved")
        // The transaction root is either `files/dsh/trial` itself (the app's default layout) or a
        // per-transaction directory below it; it is never the managed data root itself.
        if (!isWithin(trialBase, trialRootCanonical) || trialRootCanonical == canonicalOrNull(dataRoot)) {
            return refused("the trial roots must live below the managed trial directory")
        }
        // Never the production generation's roots, and never a symlinked path: a symlink inside the
        // trial tree could redirect the trial host's writes into the real home.
        val forbidden = listOf(
            File(dataRoot, "home"),
            File(dataRoot, "workspace"),
            File(dataRoot, "tmp"),
            File(dataRoot, "payload"),
            File(dataRoot, "closure"),
            dataRoot
        ).mapNotNull(::canonicalOrNull).toSet()
        for ((name, root) in listOf(
            "trial home" to homeRoot,
            "trial workspace" to workspaceRoot,
            "trial tmp" to tmpRoot
        )) {
            val canonical = canonicalOrNull(root)
                ?: return refused("the $name root cannot be resolved")
            if (canonical in forbidden) {
                return refused("the $name root must not be a production DSH root")
            }
            if (!isStrictlyInside(canonical, trialRootCanonical)) {
                return refused("the $name root must live inside the trial root")
            }
            if (!symlinkFree(trialRootCanonical, canonical)) {
                return refused("the $name root must not traverse a symbolic link")
            }
        }
        if (homeRoot.absoluteFile == workspaceRoot.absoluteFile ||
            homeRoot.absoluteFile == tmpRoot.absoluteFile ||
            workspaceRoot.absoluteFile == tmpRoot.absoluteFile
        ) {
            return refused("the trial home, workspace and tmp roots must be distinct")
        }

        val closureBase = canonicalOrNull(File(dataRoot, CLOSURE_DIR_NAME))
            ?: return refused("the closure root cannot be resolved")
        val candidateCanonical = canonicalOrNull(candidateRoot)
            ?: return refused("the candidate staging root cannot be resolved")
        if (!isStrictlyInside(candidateCanonical, closureBase)) {
            return refused("the candidate staging root must live below the managed closure directory")
        }
        if (!symlinkFree(closureBase, candidateCanonical)) {
            return refused("the candidate staging root must not traverse a symbolic link")
        }
        if (!candidateRoot.isDirectory) {
            return refused("the candidate staging root is not a directory")
        }
        val manifest = File(candidateRoot, CANDIDATE_MANIFEST_NAME)
        if (!manifest.isFile || manifest.length() <= 0L) {
            return refused("the candidate staging root carries no staged candidate manifest")
        }
        return Resolution.Ok(
            Accepted(
                trialRoot = trialRootCanonical,
                home = homeRoot.absoluteFile,
                workspace = workspaceRoot.absoluteFile,
                tmp = tmpRoot.absoluteFile,
                candidate = candidateRoot.absoluteFile
            )
        )
    }

    /** True when [value] is the same path or a descendant of [root] (case-aware on Windows). */
    fun isWithin(root: File, value: File): Boolean {
        val rootPath = root.absoluteFile.path
        val valuePath = value.absoluteFile.path
        val caseInsensitive = File.separatorChar == '\\'
        return valuePath.equals(rootPath, ignoreCase = caseInsensitive) ||
            valuePath.startsWith(rootPath + File.separator, ignoreCase = caseInsensitive)
    }

    private fun isStrictlyInside(value: File, root: File): Boolean =
        value != root && isWithin(root, value)

    /**
     * Walks every path component from [root] down to [value] and rejects a symbolic link. Canonical
     * paths cannot be compared textually for this: they resolve the link and would look clean.
     */
    private fun symlinkFree(root: File, value: File): Boolean {
        var current: File? = File(value.path)
        while (current != null) {
            val link = runCatching { Files.isSymbolicLink(current.toPath()) }.getOrDefault(true)
            if (link) return false
            if (current.path == root.path) return true
            current = current.parentFile
        }
        return false
    }

    private fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun refused(reason: String): Resolution = Resolution.Refused(reason)
}

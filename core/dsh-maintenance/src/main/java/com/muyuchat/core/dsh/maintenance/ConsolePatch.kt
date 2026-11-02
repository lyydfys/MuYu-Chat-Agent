package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User patch schema for the DSH console (D23).
 *
 * Three layers are recorded by hash: factory source, the managed Android/MCA patch, and the user
 * diff. Applying a user diff is exact per-file (never a whole-tree string replacement) and requires
 * the declared base hash to match the current file; a mismatch is a visible conflict with no
 * writes. Executable entries (ELF/DEX/JAR/SO/APK/native addon) and upgrade-managed trees are
 * rejected outright. Clearing the user layer removes only that layer; sessions, plugins and user
 * data are untouched.
 */

enum class DshPatchScope { DSH_CONSOLE, OTHER }

data class DshPatchFile(
    val relativePath: String,
    val baseSha256: String?,
    val newSha256: String,
    val content: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is DshPatchFile && relativePath == other.relativePath &&
            baseSha256 == other.baseSha256 && newSha256 == other.newSha256 &&
            content.contentEquals(other.content)

    override fun hashCode(): Int =
        ((relativePath.hashCode() * 31 + (baseSha256?.hashCode() ?: 0)) * 31 + newSha256.hashCode()) * 31 +
            content.contentHashCode()
}

data class DshPatchProposal(
    val patchId: String,
    val targetVersion: String,
    val scope: DshPatchScope,
    val factoryBaseHash: String,
    val managedPatchHash: String,
    val files: List<DshPatchFile>
)

data class DshConsolePatchState(
    val targetVersion: String = "",
    val factoryBaseHash: String = "",
    val managedPatchHash: String = "",
    val userDiffHash: String = "",
    val activePatchId: String? = null,
    val previousPatchId: String? = null,
    val lastRestorePoint: String? = null,
    val appliedFiles: List<String> = emptyList()
)

class DshConsolePatchStateStore(private val file: File) {
    fun load(): DshConsolePatchState {
        if (!file.isFile) return DshConsolePatchState()
        return runCatching {
            val json = JSONObject(DshMaintenanceIo.readText(file))
            val files = json.optJSONArray("appliedFiles") ?: JSONArray()
            val applied = ArrayList<String>(files.length())
            for (index in 0 until files.length()) applied.add(files.optString(index))
            DshConsolePatchState(
                targetVersion = json.optString("targetVersion"),
                factoryBaseHash = json.optString("factoryBaseHash"),
                managedPatchHash = json.optString("managedPatchHash"),
                userDiffHash = json.optString("userDiffHash"),
                activePatchId = json.optString("activePatchId").takeIf { it.isNotEmpty() },
                previousPatchId = json.optString("previousPatchId").takeIf { it.isNotEmpty() },
                lastRestorePoint = json.optString("lastRestorePoint").takeIf { it.isNotEmpty() },
                appliedFiles = applied
            )
        }.getOrDefault(DshConsolePatchState())
    }

    fun save(state: DshConsolePatchState) {
        val json = JSONObject()
        json.put("targetVersion", state.targetVersion)
        json.put("factoryBaseHash", state.factoryBaseHash)
        json.put("managedPatchHash", state.managedPatchHash)
        json.put("userDiffHash", state.userDiffHash)
        json.put("activePatchId", state.activePatchId ?: "")
        json.put("previousPatchId", state.previousPatchId ?: "")
        json.put("lastRestorePoint", state.lastRestorePoint ?: "")
        val files = JSONArray()
        state.appliedFiles.forEach(files::put)
        json.put("appliedFiles", files)
        DshMaintenanceIo.writeDurably(file, json.toString())
    }
}

sealed interface DshPatchResult {
    data class Applied(
        val transactionId: String,
        val patchId: String,
        val files: Int,
        val restorePoint: File?
    ) : DshPatchResult

    data class Cleared(val transactionId: String) : DshPatchResult
    data class RolledBack(val transactionId: String) : DshPatchResult
    data class Conflict(val conflicts: List<String>) : DshPatchResult
    data class Rejected(val failure: DshMaintenanceFailure) : DshPatchResult
}

/** Exact-scope console patch manager. */
class DshConsolePatchManager(
    private val engine: DshMaintenanceEngine,
    private val homeDir: File,
    private val userLayerRelative: String = "user-patches/dsh-console",
    private val stateStore: DshConsolePatchStateStore =
        DshConsolePatchStateStore(File(engine.dataRootDirectory, "maintenance/console-patch.json"))
) {
    fun state(): DshConsolePatchState = stateStore.load()

    fun validate(proposal: DshPatchProposal): DshMaintenanceFailure? {
        if (proposal.scope != DshPatchScope.DSH_CONSOLE) {
            return DshMaintenanceFailure(
                DshMaintenanceError.PATCH_FORBIDDEN,
                "user patches may only target the DSH console"
            )
        }
        if (proposal.patchId.isBlank() || proposal.patchId.length > 96) {
            return DshMaintenanceFailure(DshMaintenanceError.PATCH_CONFLICT, "patch id is missing or too long")
        }
        if (!DshMaintenanceIo.isSafeRelativePath(userLayerRelative)) {
            return DshMaintenanceFailure(DshMaintenanceError.PATCH_FORBIDDEN, "user patch layer path is unsafe")
        }
        if (proposal.files.isEmpty() || proposal.files.size > MAX_FILES) {
            return DshMaintenanceFailure(DshMaintenanceError.PATCH_FORBIDDEN, "patch file count is outside the budget")
        }
        var total = 0L
        for (file in proposal.files) {
            total += file.content.size
            if (file.content.size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                return DshMaintenanceFailure(DshMaintenanceError.PATCH_FORBIDDEN, "patch exceeds its size budget")
            }
            if (!DshMaintenanceIo.isSafeRelativePath(file.relativePath)) {
                return DshMaintenanceFailure(DshMaintenanceError.PATCH_FORBIDDEN, "patch path is not a safe relative path")
            }
            forbidden(file.relativePath)?.let { return it }
            if (!DshMaintenanceIo.sha256(file.content).equals(file.newSha256, ignoreCase = true)) {
                return DshMaintenanceFailure(DshMaintenanceError.PATCH_CONFLICT, "patch content does not match its declared hash")
            }
            semanticCheck(file)?.let { return it }
        }
        return null
    }

    /**
     * Applies [proposal] when every base hash matches. All conflicts are reported together and
     * nothing is written; a valid apply saves a restore point of exactly the replaced files.
     */
    fun apply(
        proposal: DshPatchProposal,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence,
        admissionWaitMillis: Long = 0L
    ): DshPatchResult {
        validate(proposal)?.let { return DshPatchResult.Rejected(it) }
        val current = stateStore.load()
        val conflicts = ArrayList<String>()
        if (current.targetVersion.isNotEmpty() && current.targetVersion != proposal.targetVersion) {
            conflicts.add("target version ${proposal.targetVersion} does not match ${current.targetVersion}")
        }
        if (current.managedPatchHash.isNotEmpty() && current.managedPatchHash != proposal.managedPatchHash) {
            conflicts.add("managed patch layer changed since the proposal was authored")
        }
        val layerRoot = File(homeDir, userLayerRelative)
        val targets = proposal.files.map { File(layerRoot, it.relativePath) }
        for ((index, file) in proposal.files.withIndex()) {
            val target = targets[index]
            val actualBase = if (target.isFile) DshMaintenanceIo.sha256(target) else null
            if (actualBase != file.baseSha256) {
                conflicts.add("${file.relativePath}: base hash does not match the current file")
            }
        }
        if (conflicts.isNotEmpty()) return DshPatchResult.Conflict(conflicts)
        val workRoot = try {
            engine.newWorkDirectory("console-patch")
        } catch (io: Exception) {
            return DshPatchResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the patch staging directory")
            )
        }
        val restorePoint = try {
            engine.createRestorePointDirectory("patch")
        } catch (io: Exception) {
            return DshPatchResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the patch restore point")
            )
        }
        val steps = ArrayList<DshMaintenanceStep>()
        for ((index, file) in proposal.files.withIndex()) {
            val target = targets[index]
            if (target.isFile) {
                DshMaintenanceIo.copyFileDurably(target, File(restorePoint, "files/${file.relativePath}"))
            }
            val staged = File(workRoot, "${index}-${target.name}")
            DshMaintenanceIo.writeDurably(staged, file.content)
            steps.add(
                DshMaintenanceStep(
                    action = DshStepAction.INSTALL_FILE,
                    target = target,
                    staging = staged,
                    expectedSha256 = file.newSha256
                )
            )
        }
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.PATCH_APPLY,
                requester = requester,
                quiescence = quiescence,
                targetRoot = homeDir,
                steps = steps,
                restorePoint = restorePoint,
                healthCheckRequired = true,
                admissionWaitMillis = admissionWaitMillis,
                detail = "patch=${proposal.patchId};files=${steps.size}"
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> {
                stateStore.save(
                    current.copy(
                        targetVersion = proposal.targetVersion,
                        factoryBaseHash = proposal.factoryBaseHash,
                        managedPatchHash = proposal.managedPatchHash,
                        userDiffHash = hashLayer(layerRoot),
                        activePatchId = proposal.patchId,
                        previousPatchId = current.activePatchId,
                        lastRestorePoint = restorePoint.absolutePath,
                        appliedFiles = proposal.files.map { it.relativePath }
                    )
                )
                DshPatchResult.Applied(outcome.transactionId, proposal.patchId, steps.size, restorePoint)
            }

            is DshMaintenanceOutcome.RolledBack -> DshPatchResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshPatchResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshPatchResult.Rejected(outcome.failure)
        }
    }

    /** Rollback to the exact pre-apply copies captured by the last successful apply/clear. */
    fun rollback(
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshPatchResult {
        val current = stateStore.load()
        val point = current.lastRestorePoint?.let(::File)
            ?: return DshPatchResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.PATCH_CONFLICT, "no previous patch version is recorded")
            )
        val filesRoot = File(point, "files")
        val relativeFiles = DshMaintenanceIo.listRelativeFiles(filesRoot).filterNot { it.endsWith("/") }
        if (relativeFiles.isEmpty()) {
            return DshPatchResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.PATCH_CONFLICT, "restore point holds no patch files")
            )
        }
        val layerRoot = File(homeDir, userLayerRelative)
        val steps = relativeFiles.map { relative ->
            val source = File(filesRoot, relative)
            DshMaintenanceStep(
                action = DshStepAction.INSTALL_FILE,
                target = File(layerRoot, relative),
                staging = source,
                expectedSha256 = DshMaintenanceIo.sha256(source)
            )
        }
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.PATCH_APPLY,
                requester = requester,
                quiescence = quiescence,
                targetRoot = homeDir,
                steps = steps,
                restorePoint = point,
                healthCheckRequired = true,
                detail = "rollback-patch=${current.activePatchId ?: ""}"
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> {
                stateStore.save(
                    current.copy(
                        activePatchId = null,
                        userDiffHash = hashLayer(File(homeDir, userLayerRelative)),
                        appliedFiles = emptyList()
                    )
                )
                DshPatchResult.RolledBack(outcome.transactionId)
            }

            is DshMaintenanceOutcome.RolledBack -> DshPatchResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshPatchResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshPatchResult.Rejected(outcome.failure)
        }
    }

    /** Removes only the user diff layer (moved aside, recoverable); user data stays untouched. */
    fun clearUserLayer(
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshPatchResult {
        val layerRoot = File(homeDir, userLayerRelative)
        if (!layerRoot.exists()) {
            return DshPatchResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.PATCH_CONFLICT, "no user patch layer is installed")
            )
        }
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.PATCH_APPLY,
                requester = requester,
                quiescence = quiescence,
                targetRoot = homeDir,
                steps = listOf(
                    DshMaintenanceStep(
                        action = DshStepAction.MOVE_ASIDE,
                        target = layerRoot
                    )
                ),
                healthCheckRequired = false,
                detail = "clear-user-patch"
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> {
                val current = stateStore.load()
                stateStore.save(current.copy(activePatchId = null, userDiffHash = "", appliedFiles = emptyList()))
                DshPatchResult.Cleared(outcome.transactionId)
            }

            is DshMaintenanceOutcome.RolledBack -> DshPatchResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshPatchResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshPatchResult.Rejected(outcome.failure)
        }
    }

    private fun forbidden(relativePath: String): DshMaintenanceFailure? {
        val lower = relativePath.lowercase()
        val segments = lower.split('/')
        val protectedRoots = listOf(
            "payload", "dshroot", "runtime", "node_modules", ".git",
            "sessions", "sessions-deleted", "attachments", "workspace", "config/credentials"
        )
        val firstSegment = segments.firstOrNull()
        if ((firstSegment != null && firstSegment in protectedRoots) ||
            protectedRoots.any { lower.startsWith("$it/") }
        ) {
            return DshMaintenanceFailure(
                DshMaintenanceError.PATCH_FORBIDDEN,
                "user patches cannot replace managed/upgrade or user-data trees"
            )
        }
        if (EXECUTABLE_SUFFIXES.any { lower.endsWith(it) }) {
            return DshMaintenanceFailure(
                DshMaintenanceError.PATCH_FORBIDDEN,
                "user patches cannot replace executable entries"
            )
        }
        if (ALLOWED_SUFFIXES.none { lower.endsWith(it) }) {
            return DshMaintenanceFailure(
                DshMaintenanceError.PATCH_FORBIDDEN,
                "user patch file type is outside the console source/config/resource scope"
            )
        }
        return null
    }

    /**
     * Configuration still changes behaviour, so a JSON patch is parsed and rejected when it carries
     * an execution-looking key. This is a semantic precheck, not blind replay.
     */
    private fun semanticCheck(file: DshPatchFile): DshMaintenanceFailure? {
        val lower = file.relativePath.lowercase()
        if (!lower.endsWith(".json") && !lower.endsWith(".yaml") && !lower.endsWith(".yml")) return null
        if (lower.endsWith(".json")) {
            val parsed = runCatching { JSONObject(file.content.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return DshMaintenanceFailure(DshMaintenanceError.PATCH_FORBIDDEN, "JSON patch is not valid JSON")
            if (containsExecutionKey(parsed, 0)) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.PATCH_FORBIDDEN,
                    "JSON patch contains an execution-like key"
                )
            }
        } else {
            val text = file.content.toString(Charsets.UTF_8)
            if (EXECUTION_KEY_WORDS.any { Regex("(?i)\\b${Regex.escape(it)}\\s*:").containsMatchIn(text) }) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.PATCH_FORBIDDEN,
                    "config patch contains an execution-like key"
                )
            }
        }
        return null
    }

    private fun containsExecutionKey(node: Any?, depth: Int): Boolean {
        if (depth > 16) return true
        return when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (EXECUTION_KEY_WORDS.any { it.equals(key, ignoreCase = true) }) return true
                    if (containsExecutionKey(node.opt(key), depth + 1)) return true
                }
                false
            }

            is JSONArray -> (0 until node.length()).any { containsExecutionKey(node.opt(it), depth + 1) }
            else -> false
        }
    }

    private fun hashLayer(root: File): String {
        val files = DshMaintenanceIo.listRelativeFiles(root).filterNot { it.endsWith("/") }
        if (files.isEmpty()) return ""
        val digestInput = files.joinToString("\n") { relative ->
            val file = File(root, relative)
            "$relative:${if (file.isFile) DshMaintenanceIo.sha256(file) else "dir"}"
        }
        return DshMaintenanceIo.sha256(digestInput.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val MAX_FILES = 200
        const val MAX_FILE_BYTES = 1024L * 1024L
        const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L
        val EXECUTABLE_SUFFIXES = listOf(".so", ".elf", ".dex", ".jar", ".apk", ".node", ".wasm", ".a")
        val ALLOWED_SUFFIXES = listOf(
            ".js", ".mjs", ".cjs", ".ts", ".css", ".html", ".json", ".md", ".txt", ".yaml", ".yml"
        )
        val EXECUTION_KEY_WORDS = listOf(
            "exec", "execute", "spawn", "shell", "command", "eval", "script", "preload", "require", "loader"
        )
    }
}

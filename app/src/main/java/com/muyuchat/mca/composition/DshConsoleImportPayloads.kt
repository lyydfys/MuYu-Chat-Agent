package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.maintenance.DshConsolePatchState
import com.muyuchat.core.dsh.maintenance.DshMaintenanceIo
import com.muyuchat.core.dsh.maintenance.DshPatchFile
import com.muyuchat.core.dsh.maintenance.DshPatchProposal
import com.muyuchat.core.dsh.maintenance.DshPatchScope
import org.json.JSONObject

/**
 * Strict decoder for the user-selected console patch document.
 *
 * The maintenance owner already validates a [DshPatchProposal] (paths, per-file and total size,
 * declared content hash, executable/upgrade-managed trees, execution-looking config keys) and
 * requires every base hash to match the current file before it writes anything. This decoder only
 * produces the proposal from the user's document: it never touches the file system and never
 * decides that a patch is safe.
 *
 * Document shape (all sizes bounded here, before the owner sees anything):
 * ```json
 * {
 *   "patchId": "user-console-1",
 *   "scope": "DSH_CONSOLE",
 *   "files": [
 *     { "path": "index.js", "base": "<sha256 of the current file or null>", "content": "..." }
 *   ]
 * }
 * ```
 *
 * `base` is the hash the author saw; omitting it means "the file must not exist yet". The patch is
 * therefore a real, reviewable diff rather than a blind overwrite.
 */
internal object DshConsolePatchPayload {

    sealed interface Decoded {
        data class Valid(val proposal: DshPatchProposal) : Decoded

        /** Rejected before the owner is called; [message] is fixed copy, never document content. */
        data class Invalid(val message: String) : Decoded
    }

    fun decode(payload: String?, current: DshConsolePatchState): Decoded {
        val text = payload?.trim().orEmpty()
        if (text.isEmpty()) return Decoded.Invalid("没有可应用的补丁文件")
        if (text.length > MAX_DOCUMENT_CHARS) return Decoded.Invalid("补丁文件过大")
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return Decoded.Invalid("补丁文件不是有效的 JSON")
        val scope = json.optString("scope", DshPatchScope.DSH_CONSOLE.name)
        if (!scope.equals(DshPatchScope.DSH_CONSOLE.name, ignoreCase = true)) {
            return Decoded.Invalid("补丁只能作用于 DSH 控制台")
        }
        val filesJson = json.optJSONArray("files") ?: return Decoded.Invalid("补丁没有文件列表")
        if (filesJson.length() == 0 || filesJson.length() > MAX_FILES) {
            return Decoded.Invalid("补丁文件数量超出上限")
        }
        val files = ArrayList<DshPatchFile>(filesJson.length())
        var totalBytes = 0
        for (index in 0 until filesJson.length()) {
            val entry = filesJson.optJSONObject(index)
                ?: return Decoded.Invalid("补丁文件条目格式不正确")
            val path = entry.optString("path").trim()
            if (!DshMaintenanceIo.isSafeRelativePath(path)) {
                return Decoded.Invalid("补丁包含不安全的路径")
            }
            val content = entry.optString("content")
            if (content.isEmpty()) return Decoded.Invalid("补丁文件内容为空")
            totalBytes += content.length
            if (content.length > MAX_FILE_CHARS || totalBytes > MAX_TOTAL_CHARS) {
                return Decoded.Invalid("补丁内容超出上限")
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            val base = entry.optString("base").trim().takeIf { it.isNotEmpty() }
            if (base != null && !DshMaintenanceIo.isSha256Hex(base)) {
                return Decoded.Invalid("补丁的基础哈希格式不正确")
            }
            files += DshPatchFile(
                relativePath = path,
                baseSha256 = base?.lowercase(),
                // The declared new hash is computed from the content the user actually supplied, so
                // the owner's content/hash check is meaningful instead of trusting the document.
                newSha256 = DshMaintenanceIo.sha256(bytes),
                content = bytes
            )
        }
        val hashInput = files.joinToString("\n") { "${it.relativePath}:${it.newSha256}" }
        val patchId = json.optString("patchId").trim().takeIf { it.isNotEmpty() }
            ?: ("user-" + DshMaintenanceIo.sha256(hashInput.toByteArray(Charsets.UTF_8)).take(16))
        return Decoded.Valid(
            DshPatchProposal(
                patchId = patchId,
                // Declared as authored against what is installed now: the per-file base hashes above
                // are the real conflict protection, and the owner re-checks them against disk.
                targetVersion = current.targetVersion,
                scope = DshPatchScope.DSH_CONSOLE,
                factoryBaseHash = current.factoryBaseHash,
                managedPatchHash = current.managedPatchHash,
                files = files
            )
        )
    }

    private const val MAX_FILES = 40
    private const val MAX_FILE_CHARS = 256 * 1024
    private const val MAX_TOTAL_CHARS = 1024 * 1024
    private const val MAX_DOCUMENT_CHARS = com.muyuchat.core.dsh.contract.console.DSH_MAINTENANCE_MAX_DOCUMENT_CHARS
}

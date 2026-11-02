package com.muyuchat.mca.presentation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.muyuchat.feature.agent.workspace.AgentDocumentRef
import com.muyuchat.feature.agent.workspace.AgentSkillAdminItem
import com.muyuchat.feature.agent.workspace.AgentSkillAdminListResult
import com.muyuchat.feature.agent.workspace.AgentSkillAdminOutcome
import com.muyuchat.feature.agent.workspace.AgentSkillAdminPort
import com.muyuchat.mca.agent.extensions.skills.AgentSafSkillTree
import com.muyuchat.mca.agent.extensions.skills.AgentSkillException
import com.muyuchat.mca.agent.extensions.skills.AgentSkillLimits
import com.muyuchat.mca.agent.extensions.skills.AgentSkillManager
import com.muyuchat.mca.agent.extensions.skills.AgentSkillManifest
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * K12 host adapter for the Agent workspace Skills surface. It owns the SAF stream/tree plumbing and
 * the package-size guard; all parsing/limits/persistence stay in [AgentSkillManager]. No exception
 * message from a provider or parser reaches the page — only typed safe outcomes do.
 */
class AgentSkillAdminService(
    context: Context,
    private val manager: AgentSkillManager
) : AgentSkillAdminPort {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val cacheDir: File = context.applicationContext.cacheDir

    override suspend fun list(): AgentSkillAdminListResult = withContext(Dispatchers.IO) {
        runCatching { AgentSkillAdminListResult.Loaded(manager.list().map(::toItem)) }
            .getOrElse { failure -> rejectedList(failure) }
    }

    override suspend fun importZip(source: AgentDocumentRef): AgentSkillAdminOutcome =
        withContext(Dispatchers.IO) {
            val spool = File(cacheDir, "skill-import-${UUID.randomUUID()}.zip")
            try {
                copyBounded(Uri.parse(source.value), spool)
                val manifest = manager.installZip(
                    sourceUri = source.value,
                    packageFile = spool,
                    enable = false
                )
                AgentSkillAdminOutcome.Imported(toItem(manifest))
            } catch (failure: Throwable) {
                rejected(failure)
            } finally {
                spool.delete()
            }
        }

    override suspend fun importTree(source: AgentDocumentRef): AgentSkillAdminOutcome =
        withContext(Dispatchers.IO) {
            try {
                val tree = AgentSafSkillTree(resolver, Uri.parse(source.value))
                val manifest = manager.installTree(
                    sourceUri = source.value,
                    root = tree.root(),
                    enable = false
                )
                AgentSkillAdminOutcome.Imported(toItem(manifest))
            } catch (failure: Throwable) {
                rejected(failure)
            }
        }

    override suspend fun setEnabled(skillId: String, enabled: Boolean): AgentSkillAdminOutcome =
        withContext(Dispatchers.IO) {
            try {
                val manifest = manager.setEnabled(skillId, enabled)
                if (enabled) AgentSkillAdminOutcome.Enabled(toItem(manifest))
                else AgentSkillAdminOutcome.Disabled(toItem(manifest))
            } catch (failure: Throwable) {
                rejected(failure)
            }
        }

    override suspend fun uninstall(skillId: String): AgentSkillAdminOutcome =
        withContext(Dispatchers.IO) {
            try {
                val result = manager.uninstall(skillId)
                AgentSkillAdminOutcome.Uninstalled(result.removed.id, result.removed.name)
            } catch (failure: Throwable) {
                rejected(failure)
            }
        }

    private fun copyBounded(uri: Uri, target: File) {
        val input = resolver.openInputStream(uri)
            ?: throw AgentSkillException(
                com.muyuchat.mca.agent.extensions.skills.AgentSkillError(
                    com.muyuchat.mca.agent.extensions.skills.AgentSkillErrorCode.STORE_FAILURE,
                    "无法读取所选 Skill 包。"
                )
            )
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > AgentSkillLimits.MAX_PACKAGE_BYTES) {
                        throw AgentSkillException(
                            com.muyuchat.mca.agent.extensions.skills.AgentSkillError(
                                com.muyuchat.mca.agent.extensions.skills.AgentSkillErrorCode.PACKAGE_TOO_LARGE,
                                "Skill 包超过大小上限。"
                            )
                        )
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun toItem(manifest: AgentSkillManifest): AgentSkillAdminItem = AgentSkillAdminItem(
        skillId = manifest.id,
        name = manifest.name,
        description = manifest.description,
        version = manifest.version,
        packageVersion = manifest.packageVersion,
        enabled = manifest.enabled,
        allowedToolCount = manifest.declaredToolPatterns.size
    )

    private fun rejected(failure: Throwable): AgentSkillAdminOutcome =
        if (failure is AgentSkillException) {
            AgentSkillAdminOutcome.Rejected(failure.error.code.name, failure.error.message)
        } else {
            AgentSkillAdminOutcome.Rejected("SKILL_ADMIN_FAILED", "Skills 操作失败，请重试。")
        }

    private fun rejectedList(failure: Throwable): AgentSkillAdminListResult =
        if (failure is AgentSkillException) {
            AgentSkillAdminListResult.Rejected(failure.error.code.name, failure.error.message)
        } else {
            AgentSkillAdminListResult.Rejected("SKILL_ADMIN_FAILED", "无法读取 Skills 列表。")
        }
}

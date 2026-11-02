package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-local Skills coordinator.
 *
 * It owns the one-at-a-time operation gate, the SAF hand-off through [AgentDocumentLauncher], and
 * the typed presentation of [AgentSkillAdminOutcome]. It does no import, hashing, persistence or
 * revocation itself: those belong to the K12 port behind [AgentSkillAdminPort].
 */
internal class AgentSkillsCoordinator(
    private val scope: CoroutineScope,
    private val port: AgentSkillAdminPort,
    private val documents: AgentDocumentLauncher
) {
    private val mutable = MutableStateFlow(AgentSkillsUiState())
    val state: StateFlow<AgentSkillsUiState> = mutable.asStateFlow()

    fun open() {
        if (mutable.value.visible) return
        val shouldLoad = mutable.value.items.isEmpty() &&
            !mutable.value.loading &&
            mutable.value.busy == null
        mutable.update {
            it.copy(
                visible = true,
                loading = shouldLoad || it.loading,
                uninstallConfirmation = null
            )
        }
        if (shouldLoad) scope.launch { refreshNow() }
    }

    fun close() {
        mutable.update { it.copy(visible = false, uninstallConfirmation = null) }
    }

    fun refresh() {
        if (mutable.value.busy != null) return
        scope.launch { refreshNow() }
    }

    fun requestImportZip() {
        if (mutable.value.busy != null) return
        documents.openDocument { source ->
            if (source == null) {
                setNotice("已取消导入")
            } else {
                scope.launch { importZipNow(source) }
            }
        }
    }

    fun requestImportTree() {
        if (mutable.value.busy != null) return
        documents.openTree { source ->
            if (source == null) {
                setNotice("已取消导入")
            } else {
                scope.launch { importTreeNow(source) }
            }
        }
    }

    fun requestSetEnabled(skillId: String, enabled: Boolean) {
        if (mutable.value.busy != null) return
        val item = mutable.value.items.firstOrNull { it.skillId == skillId } ?: return
        if (enabled && mutable.value.limitReached) {
            setNotice("最多同时启用 ${AgentSkillAdminLimits.MAX_ENABLED_SKILLS} 个 Skill，请先停用其他 Skill。")
            return
        }
        if (item.enabled == enabled) return
        scope.launch { setEnabledNow(skillId, enabled) }
    }

    fun requestUninstall(skillId: String) {
        if (mutable.value.busy != null) return
        val item = mutable.value.items.firstOrNull { it.skillId == skillId } ?: return
        mutable.update {
            it.copy(
                uninstallConfirmation = AgentSkillUninstallConfirmationUi(item.skillId, item.name),
                notice = null
            )
        }
    }

    fun confirmUninstall() {
        val confirmation = mutable.value.uninstallConfirmation ?: return
        if (mutable.value.busy != null) return
        mutable.update { it.copy(uninstallConfirmation = null) }
        scope.launch { uninstallNow(confirmation.skillId) }
    }

    fun cancelUninstall() {
        mutable.update { it.copy(uninstallConfirmation = null) }
    }

    fun dismissStatus() {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(error = null, notice = null) }
    }

    // ------------------------------------------------------------ deterministic suspend entry points

    internal suspend fun refreshNow() {
        if (mutable.value.busy != null) return
        begin(AgentSkillOperationKind.LISTING)
        val result = try {
            port.list()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentSkillAdminListResult.Rejected(
                "SKILL_ADMIN_FAILED",
                "Skills 列表暂时无法载入，请稍后重试。"
            )
        }
        when (result) {
            is AgentSkillAdminListResult.Loaded -> mutable.update {
                it.copy(
                    loading = false,
                    items = result.items.map(::projectSkillItem).sortedBy { item -> item.name },
                    busy = null,
                    error = null
                )
            }

            is AgentSkillAdminListResult.Rejected -> mutable.update {
                it.copy(
                    loading = false,
                    busy = null,
                    error = AgentSkillAdminErrorUi(result.code, result.safeMessage)
                )
            }
        }
    }

    internal suspend fun importZipNow(source: AgentDocumentRef) {
        if (mutable.value.busy != null) return
        begin(AgentSkillOperationKind.IMPORT_ZIP)
        applyImport(callImport { port.importZip(source) })
    }

    internal suspend fun importTreeNow(source: AgentDocumentRef) {
        if (mutable.value.busy != null) return
        begin(AgentSkillOperationKind.IMPORT_TREE)
        applyImport(callImport { port.importTree(source) })
    }

    internal suspend fun setEnabledNow(skillId: String, enabled: Boolean) {
        if (mutable.value.busy != null) return
        val item = mutable.value.items.firstOrNull { it.skillId == skillId } ?: return
        if (enabled && mutable.value.limitReached) {
            setNotice("最多同时启用 ${AgentSkillAdminLimits.MAX_ENABLED_SKILLS} 个 Skill，请先停用其他 Skill。")
            return
        }
        begin(AgentSkillOperationKind.TOGGLE)
        val outcome = try {
            port.setEnabled(skillId, enabled)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            rejected("SKILL_ADMIN_FAILED", "Skill 状态未能更新，请稍后重试。")
        }
        when (outcome) {
            is AgentSkillAdminOutcome.Enabled -> {
                replaceItem(outcome.item)
                mutable.update {
                    it.copy(busy = null, error = null, notice = "已启用 ${outcome.item.name}")
                }
            }

            is AgentSkillAdminOutcome.Disabled -> {
                replaceItem(outcome.item)
                mutable.update {
                    it.copy(
                        busy = null,
                        error = null,
                        notice = "已停用 ${outcome.item.name}；待审批已失效，运行中任务保留其快照。"
                    )
                }
            }

            is AgentSkillAdminOutcome.Rejected -> finishRejected(outcome)
            else -> finishUnexpected()
        }
    }

    internal suspend fun uninstallNow(skillId: String) {
        if (mutable.value.busy != null) return
        if (mutable.value.items.none { it.skillId == skillId }) return
        begin(AgentSkillOperationKind.UNINSTALL)
        val outcome = try {
            port.uninstall(skillId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            rejected("SKILL_ADMIN_FAILED", "Skill 未能卸载，请稍后重试。")
        }
        when (outcome) {
            is AgentSkillAdminOutcome.Uninstalled -> mutable.update { current ->
                current.copy(
                    items = current.items.filterNot { it.skillId == outcome.skillId },
                    busy = null,
                    error = null,
                    notice = "已卸载 ${outcome.name}；运行中的任务会保留其 Skill 快照。"
                )
            }

            is AgentSkillAdminOutcome.Rejected -> finishRejected(outcome)
            else -> finishUnexpected()
        }
    }

    // ---------------------------------------------------------------- outcome projection

    private suspend fun callImport(block: suspend () -> AgentSkillAdminOutcome): AgentSkillAdminOutcome =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            rejected("SKILL_ADMIN_FAILED", "Skill 未能导入，请检查包内容后重试。")
        }

    private fun applyImport(outcome: AgentSkillAdminOutcome) {
        when (outcome) {
            is AgentSkillAdminOutcome.Imported -> {
                upsertItem(outcome.item)
                mutable.update {
                    it.copy(
                        loading = false,
                        busy = null,
                        error = null,
                        notice = "已导入 Skill：${outcome.item.name}"
                    )
                }
            }

            is AgentSkillAdminOutcome.Updated -> {
                upsertItem(outcome.item)
                mutable.update {
                    it.copy(
                        loading = false,
                        busy = null,
                        error = null,
                        notice = "已更新 Skill：${outcome.item.name}"
                    )
                }
            }

            is AgentSkillAdminOutcome.Rejected -> finishRejected(outcome)
            else -> finishUnexpected()
        }
    }

    private fun begin(kind: AgentSkillOperationKind) {
        mutable.update {
            it.copy(
                busy = kind,
                loading = kind == AgentSkillOperationKind.LISTING || it.loading,
                error = null,
                notice = null,
                uninstallConfirmation = null
            )
        }
    }

    private fun finishRejected(outcome: AgentSkillAdminOutcome.Rejected) {
        mutable.update {
            it.copy(
                loading = false,
                busy = null,
                error = AgentSkillAdminErrorUi(outcome.code, outcome.safeMessage)
            )
        }
    }

    private fun finishUnexpected() {
        mutable.update {
            it.copy(
                loading = false,
                busy = null,
                error = AgentSkillAdminErrorUi(
                    "SKILL_ADMIN_UNEXPECTED",
                    "Skills 操作返回了无法识别的结果，请稍后重试。"
                )
            )
        }
    }

    private fun replaceItem(item: AgentSkillAdminItem) {
        val projected = projectSkillItem(item)
        mutable.update { current ->
            current.copy(
                items = current.items
                    .map { if (it.skillId == projected.skillId) projected else it }
                    .sortedBy { it.name }
            )
        }
    }

    private fun upsertItem(item: AgentSkillAdminItem) {
        val projected = projectSkillItem(item)
        mutable.update { current ->
            val replaced = current.items.filterNot { it.skillId == projected.skillId } + projected
            current.copy(items = replaced.sortedBy { it.name })
        }
    }

    private fun setNotice(message: String) {
        mutable.update { it.copy(notice = message, error = null) }
    }

    private fun rejected(code: String, safeMessage: String): AgentSkillAdminOutcome =
        AgentSkillAdminOutcome.Rejected(code, safeMessage)
}

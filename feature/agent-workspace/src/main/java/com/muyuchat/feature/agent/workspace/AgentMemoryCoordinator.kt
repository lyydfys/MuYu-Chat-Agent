package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-local memory-spaces coordinator.
 *
 * It lists spaces and keeps the local selection marker; it never loads memory content and never
 * invents a space. The selection is page-local interaction state and does not become a second
 * source for task/run policy: a task's frozen space selection is owned by the runtime contract.
 */
internal class AgentMemoryCoordinator(
    private val scope: CoroutineScope,
    private val port: AgentMemoryAdminPort
) {
    private val mutable = MutableStateFlow(AgentMemoryUiState())
    val state: StateFlow<AgentMemoryUiState> = mutable.asStateFlow()

    fun open() {
        if (mutable.value.visible) return
        val shouldLoad = mutable.value.spaces.isEmpty() && !mutable.value.loading
        mutable.update {
            it.copy(visible = true, loading = shouldLoad || it.loading, notice = null)
        }
        if (shouldLoad) scope.launch { refreshNow() }
    }

    fun close() {
        // The selection marker only describes this page visit; a task's real selection is frozen
        // by the runtime, so reopening must not imply a policy choice was made.
        mutable.update { it.copy(visible = false, selectedSpaceId = null) }
    }

    fun refresh() {
        if (mutable.value.loading) return
        mutable.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch { refreshNow() }
    }

    /** Selecting only inspects the already-loaded row; an unknown id is ignored. */
    fun selectSpace(spaceId: String) {
        val space = mutable.value.spaces.firstOrNull { it.spaceId == spaceId } ?: return
        mutable.update {
            it.copy(
                selectedSpaceId = space.spaceId,
                notice = "已选择 ${space.displayName}",
                error = null
            )
        }
    }

    fun dismissStatus() {
        mutable.update { it.copy(error = null, notice = null) }
    }

    internal suspend fun refreshNow() {
        mutable.update { it.copy(loading = true, error = null) }
        val result = try {
            port.list()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentMemoryListResult.Rejected(
                code = "MEMORY_ADMIN_FAILED",
                safeMessage = "记忆空间列表暂时无法载入，请稍后重试。"
            )
        }
        when (result) {
            is AgentMemoryListResult.Loaded -> {
                val projected = projectMemoryState(
                    AgentMemoryProjectionInput(
                        visible = mutable.value.visible,
                        loading = false,
                        spaces = result.spaces
                    )
                )
                mutable.update { current ->
                    // A deleted/renamed space must not leave a stale selected id behind.
                    val stillExists = projected.spaces.any { it.spaceId == current.selectedSpaceId }
                    current.copy(
                        spaces = projected.spaces,
                        selectedSpaceId = if (stillExists) current.selectedSpaceId else null,
                        loading = false,
                        error = null
                    )
                }
            }

            is AgentMemoryListResult.Rejected -> mutable.update {
                it.copy(
                    loading = false,
                    error = AgentMemoryErrorUi(result.code, result.safeMessage)
                )
            }
        }
    }
}

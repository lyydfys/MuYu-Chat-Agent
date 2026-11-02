package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-local schedule dialog for one recipe.
 *
 * It owns the open dialog, the bounded plan draft and the load/save/remove calls. It never starts a
 * run, never installs a wake and never computes a next-run instant: [AgentWorkflowSchedulePort] is
 * the only writer, and a saved row reports whether the platform wake could actually be armed.
 */
internal class AgentWorkflowScheduleCoordinator(
    private val scope: CoroutineScope,
    private val port: AgentWorkflowSchedulePort,
    private val zoneProvider: () -> String = { java.util.TimeZone.getDefault().id }
) {
    private val mutable = MutableStateFlow(AgentWorkflowScheduleUiState())
    val state: StateFlow<AgentWorkflowScheduleUiState> = mutable.asStateFlow()

    private var loadToken = 0L

    fun open(recipeId: String, recipeName: String) {
        if (recipeId.isBlank()) return
        if (mutable.value.busy) return
        val alreadyOpen = mutable.value.visible && mutable.value.recipeId == recipeId
        if (alreadyOpen) return
        loadToken++
        mutable.update {
            it.copy(
                visible = true,
                recipeId = recipeId,
                recipeName = recipeName.take(AgentWorkflowLimits.MAX_NAME_CHARS),
                loading = true,
                busy = false,
                schedule = null,
                draft = null,
                error = null,
                notice = null
            )
        }
        requestLoad()
    }

    fun close() {
        if (mutable.value.busy) return
        loadToken++
        mutable.value = AgentWorkflowScheduleUiState()
    }

    fun retry() {
        val current = mutable.value
        if (!current.canRetry) return
        mutable.update { it.copy(loading = true, error = null) }
        requestLoad()
    }

    fun updateKind(kind: AgentWorkflowScheduleKind) {
        updateDraft { draft ->
            val weekdays = if (kind == AgentWorkflowScheduleKind.WEEKLY) {
                draft.weekdays.ifEmpty { setOf(AgentWorkflowWeekday.MONDAY) }
            } else {
                draft.weekdays
            }
            draft.copy(kind = kind, weekdays = weekdays, validationMessage = null)
        }
    }

    fun updateMinutes(text: String) {
        val bounded = text.filter { it.isDigit() }.take(5)
        updateDraft { it.copy(everyMinutesText = bounded, validationMessage = null) }
    }

    fun updateTime(text: String) {
        val bounded = text.filter { it.isDigit() || it == ':' }.take(5)
        updateDraft { it.copy(timeText = bounded, validationMessage = null) }
    }

    fun toggleWeekday(day: AgentWorkflowWeekday) {
        updateDraft { draft ->
            val days = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day
            draft.copy(weekdays = days, validationMessage = null)
        }
    }

    fun updateEnabled(enabled: Boolean) {
        updateDraft { it.copy(enabled = enabled, validationMessage = null) }
    }

    fun save() {
        val current = mutable.value
        val recipeId = current.recipeId ?: return
        val draft = current.draft ?: return
        if (!current.canSave) return
        val validation = scheduleDraftValidationError(draft)
        if (validation != null) {
            mutable.update {
                it.copy(draft = draft.copy(validationMessage = validation))
            }
            return
        }
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch { saveNow(recipeId, draft) }
    }

    fun remove() {
        val current = mutable.value
        val recipeId = current.recipeId ?: return
        if (!current.canDelete) return
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch { removeNow(recipeId) }
    }

    fun dismissStatus() {
        if (mutable.value.busy) return
        mutable.update { it.copy(error = null, notice = null) }
    }

    // ------------------------------------------------------------ deterministic suspend entry points

    internal suspend fun loadNow(token: Long) {
        val recipeId = mutable.value.recipeId ?: return
        val result = try {
            port.list(recipeId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowScheduleListResult.Rejected(
                code = "WORKFLOW_SCHEDULE_FAILED",
                safeMessage = "定时计划暂时无法载入，请稍后重试。"
            )
        }
        if (token != loadToken || mutable.value.recipeId != recipeId) return
        when (result) {
            is AgentWorkflowScheduleListResult.Loaded -> {
                val schedule = result.schedules.firstOrNull { it.recipeId == recipeId }
                mutable.update {
                    it.copy(
                        loading = false,
                        schedule = schedule,
                        draft = schedule?.let(::scheduleDraftFrom)
                            ?: AgentWorkflowScheduleDraftUi(zoneId = zoneProvider()),
                        error = null
                    )
                }
            }

            is AgentWorkflowScheduleListResult.Rejected -> mutable.update {
                it.copy(
                    loading = false,
                    schedule = null,
                    draft = null,
                    error = AgentWorkflowErrorUi(result.code, result.safeMessage)
                )
            }
        }
    }

    internal suspend fun saveNow(recipeId: String, draft: AgentWorkflowScheduleDraftUi) {
        val outcome = try {
            port.save(scheduleCommandFrom(recipeId, draft))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowScheduleOutcome.Rejected(
                code = "WORKFLOW_SCHEDULE_FAILED",
                safeMessage = "定时计划未能保存，请稍后重试。"
            )
        }
        when (outcome) {
            is AgentWorkflowScheduleOutcome.Saved -> mutable.update {
                it.copy(
                    busy = false,
                    schedule = outcome.schedule,
                    draft = scheduleDraftFrom(outcome.schedule),
                    error = null,
                    notice = if (outcome.wakeArmed) {
                        "定时计划已保存，系统唤醒已安装。"
                    } else {
                        "定时计划已保存，但系统未能安装唤醒；计划可能延迟。"
                    }
                )
            }

            is AgentWorkflowScheduleOutcome.Rejected -> mutable.update {
                it.copy(
                    busy = false,
                    error = AgentWorkflowErrorUi(outcome.code, outcome.safeMessage),
                    draft = it.draft?.copy(validationMessage = outcome.safeMessage)
                )
            }

            is AgentWorkflowScheduleOutcome.Removed -> mutable.update {
                it.copy(busy = false)
            }
        }
    }

    internal suspend fun removeNow(recipeId: String) {
        val outcome = try {
            port.remove(recipeId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowScheduleOutcome.Rejected(
                code = "WORKFLOW_SCHEDULE_FAILED",
                safeMessage = "定时计划未能删除，请稍后重试。"
            )
        }
        when (outcome) {
            is AgentWorkflowScheduleOutcome.Removed -> mutable.update {
                it.copy(
                    busy = false,
                    schedule = null,
                    draft = AgentWorkflowScheduleDraftUi(zoneId = zoneProvider()),
                    error = null,
                    notice = if (outcome.removed) "定时计划已删除。" else "没有可删除的定时计划。"
                )
            }

            is AgentWorkflowScheduleOutcome.Rejected -> mutable.update {
                it.copy(
                    busy = false,
                    error = AgentWorkflowErrorUi(outcome.code, outcome.safeMessage)
                )
            }

            is AgentWorkflowScheduleOutcome.Saved -> mutable.update { it.copy(busy = false) }
        }
    }

    private fun requestLoad() {
        val token = ++loadToken
        scope.launch { loadNow(token) }
    }

    private fun updateDraft(transform: (AgentWorkflowScheduleDraftUi) -> AgentWorkflowScheduleDraftUi) {
        if (mutable.value.busy || mutable.value.loading) return
        val draft = mutable.value.draft ?: return
        mutable.update { it.copy(draft = transform(draft), error = null, notice = null) }
    }
}

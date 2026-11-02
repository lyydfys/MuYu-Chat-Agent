package com.muyuchat.mca.presentation

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleCommandUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleKind
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleListResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleOutcome as FeatureScheduleOutcome
import com.muyuchat.feature.agent.workspace.AgentWorkflowSchedulePort
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowWeekday
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipeStore
import com.muyuchat.mca.agent.workflow.RecipeWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.WorkflowAgentTaskPlan
import com.muyuchat.mca.agent.workflow.WorkflowScheduleCommand
import com.muyuchat.mca.agent.workflow.WorkflowScheduleErrorCodes
import com.muyuchat.mca.agent.workflow.WorkflowScheduleOutcome
import com.muyuchat.mca.agent.workflow.WorkflowScheduleRecord
import com.muyuchat.mca.agent.workflow.WorkflowScheduleService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-side adapter for the gated recipe schedule surface.
 *
 * The recipe stays the single editable source; this adapter only maps one recipe to its frozen
 * `recipe-<id>` definition version and delegates persistence and wake installation to
 * [WorkflowScheduleService], which remains the only schedule writer. The model is the user's
 * already-loaded local binding, frozen into the row at save time: a wake after process death can
 * never fall back to the chat page's selection. Unattended runs stay local-only.
 */
class AgentWorkflowScheduleService(
    private val recipes: AgentWorkflowRecipeStore,
    private val schedules: WorkflowScheduleService,
    private val activeBinding: () -> AgentModelBinding?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AgentWorkflowSchedulePort {

    override suspend fun list(recipeId: String): AgentWorkflowScheduleListResult =
        withContext(ioDispatcher) {
            if (recipes.latest(recipeId) == null) {
                return@withContext AgentWorkflowScheduleListResult.Rejected(
                    CODE_RECIPE_NOT_FOUND,
                    "找不到要定时的配方；请刷新后重试。"
                )
            }
            val rows = try {
                schedules.list().filter { it.definitionId == definitionId(recipeId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext AgentWorkflowScheduleListResult.Rejected(
                    CODE_STORE_FAILED,
                    "定时计划暂时无法载入，请稍后重试。"
                )
            }
            val newest = rows.maxByOrNull { it.version }
            AgentWorkflowScheduleListResult.Loaded(
                newest?.let { listOf(it.toUi(recipeId)) } ?: emptyList()
            )
        }

    override suspend fun save(command: AgentWorkflowScheduleCommandUi): FeatureScheduleOutcome =
        withContext(ioDispatcher) {
            val record = try {
                recipes.latest(command.recipeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            } ?: return@withContext rejected(
                CODE_RECIPE_NOT_FOUND,
                "找不到要定时的配方；请刷新后重试。"
            )
            if (!record.active) {
                return@withContext rejected(
                    CODE_RECIPE_INACTIVE,
                    "该配方已停用，无法设置定时计划。"
                )
            }
            val schedule = command.toDomainSchedule() ?: return@withContext rejected(
                WorkflowScheduleErrorCodes.PLAN_INVALID,
                "定时计划参数无效；请检查间隔或时间。"
            )
            val binding = activeBinding() ?: return@withContext rejected(
                CODE_MODEL_NOT_LOADED,
                "请先在聊天页加载一个本地模型。"
            )
            val id = definitionId(command.recipeId)
            val outcome = try {
                schedules.upsert(
                    WorkflowScheduleCommand(
                        definitionId = id,
                        version = record.recipe.version,
                        schedule = schedule,
                        enabled = command.enabled,
                        agentTask = WorkflowAgentTaskPlan(
                            modelBinding = binding,
                            policy = AgentRunPolicy(),
                            displayTitle = record.recipe.title
                        )
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext rejected(CODE_STORE_FAILED, "定时计划未能保存，请稍后重试。")
            }
            when (outcome) {
                is WorkflowScheduleOutcome.Saved -> {
                    // One recipe owns exactly one plan: rows pinned to an older version are stale
                    // and would otherwise fire the same recipe twice.
                    dropStaleVersions(id, keep = outcome.record.version)
                    FeatureScheduleOutcome.Saved(
                        schedule = outcome.record.toUi(command.recipeId),
                        wakeArmed = outcome.wakeArmed
                    )
                }

                is WorkflowScheduleOutcome.Rejected -> rejected(
                    outcome.code,
                    safeMessageFor(outcome.code, outcome.safeMessage)
                )

                is WorkflowScheduleOutcome.Removed -> FeatureScheduleOutcome.Removed(
                    recipeId = command.recipeId,
                    removed = outcome.removed
                )
            }
        }

    override suspend fun remove(recipeId: String): FeatureScheduleOutcome = withContext(ioDispatcher) {
        val id = definitionId(recipeId)
        val rows = try {
            schedules.list().filter { it.definitionId == id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withContext rejected(CODE_STORE_FAILED, "定时计划未能删除，请稍后重试。")
        }
        if (rows.isEmpty()) {
            return@withContext FeatureScheduleOutcome.Removed(recipeId, removed = false)
        }
        var removed = false
        for (row in rows) {
            val outcome = try {
                schedules.remove(id, row.version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext rejected(CODE_STORE_FAILED, "定时计划未能删除，请稍后重试。")
            }
            when (outcome) {
                is WorkflowScheduleOutcome.Removed -> if (outcome.removed) removed = true
                is WorkflowScheduleOutcome.Rejected -> return@withContext rejected(
                    outcome.code,
                    safeMessageFor(outcome.code, outcome.safeMessage)
                )

                is WorkflowScheduleOutcome.Saved -> Unit
            }
        }
        FeatureScheduleOutcome.Removed(recipeId, removed)
    }

    private suspend fun dropStaleVersions(definitionId: String, keep: Long) {
        val stale = try {
            schedules.list().filter { it.definitionId == definitionId && it.version != keep }
        } catch (_: Throwable) {
            return
        }
        stale.forEach { row ->
            try {
                schedules.remove(definitionId, row.version)
            } catch (_: Throwable) {
                // A leftover row of an older version is stale, never a second live plan: the
                // newest save is already durable and the next save retries this cleanup.
            }
        }
    }

    private fun AgentWorkflowScheduleCommandUi.toDomainSchedule(): WorkflowSchedule? {
        return try {
            when (kind) {
                AgentWorkflowScheduleKind.INTERVAL -> WorkflowSchedule.Interval(everyMinutes)
                AgentWorkflowScheduleKind.DAILY -> {
                    val time = parseTime(timeLabel) ?: return null
                    WorkflowSchedule.Daily(time, ZoneId.of(zoneId))
                }

                AgentWorkflowScheduleKind.WEEKLY -> {
                    val time = parseTime(timeLabel) ?: return null
                    WorkflowSchedule.Weekly(
                        days = weekdays.map { it.toDayOfWeek() }.toSet(),
                        time = time,
                        zone = ZoneId.of(zoneId)
                    )
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    private fun WorkflowScheduleRecord.toUi(recipeId: String): AgentWorkflowScheduleUi {
        val uiKind = when (schedule) {
            is WorkflowSchedule.Interval -> AgentWorkflowScheduleKind.INTERVAL
            is WorkflowSchedule.Daily -> AgentWorkflowScheduleKind.DAILY
            is WorkflowSchedule.Weekly -> AgentWorkflowScheduleKind.WEEKLY
        }
        return AgentWorkflowScheduleUi(
            recipeId = recipeId,
            definitionVersion = version,
            kind = uiKind,
            everyMinutes = (schedule as? WorkflowSchedule.Interval)?.everyMinutes,
            timeLabel = when (val plan = schedule) {
                is WorkflowSchedule.Daily -> formatTime(plan.time)
                is WorkflowSchedule.Weekly -> formatTime(plan.time)
                is WorkflowSchedule.Interval -> null
            },
            weekdays = (schedule as? WorkflowSchedule.Weekly)
                ?.days
                ?.mapNotNull { it.toUiWeekday() }
                ?.toSet()
                ?: emptySet(),
            zoneId = when (val plan = schedule) {
                is WorkflowSchedule.Interval -> ZoneId.systemDefault().id
                is WorkflowSchedule.Daily -> plan.zone.id
                is WorkflowSchedule.Weekly -> plan.zone.id
            },
            enabled = enabled,
            lastDeliveredLabel = lastDeliveredMillis?.let(::formatDelivered)
        )
    }

    private fun rejected(code: String, message: String): FeatureScheduleOutcome =
        FeatureScheduleOutcome.Rejected(code, message)

    private fun safeMessageFor(code: String, fallback: String): String = when (code) {
        WorkflowScheduleErrorCodes.DEFINITION_NOT_FOUND ->
            "该配方版本已更新，无法保存此计划；请先刷新配方再试。"

        WorkflowScheduleErrorCodes.PLAN_INVALID -> "定时计划参数无效；请检查间隔或时间。"
        WorkflowScheduleErrorCodes.STORE_FAILED -> "定时计划未能保存，请稍后重试。"
        else -> fallback
    }

    private fun AgentWorkflowWeekday.toDayOfWeek(): DayOfWeek = when (this) {
        AgentWorkflowWeekday.MONDAY -> DayOfWeek.MONDAY
        AgentWorkflowWeekday.TUESDAY -> DayOfWeek.TUESDAY
        AgentWorkflowWeekday.WEDNESDAY -> DayOfWeek.WEDNESDAY
        AgentWorkflowWeekday.THURSDAY -> DayOfWeek.THURSDAY
        AgentWorkflowWeekday.FRIDAY -> DayOfWeek.FRIDAY
        AgentWorkflowWeekday.SATURDAY -> DayOfWeek.SATURDAY
        AgentWorkflowWeekday.SUNDAY -> DayOfWeek.SUNDAY
    }

    private fun DayOfWeek.toUiWeekday(): AgentWorkflowWeekday? = when (this) {
        DayOfWeek.MONDAY -> AgentWorkflowWeekday.MONDAY
        DayOfWeek.TUESDAY -> AgentWorkflowWeekday.TUESDAY
        DayOfWeek.WEDNESDAY -> AgentWorkflowWeekday.WEDNESDAY
        DayOfWeek.THURSDAY -> AgentWorkflowWeekday.THURSDAY
        DayOfWeek.FRIDAY -> AgentWorkflowWeekday.FRIDAY
        DayOfWeek.SATURDAY -> AgentWorkflowWeekday.SATURDAY
        DayOfWeek.SUNDAY -> AgentWorkflowWeekday.SUNDAY
    }

    companion object {
        private const val CODE_RECIPE_NOT_FOUND = "WORKFLOW_SCHEDULE_RECIPE_NOT_FOUND"
        private const val CODE_RECIPE_INACTIVE = "WORKFLOW_SCHEDULE_RECIPE_INACTIVE"
        private const val CODE_MODEL_NOT_LOADED = "WORKFLOW_MODEL_NOT_LOADED"
        private const val CODE_STORE_FAILED = "WORKFLOW_SCHEDULE_STORE_FAILED"

        private val DELIVERED_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm")

        internal fun definitionId(recipeId: String): String =
            RecipeWorkflowDefinitionStore.definitionId(recipeId)

        /** Accepts `H:mm` and `HH:mm`; the host validates again before persisting. */
        internal fun parseTime(text: String): LocalTime? {
            val parts = text.trim().split(':')
            if (parts.size != 2 || parts[0].isEmpty() || parts[1].length != 2) return null
            return runCatching {
                LocalTime.of(parts[0].toInt(), parts[1].toInt())
            }.getOrNull()
        }

        internal fun formatTime(time: LocalTime): String =
            time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')

        internal fun formatDelivered(millis: Long): String =
            Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .format(DELIVERED_FORMAT)
    }
}

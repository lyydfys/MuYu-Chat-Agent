package com.muyuchat.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaChip

/**
 * The three approval tiers as the settings page shows them.
 *
 * This module cannot import `:app` (architecture Rule 14), and the persisted document is owned by
 * the app-side `AgentToolApprovalPolicyStore`. So the feature side carries only the *presentation*
 * identity — a stable [id] that both sides spell identically — plus the two sentences a user needs
 * to choose. The mapping from [id] to the real policy object lives in the app module.
 *
 * [id] values are wire values: renaming one would silently reset every install to the default, so
 * they are asserted by a unit test rather than left to convention.
 */
enum class AgentApprovalPresetUi(
    val id: String,
    val title: String,
    val description: String
) {
    /**
     * Every tool asks before it runs. This is the app-wide default and matches the behaviour of
     * every build that predates the policy, so an existing install sees no change until it picks
     * something else.
     */
    CAUTIOUS(
        id = "cautious",
        title = "谨慎（默认）",
        description = "每次工具调用都先询问；只读工具自动放行，因为读没有可批准的动作。"
    ),

    /**
     * Runs everything except actions that can cost money or reach outside the app: screen capture,
     * network access, terminal commands and any tool declaring an external side effect.
     */
    ASK_MONEY_ONLY(
        id = "ask_money_only",
        title = "仅确认花钱操作",
        description = "只读与常规工具直接执行；截图、联网、命令这类可能产生费用或触达外部的动作仍需确认。"
    ),

    /** Never asks. Only for tasks where the user fully trusts the outcome. */
    AUTONOMOUS(
        id = "autonomous",
        title = "全自动",
        description = "除危险操作外不再询问，包括支付与外部动作；只在完全信任的任务里使用。"
    );

    companion object {
        /** Default shown when the stored document names an unknown or missing preset. */
        val DEFAULT: AgentApprovalPresetUi = CAUTIOUS

        /** Total mapping from a stored id to a tier; never throws, never returns null. */
        fun of(id: String?): AgentApprovalPresetUi =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The "Agent 工具审批" card: one chip per tier plus the selected tier's explanation.
 *
 * Chips are used instead of a segmented control or radio group because the descriptions differ in
 * length and `FlowRow` keeps all three readable at large font scales, where a fixed-width row of
 * radios would clip the labels.
 */
@Composable
fun AgentApprovalSettingsCard(
    selected: AgentApprovalPresetUi,
    onSelect: (AgentApprovalPresetUi) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Agent 工具审批",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "决定 Agent 调用工具前是否需要你确认。改动立即生效，下一轮运行就会使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgentApprovalPresetUi.entries.forEach { preset ->
                    McaChip(
                        selected = preset == selected,
                        onClick = { onSelect(preset) },
                        label = { Text(preset.title) },
                        modifier = Modifier.semantics {
                            contentDescription = "审批档位：${preset.title}"
                        }
                    )
                }
            }
            Text(
                text = selected.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selected == AgentApprovalPresetUi.AUTONOMOUS) {
                Text(
                    text = "全自动会跳过所有确认，包括可能产生费用的操作；请确认任务来源可信。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

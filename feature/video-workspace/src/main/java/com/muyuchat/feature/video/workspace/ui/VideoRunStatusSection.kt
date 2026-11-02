package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.video.contract.VideoRunProjection
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.feature.video.workspace.VideoWorkspaceViewModel

/**
 * Real run status of the video owner (V1-05). Shows the active projection's state, stage, step
 * progress, resource state and error verbatim. Step percentages exist only when the owner
 * reported both step and total; the unwired state keeps the original honest copy.
 */
@Composable
internal fun VideoRunStatusSection(viewModel: VideoWorkspaceViewModel) {
    val activeRun by viewModel.activeRun.collectAsState()
    val runsError by viewModel.runsError.collectAsState()
    val retryNotice by viewModel.retryNotice.collectAsState()
    Column(Modifier.padding(top = 16.dp).testTag("video.run_status")) {
        Text(
            "输出预览",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        when {
            !viewModel.executionWired -> Text(
                "本地 Wan2.2 生成链尚未接入：可以编辑工作流草稿；执行能力接入后此处显示真实运行状态。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            runsError != null -> {
                Text(
                    "运行记录读取失败：${runsError.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                A11yLabeledControl(
                    contentDescription = "重试读取运行记录",
                    onClick = viewModel::refreshRuns,
                ) {
                    McaButton(
                        onClick = viewModel::refreshRuns,
                        style = McaButtonStyle.Outlined,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("video.run_retry"),
                    ) {
                        Text("重试")
                    }
                }
            }

            activeRun == null -> Text(
                "尚未运行任务。点击\"生成\"提交当前草稿的冻结快照。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> VideoRunStatusCard(
                run = activeRun!!,
                retryNotice = retryNotice,
                onCancel = { viewModel.requestCancel(activeRun!!.runId) },
                onRetry = { viewModel.retry(activeRun!!.runId) },
            )
        }
    }
}

@Composable
private fun VideoRunStatusCard(
    run: VideoRunProjection,
    retryNotice: String?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val stateLabel = videoRunStateLabel(run.state)
    val progress = videoRunProgress(run.step, run.stepTotal)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = McaShape.Card,
        border = BorderStroke(McaHairline.Width, McaHairline.default()),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("video.run_card")
            .semantics {
                contentDescription = buildString {
                    append("运行状态 $stateLabel")
                    append("，阶段 ${videoRunStageLabel(run.stage)}")
                    progress?.let { append("，$it") }
                    append("，${videoResourceStateLabel(run.resourceState)}")
                }
            },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "状态：$stateLabel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = runStateColor(run.state),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    videoResourceStateLabel(run.resourceState),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("阶段：")
                    append(videoRunStageLabel(run.stage))
                    progress?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "修订 ${run.revisionId.value} · 尝试 ${run.attemptId.value}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run.errorCode?.let { code ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "错误码：${code.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            run.errorDetail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("video.run_error"),
                )
            }
            run.outputMediaId?.let { mediaId ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "输出媒体 ${mediaId.value} 已发布，可在“作品”中播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run.state in CANCELABLE_STATES) {
                Spacer(Modifier.height(8.dp))
                A11yLabeledControl(
                    contentDescription = "取消任务",
                    stateDescription = "任务状态 ${stateLabel}",
                    onClick = onCancel,
                ) {
                    McaButton(
                        onClick = onCancel,
                        style = McaButtonStyle.Outlined,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("video.run_cancel"),
                    ) {
                        Text("取消任务")
                    }
                }
            }
            if (run.state in RETRYABLE_STATES) {
                Spacer(Modifier.height(8.dp))
                val retryLabel = if (run.state == VideoRunState.COMPLETED) "重新运行任务" else "重试任务"
                A11yLabeledControl(
                    contentDescription = retryLabel,
                    stateDescription = "任务状态 ${stateLabel}",
                    onClick = onRetry,
                ) {
                    McaButton(
                        onClick = onRetry,
                        style = McaButtonStyle.Outlined,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("video.run_retry_action"),
                    ) {
                        Text(if (run.state == VideoRunState.COMPLETED) "重新运行" else "重试")
                    }
                }
            }
            retryNotice?.let { notice ->
                Spacer(Modifier.height(4.dp))
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("video.run_retry_error"),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "更新于 ${formatVideoCreatedAt(run.updatedAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CANCELABLE_STATES = setOf(
    VideoRunState.QUEUED,
    VideoRunState.PREPARING,
    VideoRunState.CONDITIONING,
    VideoRunState.SAMPLING,
    VideoRunState.DECODING,
    VideoRunState.ENCODING,
    VideoRunState.PUBLISHING,
    VideoRunState.CANCEL_REQUESTED,
)

/** Terminal states the owner can explicitly retry with a new attempt on the frozen snapshot. */
private val RETRYABLE_STATES = setOf(
    VideoRunState.COMPLETED,
    VideoRunState.FAILED,
    VideoRunState.CANCELLED,
    VideoRunState.INTERRUPTED,
)

@Composable
private fun runStateColor(state: VideoRunState) = when (state) {
    VideoRunState.COMPLETED -> MaterialTheme.colorScheme.primary
    VideoRunState.FAILED,
    VideoRunState.INTERRUPTED,
    VideoRunState.CANCELLED,
    -> MaterialTheme.colorScheme.error

    else -> MaterialTheme.colorScheme.onSurface
}

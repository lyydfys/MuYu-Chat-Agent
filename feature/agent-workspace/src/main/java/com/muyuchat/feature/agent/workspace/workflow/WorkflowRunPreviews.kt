package com.muyuchat.feature.agent.workspace.workflow

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.muyuchat.core.designsystem.McaTheme

/**
 * Fixed-state previews for the workflow run surface.
 *
 * Previews are compile-time documentation only; device screenshots, TalkBack, IME and large-font
 * evidence stays NOT_RUN until a real device pass.
 */

private fun previewDefinitions(): List<WorkflowRunDefinitionUi> = listOf(
    WorkflowRunDefinitionUi(
        definitionId = "recipe-weekly-review",
        displayName = "每周回顾",
        version = 3L,
        versionLabel = "v3",
        goalPreview = "总结工作区中的记录，并列出三项下周行动。",
        updatedLabel = "今天 09:12"
    ),
    WorkflowRunDefinitionUi(
        definitionId = "recipe-brief",
        displayName = "简报整理",
        version = 1L,
        versionLabel = "v1",
        goalPreview = "整理 inputs/brief.md 的要点。",
        updatedLabel = null
    )
)

private fun previewHistory(): List<WorkflowRunHistoryRowUi> = listOf(
    WorkflowRunHistoryRowUi(
        runId = "wfrun-2f4c9a1b",
        status = WorkflowRunStatus.COMPLETED,
        statusLabel = "已完成",
        triggerLabel = "手动",
        timeLabel = "2 分钟前",
        detailLabel = "recipe-weekly-review · v3"
    ),
    WorkflowRunHistoryRowUi(
        runId = "wfrun-8d3e0c77",
        status = WorkflowRunStatus.FAILED,
        statusLabel = "失败",
        triggerLabel = "手动",
        timeLabel = "1 小时前",
        detailLabel = "recipe-brief · v1",
        errorCode = "WF_TOOL_NOT_REGISTERED"
    )
)

private fun loadedState(): WorkflowRunState = WorkflowRunState(
    definitions = previewDefinitions(),
    selectedDefinitionId = "recipe-weekly-review",
    history = previewHistory(),
    receipt = WorkflowRunReceiptUi(
        status = WorkflowRunReceiptStatus.COMPLETED,
        statusLabel = "已完成",
        runId = "wfrun-2f4c9a1b",
        finishedLabel = "2 分钟前"
    ),
    lastRefreshLabel = "10:24"
)

@Preview(name = "工作流运行-已加载", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun WorkflowRunLoadedPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            WorkflowRunScreen(state = loadedState(), actions = WorkflowRunActions())
        }
    }
}

@Preview(name = "工作流运行-空态", showBackground = true, widthDp = 400, heightDp = 700)
@Composable
private fun WorkflowRunEmptyPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            WorkflowRunScreen(state = WorkflowRunState(), actions = WorkflowRunActions())
        }
    }
}

@Preview(name = "工作流运行-进行中", showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun WorkflowRunRunningPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            WorkflowRunScreen(
                state = loadedState().copy(
                    running = true,
                    receipt = null,
                    history = previewHistory().take(1)
                ),
                actions = WorkflowRunActions()
            )
        }
    }
}

@Preview(name = "工作流运行-错误与重放", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun WorkflowRunErrorPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            WorkflowRunScreen(
                state = loadedState().copy(
                    actionError = WorkflowRunStateHolder.RUN_FAILED,
                    receipt = WorkflowRunReceiptUi(
                        status = WorkflowRunReceiptStatus.REJECTED,
                        statusLabel = "已拒绝",
                        runId = null,
                        errorCode = "WF_VERSION_NOT_FOUND",
                        safeMessage = "请求的工作流版本不存在。",
                        finishedLabel = "刚刚",
                        replay = true
                    )
                ),
                actions = WorkflowRunActions()
            )
        }
    }
}

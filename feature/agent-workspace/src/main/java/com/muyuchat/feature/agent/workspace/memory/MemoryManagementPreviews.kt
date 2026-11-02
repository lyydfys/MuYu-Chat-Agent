package com.muyuchat.feature.agent.workspace.memory

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.muyuchat.core.designsystem.McaTheme

/**
 * Fixed-state previews for the memory management surface.
 *
 * Previews are compile-time documentation only; they are not the device visual review. Screenshot,
 * TalkBack, IME and large-font evidence stays NOT_RUN until a real device pass.
 */

private fun previewSpaces(): List<MemorySpaceRowUi> = listOf(
    MemorySpaceRowUi(
        spaceId = "space-preferences",
        displayName = "偏好",
        description = "",
        itemCount = 3,
        itemsLabel = "3 条记忆",
        egressLabel = "仅本地",
        updatedLabel = "10:24"
    ),
    MemorySpaceRowUi(
        spaceId = "space-projects",
        displayName = "项目笔记",
        description = "任务复盘与接口约定",
        itemCount = 12,
        itemsLabel = "12 条记忆",
        egressLabel = "仅本地",
        updatedLabel = null
    )
)

private fun previewItems(): List<MemoryItemRowUi> = listOf(
    MemoryItemRowUi(
        spaceId = "space-preferences",
        itemId = "memory-1",
        title = "明天九点开会",
        excerpt = "明天九点开会",
        revision = 2L,
        sourceLabel = "自动提取",
        updatedLabel = "2 分钟前",
        revoked = false,
        hasSourceRef = true
    ),
    MemoryItemRowUi(
        spaceId = "space-preferences",
        itemId = "memory-2",
        title = "我喜欢喝乌龙茶",
        excerpt = "我喜欢喝乌龙茶",
        revision = 1L,
        sourceLabel = "手动记录",
        updatedLabel = "1 小时前",
        revoked = false,
        hasSourceRef = false
    ),
    MemoryItemRowUi(
        spaceId = "space-preferences",
        itemId = "memory-3",
        title = "已删除的旧偏好",
        excerpt = "已删除的旧偏好，可在显示已删除后恢复。",
        revision = 3L,
        sourceLabel = "文件导入",
        updatedLabel = "3 天前",
        revoked = true,
        hasSourceRef = true
    )
)

private fun previewCandidates(): List<MemoryCandidateRowUi> = listOf(
    MemoryCandidateRowUi(
        candidateId = "candidate-1",
        title = "周五发版",
        text = "请记住：周五发版",
        confidenceLabel = "高",
        originLabel = "规则：remember_explicit",
        duplicateLabel = null
    ),
    MemoryCandidateRowUi(
        candidateId = "candidate-2",
        title = "喜欢乌龙茶",
        text = "我喜欢喝乌龙茶",
        confidenceLabel = "中",
        originLabel = "模型：local-fake-v1",
        duplicateLabel = "已存在于该空间"
    )
)

private fun loadedState(
    includeRevoked: Boolean = false,
    items: List<MemoryItemRowUi> = previewItems()
): MemoryManagementState = MemoryManagementState(
    spaces = previewSpaces(),
    selectedSpaceId = "space-preferences",
    query = "",
    includeRevoked = includeRevoked,
    items = items.filter { includeRevoked || !it.revoked },
    itemsTotal = if (includeRevoked) items.size else items.count { !it.revoked },
    hasMoreItems = true,
    candidates = previewCandidates(),
    candidatesSubmittedLabel = "刚刚",
    semanticLabel = "仅词法检索（未配置本地向量）"
)

private fun previewActions(): MemoryManagementActions = MemoryManagementActions()

@Preview(name = "记忆管理-已加载", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun MemoryManagementLoadedPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MemoryManagementScreen(state = loadedState(), actions = previewActions())
        }
    }
}

@Preview(name = "记忆管理-含已删除", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun MemoryManagementRevokedPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MemoryManagementScreen(state = loadedState(includeRevoked = true), actions = previewActions())
        }
    }
}

@Preview(name = "记忆管理-空态", showBackground = true, widthDp = 400, heightDp = 700)
@Composable
private fun MemoryManagementEmptyPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MemoryManagementScreen(
                state = MemoryManagementState(
                    spaces = previewSpaces(),
                    selectedSpaceId = "space-preferences",
                    items = emptyList(),
                    itemsTotal = 0,
                    semanticLabel = "仅词法检索（未配置本地向量）"
                ),
                actions = previewActions()
            )
        }
    }
}

@Preview(name = "记忆管理-错误与编辑", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun MemoryManagementErrorEditorPreview() {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MemoryManagementScreen(
                state = loadedState().copy(
                    actionError = MemoryManagementErrorUi(
                        code = "MEMORY_REVISION_CONFLICT",
                        message = "该条目已被其他操作修改，请重新打开后再保存。"
                    ),
                    editor = MemoryEditorUi(
                        itemId = "memory-1",
                        title = "明天九点开会",
                        text = "明天九点开会，地点在三层会议室。",
                        expectedRevision = 2L,
                        loading = false
                    )
                ),
                actions = previewActions()
            )
        }
    }
}

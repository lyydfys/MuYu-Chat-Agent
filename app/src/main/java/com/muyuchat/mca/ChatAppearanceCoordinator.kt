package com.muyuchat.mca

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages chat appearance lifecycle, background image import, persistence, and cleanup.
 * Sinks [globalChatAppearanceStore] and [backgroundImageStore] out of MainViewModel.
 */
internal class ChatAppearanceCoordinator(
    private val scope: CoroutineScope,
    private val uiStateStore: UiStateWriter<ChatAppearanceFields>,
    private val globalChatAppearanceStore: GlobalChatAppearanceStore,
    private val backgroundImageStore: BackgroundImageStore,
    private val assistantStore: AssistantStore,
    private val chatSessionStore: ChatSessionStore,
    private val showStatus: (String?) -> Unit
) {
    val mutex = Mutex()

    fun loadInitialGlobalChatAppearance(): ChatAppearance =
        globalChatAppearanceStore.load()

    fun importChatBackground(scopeKind: ChatAppearanceScope, uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank()) {
            showStatus("无法读取所选背景图片")
            return
        }
        val target = captureChatAppearanceTarget(scopeKind) ?: return
        uiStateStore.update { it.copy(chatBackgroundImporting = true, statusMessage = "正在处理背景图片…") }
        scope.launch {
            mutex.withLock {
                runCatching {
                    val stored = withContext(Dispatchers.IO) { backgroundImageStore.import(uri) }
                    val current = currentAppearanceForTarget(target) ?: target.appearance
                    val appearance = current.copy(backgroundImagePath = stored.path)
                    withContext(Dispatchers.IO) { persistChatAppearanceTarget(target, appearance) }
                    applyPersistedChatAppearance(target, appearance)
                    cleanupUnusedChatBackgrounds()
                }.onSuccess {
                    uiStateStore.update {
                        it.copy(chatBackgroundImporting = false, statusMessage = "聊天背景已更新")
                    }
                }.onFailure { error ->
                    uiStateStore.update {
                        it.copy(
                            chatBackgroundImporting = false,
                            statusMessage = "背景图片设置失败：${error.message ?: "无法处理图片"}"
                        )
                    }
                }
            }
        }
    }

    fun setChatAppearance(scopeKind: ChatAppearanceScope, appearance: ChatAppearance?) {
        val target = captureChatAppearanceTarget(scopeKind) ?: return
        val normalized = when (scopeKind) {
            ChatAppearanceScope.GLOBAL -> appearance ?: ChatAppearance()
            ChatAppearanceScope.ASSISTANT,
            ChatAppearanceScope.SESSION -> appearance
        }
        if (normalized?.backgroundImagePath != null &&
            backgroundImageStore.resolve(normalized.backgroundImagePath) == null
        ) {
            showStatus("背景图片已失效，请重新选择")
            return
        }
        scope.launch {
            mutex.withLock {
                runCatching {
                    withContext(Dispatchers.IO) { persistChatAppearanceTarget(target, normalized) }
                    applyPersistedChatAppearance(target, normalized)
                    cleanupUnusedChatBackgrounds()
                }.onFailure { error ->
                    uiStateStore.update {
                        it.copy(statusMessage = "保存聊天外观失败：${error.message ?: "存储错误"}")
                    }
                }
            }
        }
    }

    private data class ChatAppearanceTarget(
        val scope: ChatAppearanceScope,
        val ownerId: String?,
        val appearance: ChatAppearance
    )

    private fun captureChatAppearanceTarget(scopeKind: ChatAppearanceScope): ChatAppearanceTarget? {
        val state = uiStateStore.current
        return when (scopeKind) {
            ChatAppearanceScope.GLOBAL -> ChatAppearanceTarget(scopeKind, null, state.globalChatAppearance)
            ChatAppearanceScope.ASSISTANT -> {
                val assistant = state.assistants.firstOrNull { it.id == state.selectedAssistantId }
                if (assistant == null) {
                    showStatus("当前角色不可用")
                    null
                } else {
                    ChatAppearanceTarget(scopeKind, assistant.id, assistant.appearance)
                }
            }
            ChatAppearanceScope.SESSION -> {
                val session = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
                if (session == null) {
                    showStatus("请先发送一条消息，再设置当前会话背景")
                    null
                } else {
                    ChatAppearanceTarget(scopeKind, session.id, session.appearanceOverride ?: ChatAppearance())
                }
            }
        }
    }

    private fun currentAppearanceForTarget(target: ChatAppearanceTarget): ChatAppearance? {
        val state = uiStateStore.current
        return when (target.scope) {
            ChatAppearanceScope.GLOBAL -> state.globalChatAppearance
            ChatAppearanceScope.ASSISTANT -> state.assistants
                .firstOrNull { it.id == target.ownerId }
                ?.appearance
            ChatAppearanceScope.SESSION -> state.chatSessions
                .firstOrNull { it.id == target.ownerId }
                ?.appearanceOverride
        }
    }

    private fun persistChatAppearanceTarget(target: ChatAppearanceTarget, appearance: ChatAppearance?) {
        when (target.scope) {
            ChatAppearanceScope.GLOBAL -> {
                val value = appearance ?: ChatAppearance()
                if (value.isDefault) globalChatAppearanceStore.clear() else globalChatAppearanceStore.save(value)
            }
            ChatAppearanceScope.ASSISTANT -> {
                val ownerId = requireNotNull(target.ownerId)
                checkNotNull(assistantStore.updateAppearance(ownerId, appearance ?: ChatAppearance())) {
                    "角色已不存在"
                }
            }
            ChatAppearanceScope.SESSION -> {
                val ownerId = requireNotNull(target.ownerId)
                chatSessionStore.updateAppearance(ownerId, appearance)
            }
        }
    }

    private fun applyPersistedChatAppearance(target: ChatAppearanceTarget, appearance: ChatAppearance?) {
        uiStateStore.update { state ->
            when (target.scope) {
                ChatAppearanceScope.GLOBAL -> state.copy(globalChatAppearance = appearance ?: ChatAppearance())
                ChatAppearanceScope.ASSISTANT -> state.copy(
                    assistants = state.assistants.map { assistant ->
                        if (assistant.id == target.ownerId) {
                            assistant.copy(appearance = appearance ?: ChatAppearance())
                        } else {
                            assistant
                        }
                    }
                )
                ChatAppearanceScope.SESSION -> state.copy(
                    chatSessions = state.chatSessions.map { session ->
                        if (session.id == target.ownerId) session.copy(appearanceOverride = appearance) else session
                    }
                )
            }
        }
    }

    suspend fun cleanupUnusedChatBackgrounds() {
        val state = uiStateStore.current
        val referenced = buildSet {
            state.globalChatAppearance.backgroundImagePath?.let(::add)
            state.assistants.mapNotNullTo(this) { it.appearance.backgroundImagePath }
            state.chatSessions.mapNotNullTo(this) { it.appearanceOverride?.backgroundImagePath }
        }
        withContext(Dispatchers.IO) { backgroundImageStore.cleanup(referenced) }
    }
}

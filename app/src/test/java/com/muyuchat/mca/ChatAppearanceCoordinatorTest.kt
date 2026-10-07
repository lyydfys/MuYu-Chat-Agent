package com.muyuchat.mca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ChatAppearanceCoordinatorTest {

    @Test
    fun coordinatorExposesMutex() {
        val uiStateStore = UiStateStore(MainUiState())
        val dummyScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        val globalAppearanceStore = createStoreProxy(GlobalChatAppearanceStore::class.java)
        val backgroundImageStore = createStoreProxy(BackgroundImageStore::class.java)
        val assistantStore = createStoreProxy(AssistantStore::class.java)
        val chatSessionStore = createStoreProxy(ChatSessionStore::class.java)

        val coordinator = ChatAppearanceCoordinator(
            scope = dummyScope,
            uiStateStore = uiStateStore,
            globalChatAppearanceStore = globalAppearanceStore,
            backgroundImageStore = backgroundImageStore,
            assistantStore = assistantStore,
            chatSessionStore = chatSessionStore,
            showStatus = {}
        )

        assertNotNull(coordinator.mutex)
    }

    @Test
    fun importChatBackgroundWithInvalidUriReportsStatus() {
        val uiStateStore = UiStateStore(MainUiState())
        val dummyScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val statuses = mutableListOf<String?>()

        val globalAppearanceStore = createStoreProxy(GlobalChatAppearanceStore::class.java)
        val backgroundImageStore = createStoreProxy(BackgroundImageStore::class.java)
        val assistantStore = createStoreProxy(AssistantStore::class.java)
        val chatSessionStore = createStoreProxy(ChatSessionStore::class.java)

        val coordinator = ChatAppearanceCoordinator(
            scope = dummyScope,
            uiStateStore = uiStateStore,
            globalChatAppearanceStore = globalAppearanceStore,
            backgroundImageStore = backgroundImageStore,
            assistantStore = assistantStore,
            chatSessionStore = chatSessionStore,
            showStatus = { statuses.add(it) }
        )

        coordinator.importChatBackground(ChatAppearanceScope.GLOBAL, "")
        assertTrue(statuses.contains("无法读取所选背景图片"))
    }

    @Test
    fun importChatBackgroundWithMissingAssistantReportsStatus() {
        val uiStateStore = UiStateStore(MainUiState(selectedAssistantId = "missing-id"))
        val dummyScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val statuses = mutableListOf<String?>()

        val globalAppearanceStore = createStoreProxy(GlobalChatAppearanceStore::class.java)
        val backgroundImageStore = createStoreProxy(BackgroundImageStore::class.java)
        val assistantStore = createStoreProxy(AssistantStore::class.java)
        val chatSessionStore = createStoreProxy(ChatSessionStore::class.java)

        val coordinator = ChatAppearanceCoordinator(
            scope = dummyScope,
            uiStateStore = uiStateStore,
            globalChatAppearanceStore = globalAppearanceStore,
            backgroundImageStore = backgroundImageStore,
            assistantStore = assistantStore,
            chatSessionStore = chatSessionStore,
            showStatus = { statuses.add(it) }
        )

        coordinator.setChatAppearance(ChatAppearanceScope.ASSISTANT, ChatAppearance())
        assertTrue(statuses.contains("当前角色不可用"))
    }

    @Test
    fun setChatAppearanceWithMissingSessionReportsStatus() {
        val uiStateStore = UiStateStore(MainUiState(activeChatSessionId = "missing-session"))
        val dummyScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val statuses = mutableListOf<String?>()

        val globalAppearanceStore = createStoreProxy(GlobalChatAppearanceStore::class.java)
        val backgroundImageStore = createStoreProxy(BackgroundImageStore::class.java)
        val assistantStore = createStoreProxy(AssistantStore::class.java)
        val chatSessionStore = createStoreProxy(ChatSessionStore::class.java)

        val coordinator = ChatAppearanceCoordinator(
            scope = dummyScope,
            uiStateStore = uiStateStore,
            globalChatAppearanceStore = globalAppearanceStore,
            backgroundImageStore = backgroundImageStore,
            assistantStore = assistantStore,
            chatSessionStore = chatSessionStore,
            showStatus = { statuses.add(it) }
        )

        coordinator.setChatAppearance(ChatAppearanceScope.SESSION, ChatAppearance())
        assertTrue(statuses.contains("请先发送一条消息，再设置当前会话背景"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createStoreProxy(clazz: Class<T>): T {
        // If class is not an interface, instantiate directly via objenesis or standard reflection if possible,
        // or subclass / default constructor if mock is needed.
        // For Android Context-taking classes, we can use sun.reflect or Objenesis to allocate without calling constructor.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz) as T
    }
}

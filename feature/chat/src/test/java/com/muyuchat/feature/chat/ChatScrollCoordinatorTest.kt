package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollCoordinatorTest {
    @Test
    fun contentGrowthRequestsTheTailOnlyWhileFollowing() {
        val following = ChatScrollIntentState(mode = ChatScrollFollowMode.Following)
        val followingGrowth = reduceChatScrollIntent(
            following,
            ChatScrollIntentAction.ContentGrew,
        )

        assertEquals(ChatScrollFollowMode.Following, followingGrowth.state.mode)
        assertEquals(ChatScrollRequestKind.FollowGrowth, followingGrowth.requestKind)
        assertFalse(followingGrowth.cancelProgrammaticScroll)

        val detached = ChatScrollIntentState(mode = ChatScrollFollowMode.DetachedByUser)
        val detachedGrowth = reduceChatScrollIntent(
            detached,
            ChatScrollIntentAction.ContentGrew,
        )

        assertEquals(ChatScrollFollowMode.DetachedByUser, detachedGrowth.state.mode)
        assertNull(detachedGrowth.requestKind)
    }

    @Test
    fun userVerticalScrollDetachesAndMakesQueuedFollowRequestsStale() {
        val following = ChatScrollIntentState(mode = ChatScrollFollowMode.Following)
        val transition = reduceChatScrollIntent(
            following,
            ChatScrollIntentAction.UserVerticalScroll,
        )

        assertEquals(ChatScrollFollowMode.DetachedByUser, transition.state.mode)
        assertTrue(transition.state.userScrollSettlementPending)
        assertTrue(transition.cancelProgrammaticScroll)
        assertFalse(ChatScrollRequestKind.FollowGrowth.isAllowedIn(transition.state.mode))
        assertFalse(ChatScrollRequestKind.RestoreTail.isAllowedIn(transition.state.mode))

        val laterGrowth = reduceChatScrollIntent(
            transition.state,
            ChatScrollIntentAction.ContentGrew,
        )
        assertEquals(ChatScrollFollowMode.DetachedByUser, laterGrowth.state.mode)
        assertNull(laterGrowth.requestKind)
    }

    @Test
    fun restoringWaitsForItsOwnCompletionBeforeFollowingGrowth() {
        val restoring = ChatScrollIntentState(mode = ChatScrollFollowMode.Restoring)
        val growth = reduceChatScrollIntent(restoring, ChatScrollIntentAction.ContentGrew)
        assertNull(growth.requestKind)

        val completed = reduceChatScrollIntent(
            restoring,
            ChatScrollIntentAction.RestoreCompleted,
        )
        assertEquals(ChatScrollFollowMode.Following, completed.state.mode)
    }

    @Test
    fun aRestoreCompletionCannotOverrideUserDetach() {
        val restoring = ChatScrollIntentState(mode = ChatScrollFollowMode.Restoring)
        val detached = reduceChatScrollIntent(
            restoring,
            ChatScrollIntentAction.UserVerticalScroll,
        ).state
        val restoreCompletion = reduceChatScrollIntent(
            detached,
            ChatScrollIntentAction.RestoreCompleted,
        )

        assertEquals(ChatScrollFollowMode.DetachedByUser, restoreCompletion.state.mode)
    }

    @Test
    fun userSettlementNearTheTailRestoresFollowingButSettlementAwayDoesNot() {
        val detached = ChatScrollIntentState(
            mode = ChatScrollFollowMode.DetachedByUser,
            userScrollSettlementPending = true,
        )
        val near = reduceChatScrollIntent(
            detached,
            ChatScrollIntentAction.UserScrollSettledNearBottom,
        )
        assertEquals(ChatScrollFollowMode.Following, near.state.mode)
        assertFalse(near.state.userScrollSettlementPending)

        val away = reduceChatScrollIntent(
            detached,
            ChatScrollIntentAction.UserScrollSettledAwayFromBottom,
        )
        assertEquals(ChatScrollFollowMode.DetachedByUser, away.state.mode)
        assertFalse(away.state.userScrollSettlementPending)
    }

    @Test
    fun sendingANewMessageExplicitlyResumesFollowing() {
        val detached = ChatScrollIntentState(mode = ChatScrollFollowMode.DetachedByUser)
        val transition = reduceChatScrollIntent(
            detached,
            ChatScrollIntentAction.UserSentMessage,
        )

        assertEquals(ChatScrollFollowMode.Following, transition.state.mode)
        assertTrue(transition.cancelProgrammaticScroll)
    }

    @Test
    fun requestsFromAnOldConversationIdentityCanNeverRunInTheNewOne() {
        val oldRequest = ChatScrollRequest(
            sessionId = "conversation-a",
            sessionGeneration = 7L,
            targetItemIndex = 42,
            kind = ChatScrollRequestKind.FollowGrowth,
        )

        assertTrue(oldRequest.isCurrentFor("conversation-a", 7L))
        assertFalse(oldRequest.isCurrentFor("conversation-b", 8L))
        assertFalse(oldRequest.isCurrentFor("conversation-a", 8L))
    }

    @Test
    fun onlyVerticalDominantUserNestedScrollCanDetach() {
        assertTrue(
            shouldDetachForNestedScroll(
                source = ChatScrollDispatchSource.UserInput,
                deltaX = 2f,
                deltaY = -24f,
            )
        )
        assertFalse(
            shouldDetachForNestedScroll(
                source = ChatScrollDispatchSource.UserInput,
                deltaX = 24f,
                deltaY = 0f,
            )
        )
        assertFalse(
            shouldDetachForNestedScroll(
                source = ChatScrollDispatchSource.UserInput,
                deltaX = 24f,
                deltaY = 2f,
            )
        )
        assertFalse(
            shouldDetachForNestedScroll(
                source = ChatScrollDispatchSource.SideEffect,
                deltaX = 0f,
                deltaY = 24f,
            )
        )
        assertFalse(
            shouldDetachForNestedScroll(
                source = ChatScrollDispatchSource.Relocate,
                deltaX = 0f,
                deltaY = 24f,
            )
        )
    }

    @Test
    fun nearBottomGeometryDoesNotTreatAtallLastItemAsReached() {
        val nearBottom = ChatScrollLayoutObservation(
            totalItemsCount = 3,
            viewportStartOffset = 0,
            viewportEndOffset = 1_000,
            afterContentPadding = 118,
            canScrollForward = true,
            visibleItems = listOf(ChatScrollVisibleItem(index = 2, offset = 800, size = 82)),
            isScrollInProgress = false,
        )
        assertTrue(nearBottom.isNearScrollBottom(thresholdPx = 48f))

        val tallTail = nearBottom.copy(
            visibleItems = listOf(ChatScrollVisibleItem(index = 2, offset = 800, size = 500)),
        )
        assertFalse(tallTail.isNearScrollBottom(thresholdPx = 48f))

        val olderItem = nearBottom.copy(
            visibleItems = listOf(ChatScrollVisibleItem(index = 1, offset = 800, size = 82)),
        )
        assertFalse(olderItem.isNearScrollBottom(thresholdPx = 48f))
    }
}

class ChatScrollWiringPolicyTest {
    @Test
    fun productionDropsCharacterBucketsAndWiresOneNestedScrollCoordinator() {
        val chatScreen = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt")
        val messageList = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMessageListSection.kt"
        )
        val coordinator = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScrollCoordinator.kt"
        )

        assertFalse(chatScreen.contains("STREAMING_SCROLL_CHAR_STEP"))
        assertFalse(chatScreen.contains("streamingScrollBucket"))
        assertFalse(chatScreen.contains("listState.scrollToItem"))
        assertTrue(chatScreen.contains("toChatScrollLayoutObservation()"))
        assertTrue(chatScreen.contains("nestedScrollConnection = scrollNestedScrollConnection"))
        assertTrue(chatScreen.contains("scrollCoordinator.onUserSentMessage()"))

        assertTrue(messageList.contains(".nestedScroll(nestedScrollConnection)"))

        assertTrue(coordinator.contains("Channel.CONFLATED"))
        assertTrue(coordinator.contains("NestedScrollSource.UserInput"))
        assertTrue(coordinator.contains("available.y"))
        assertFalse(coordinator.contains("pointerInput"))
        assertEquals(1, coordinator.countOccurrences("scrollToItem("))
    }

    private fun sourceFile(relativePath: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            val candidate = File(root, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val index = indexOf(needle, from)
            if (index < 0) return count
            count += 1
            from = index + needle.length
        }
    }
}

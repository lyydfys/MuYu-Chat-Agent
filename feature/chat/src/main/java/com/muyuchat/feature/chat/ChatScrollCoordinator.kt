package com.muyuchat.feature.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val CHAT_SCROLL_FOLLOW_THRESHOLD_DP = 48

/**
 * Page-local scroll intent. It belongs to the visible chat page and is never written into
 * [ChatUiState] or the app-level state store.
 */
internal enum class ChatScrollFollowMode {
    Following,
    DetachedByUser,
    Restoring,
}

/** Sources that can reach a parent nested-scroll connection from the message list. */
internal enum class ChatScrollDispatchSource {
    UserInput,
    SideEffect,
    Relocate,
}

internal data class ChatScrollIntentState(
    val mode: ChatScrollFollowMode = ChatScrollFollowMode.Restoring,
    val userScrollSettlementPending: Boolean = false,
)

internal sealed interface ChatScrollIntentAction {
    data object UserVerticalScroll : ChatScrollIntentAction
    data object UserScrollSettledNearBottom : ChatScrollIntentAction
    data object UserScrollSettledAwayFromBottom : ChatScrollIntentAction
    data object UserSentMessage : ChatScrollIntentAction
    data object ContentGrew : ChatScrollIntentAction
    data object RestoreCompleted : ChatScrollIntentAction
}

internal enum class ChatScrollRequestKind {
    RestoreTail,
    FollowGrowth,
}

internal data class ChatScrollIntentTransition(
    val state: ChatScrollIntentState,
    val cancelProgrammaticScroll: Boolean = false,
    val requestKind: ChatScrollRequestKind? = null,
)

/**
 * Pure intent reducer. Geometry decides how far to move; this reducer decides whether moving is
 * allowed at all. In particular, content growth can never turn DetachedByUser back into Following.
 */
internal fun reduceChatScrollIntent(
    state: ChatScrollIntentState,
    action: ChatScrollIntentAction,
): ChatScrollIntentTransition = when (action) {
    ChatScrollIntentAction.UserVerticalScroll -> ChatScrollIntentTransition(
        state = state.copy(
            mode = ChatScrollFollowMode.DetachedByUser,
            userScrollSettlementPending = true,
        ),
        cancelProgrammaticScroll = true,
    )

    ChatScrollIntentAction.UserScrollSettledNearBottom -> if (
        state.mode == ChatScrollFollowMode.DetachedByUser
    ) {
        ChatScrollIntentTransition(
            state = state.copy(
                mode = ChatScrollFollowMode.Following,
                userScrollSettlementPending = false,
            ),
        )
    } else {
        ChatScrollIntentTransition(state = state.copy(userScrollSettlementPending = false))
    }

    ChatScrollIntentAction.UserScrollSettledAwayFromBottom -> ChatScrollIntentTransition(
        state = state.copy(userScrollSettlementPending = false),
    )

    ChatScrollIntentAction.UserSentMessage -> ChatScrollIntentTransition(
        state = state.copy(
            mode = ChatScrollFollowMode.Following,
            userScrollSettlementPending = false,
        ),
        cancelProgrammaticScroll = true,
    )

    ChatScrollIntentAction.ContentGrew -> if (state.mode == ChatScrollFollowMode.Following) {
        ChatScrollIntentTransition(
            state = state,
            requestKind = ChatScrollRequestKind.FollowGrowth,
        )
    } else {
        ChatScrollIntentTransition(state = state)
    }

    ChatScrollIntentAction.RestoreCompleted -> if (state.mode == ChatScrollFollowMode.Restoring) {
        ChatScrollIntentTransition(state = state.copy(mode = ChatScrollFollowMode.Following))
    } else {
        ChatScrollIntentTransition(state = state)
    }
}

internal fun ChatScrollRequestKind.isAllowedIn(mode: ChatScrollFollowMode): Boolean = when (this) {
    ChatScrollRequestKind.RestoreTail -> mode == ChatScrollFollowMode.Restoring
    ChatScrollRequestKind.FollowGrowth -> mode == ChatScrollFollowMode.Following
}

/** Immutable geometry captured from one list layout pass. */
internal data class ChatScrollVisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal data class ChatScrollLayoutObservation(
    val totalItemsCount: Int,
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
    val afterContentPadding: Int,
    val canScrollForward: Boolean,
    val visibleItems: List<ChatScrollVisibleItem>,
    val isScrollInProgress: Boolean,
)

internal fun ChatScrollLayoutObservation.isNearScrollBottom(thresholdPx: Float): Boolean {
    if (!canScrollForward) return true
    val lastVisible = visibleItems.lastOrNull() ?: return true
    if (lastVisible.index != totalItemsCount - 1) return false
    val contentEnd = viewportEndOffset - afterContentPadding
    val distanceFromEnd = contentEnd - (lastVisible.offset + lastVisible.size)
    // A negative value beyond one rounding pixel means the last item still extends below the
    // viewport (for example, one message taller than two screens), so it is not near the bottom.
    return distanceFromEnd >= -1f && distanceFromEnd <= thresholdPx.coerceAtLeast(0f)
}

internal fun LazyListState.toChatScrollLayoutObservation(): ChatScrollLayoutObservation {
    val layout = layoutInfo
    return ChatScrollLayoutObservation(
        totalItemsCount = layout.totalItemsCount,
        viewportStartOffset = layout.viewportStartOffset,
        viewportEndOffset = layout.viewportEndOffset,
        afterContentPadding = layout.afterContentPadding,
        canScrollForward = canScrollForward,
        visibleItems = layout.visibleItemsInfo.map { item ->
            ChatScrollVisibleItem(
                index = item.index,
                offset = item.offset,
                size = item.size,
            )
        },
        isScrollInProgress = isScrollInProgress,
    )
}

/**
 * Only nested scroll dispatched as user input with a vertical-dominant delta detaches the view.
 * Programmatic list movement uses SideEffect/Relocate, and a horizontal code-block scroll has no
 * vertical delta. Text selection and a bare pointer-down do not dispatch a list scroll at all.
 */
internal fun shouldDetachForNestedScroll(
    source: ChatScrollDispatchSource,
    deltaX: Float,
    deltaY: Float,
): Boolean = source == ChatScrollDispatchSource.UserInput &&
    abs(deltaY) > 0f &&
    abs(deltaY) > abs(deltaX)

internal fun chatScrollNestedScrollConnection(
    coordinator: ChatScrollCoordinator,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (shouldDetachForNestedScroll(
                source = source.toChatScrollDispatchSource(),
                deltaX = available.x,
                deltaY = available.y,
            )
        ) {
            coordinator.onUserVerticalScrollInput()
        }
        return Offset.Zero
    }
}

private fun NestedScrollSource.toChatScrollDispatchSource(): ChatScrollDispatchSource = when (this) {
    NestedScrollSource.UserInput -> ChatScrollDispatchSource.UserInput
    NestedScrollSource.Relocate -> ChatScrollDispatchSource.Relocate
    else -> ChatScrollDispatchSource.SideEffect
}

internal data class ChatScrollRequest(
    val sessionId: String?,
    val sessionGeneration: Long,
    val targetItemIndex: Int,
    val kind: ChatScrollRequestKind,
) {
    fun isCurrentFor(sessionId: String?, sessionGeneration: Long): Boolean =
        this.sessionId == sessionId && this.sessionGeneration == sessionGeneration
}

/**
 * Owns the page's follow intent and the single programmatic scroll worker.
 *
 * Requests carry the conversation identity and a monotonically increasing session generation. A
 * session switch cancels the active job, and queued requests from the previous generation are
 * rejected before they can touch the next conversation's list. A conflated queue provides same-frame
 * coalescing: the executor only ever consumes the latest outstanding demand.
 */
internal class ChatScrollCoordinator(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val followThresholdPx: Float,
) {
    private val requests = Channel<ChatScrollRequest>(Channel.CONFLATED)
    private var intentState = ChatScrollIntentState()
    private var activeSessionId: String? = null
    private var sessionInitialized = false
    private var sessionGeneration = 0L
    private var activeScrollJob: Job? = null
    private var programmaticScrollRunning = false
    private var followAfterProgrammaticScroll = false

    private val workerJob = scope.launch {
        for (request in requests) {
            if (!request.isCurrentFor(activeSessionId, sessionGeneration)) continue
            if (!request.kind.isAllowedIn(intentState.mode)) continue
            programmaticScrollRunning = true
            try {
                coroutineScope {
                    val scrollJob = launch(start = CoroutineStart.LAZY) {
                        executeScroll(request)
                    }
                    activeScrollJob = scrollJob
                    scrollJob.start()
                    scrollJob.join()
                }
            } finally {
                activeScrollJob = null
                programmaticScrollRunning = false
                if (followAfterProgrammaticScroll) {
                    followAfterProgrammaticScroll = false
                    val totalItems = listState.layoutInfo.totalItemsCount
                    if (intentState.mode == ChatScrollFollowMode.Following &&
                        listState.canScrollForward &&
                        totalItems > 0
                    ) {
                        enqueue(
                            kind = ChatScrollRequestKind.FollowGrowth,
                            targetItemIndex = totalItems - 1,
                        )
                    }
                }
            }
        }
    }

    internal val followMode: ChatScrollFollowMode
        get() = intentState.mode

    /**
     * Switches the page-local scroll identity. Returns true when this is a real session change.
     * The old job is cancelled before a restore request for the new generation is queued.
     */
    internal fun activateSession(
        conversationId: String?,
        restoreTargetItemIndex: Int,
    ): Boolean {
        if (sessionInitialized && conversationId == activeSessionId) return false
        sessionInitialized = true
        activeSessionId = conversationId
        sessionGeneration += 1
        intentState = ChatScrollIntentState()
        followAfterProgrammaticScroll = false
        activeScrollJob?.cancel()
        enqueue(
            kind = ChatScrollRequestKind.RestoreTail,
            targetItemIndex = restoreTargetItemIndex,
        )
        return true
    }

    /** User vertical scroll has priority over every queued or active follow request. */
    internal fun onUserVerticalScrollInput() {
        followAfterProgrammaticScroll = false
        apply(ChatScrollIntentAction.UserVerticalScroll)
    }

    /** Sending is an explicit product action that deliberately resumes following. */
    internal fun onUserSentMessage() {
        followAfterProgrammaticScroll = false
        apply(ChatScrollIntentAction.UserSentMessage)
    }

    internal fun onLayoutObservation(observation: ChatScrollLayoutObservation) {
        if (programmaticScrollRunning) {
            if (intentState.mode != ChatScrollFollowMode.DetachedByUser &&
                observation.canScrollForward
            ) {
                followAfterProgrammaticScroll = true
            }
            return
        }
        if (observation.totalItemsCount <= 0) return

        if (intentState.mode == ChatScrollFollowMode.DetachedByUser &&
            intentState.userScrollSettlementPending &&
            !observation.isScrollInProgress
        ) {
            apply(
                if (observation.isNearScrollBottom(followThresholdPx)) {
                    ChatScrollIntentAction.UserScrollSettledNearBottom
                } else {
                    ChatScrollIntentAction.UserScrollSettledAwayFromBottom
                }
            )
            return
        }

        if (intentState.mode != ChatScrollFollowMode.Following || !observation.canScrollForward) {
            return
        }
        val transition = apply(ChatScrollIntentAction.ContentGrew)
        transition.requestKind?.let { kind ->
            enqueue(
                kind = kind,
                targetItemIndex = observation.totalItemsCount - 1,
            )
        }
    }

    internal fun close() {
        requests.close()
        activeScrollJob?.cancel()
        workerJob.cancel()
    }

    private fun apply(action: ChatScrollIntentAction): ChatScrollIntentTransition {
        val transition = reduceChatScrollIntent(intentState, action)
        intentState = transition.state
        if (transition.cancelProgrammaticScroll) {
            activeScrollJob?.cancel()
        }
        return transition
    }

    private fun enqueue(
        kind: ChatScrollRequestKind,
        targetItemIndex: Int,
    ) {
        requests.trySend(
            ChatScrollRequest(
                sessionId = activeSessionId,
                sessionGeneration = sessionGeneration,
                targetItemIndex = targetItemIndex.coerceAtLeast(0),
                kind = kind,
            )
        )
    }

    private suspend fun executeScroll(request: ChatScrollRequest) {
        if (!request.isCurrentFor(activeSessionId, sessionGeneration)) return
        if (!request.kind.isAllowedIn(intentState.mode)) return

        if (request.kind == ChatScrollRequestKind.RestoreTail) {
            var waitedFrames = 0
            while (currentCoroutineContext().isActive &&
                listState.layoutInfo.totalItemsCount <= 0 &&
                waitedFrames < MAX_LAYOUT_WAIT_FRAMES
            ) {
                withFrameNanos { }
                waitedFrames += 1
            }
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems <= 0) {
                apply(ChatScrollIntentAction.RestoreCompleted)
                return
            }
            listState.scrollToItem(request.targetItemIndex.coerceAtMost(totalItems - 1))
        }

        var passes = 0
        while (currentCoroutineContext().isActive &&
            request.isCurrentFor(activeSessionId, sessionGeneration) &&
            request.kind.isAllowedIn(intentState.mode) &&
            listState.canScrollForward &&
            passes < MAX_TAIL_SCROLL_PASSES
        ) {
            val layout = listState.layoutInfo
            val viewport = (layout.viewportEndOffset - layout.viewportStartOffset)
                .coerceAtLeast(1)
            val consumed = listState.scrollBy(viewport.toFloat())
            if (consumed <= 0f) break
            passes += 1
        }

        if (request.isCurrentFor(activeSessionId, sessionGeneration) &&
            request.kind == ChatScrollRequestKind.RestoreTail
        ) {
            apply(ChatScrollIntentAction.RestoreCompleted)
        }
    }

    private companion object {
        const val MAX_LAYOUT_WAIT_FRAMES = 5
        const val MAX_TAIL_SCROLL_PASSES = 256
    }
}

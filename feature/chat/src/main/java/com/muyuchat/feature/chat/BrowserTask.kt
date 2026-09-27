package com.muyuchat.feature.chat

import java.util.concurrent.atomic.AtomicLong

/**
 * The observable lifecycle of a user-owned browser operation.
 *
 * A browser task is deliberately smaller than a general WebView controller:
 * it records intent, ownership and the navigation decision, while the WebView
 * remains responsible for rendering only after the decision has been made.
 */
enum class BrowserTaskPhase {
    QUEUED,
    SEARCHING,
    AWAITING_CONFIRMATION,
    NAVIGATING,
    COMPLETED,
    CANCELLED,
    BLOCKED,
    FAILED
}

enum class BrowserTaskKind {
    SOURCE,
    SEARCH
}

data class BrowserTask(
    val taskId: String,
    val owner: String,
    val kind: BrowserTaskKind,
    val initialUrl: String,
    val phase: BrowserTaskPhase = BrowserTaskPhase.QUEUED,
    val currentUrl: String? = null,
    val pendingUrl: String? = null,
    val message: String? = null,
    val sessionId: String? = null,
    val messageId: String? = null,
    val conversationRevision: String? = null,
    val query: String? = null,
    val candidateUrls: List<String> = emptyList(),
    val navigationId: Long = 0L,
    val windowVisible: Boolean = false,
    val approvedOrigins: Set<String> = emptySet(),
    val parentTaskId: String? = null,
    val events: List<BrowserTaskEventRecord> = emptyList()
) {
    val isTerminal: Boolean
        get() = phase == BrowserTaskPhase.COMPLETED ||
            phase == BrowserTaskPhase.CANCELLED ||
            phase == BrowserTaskPhase.BLOCKED ||
            phase == BrowserTaskPhase.FAILED
}

data class BrowserTaskLaunch(
    val owner: String,
    val initialUrl: String,
    val kind: BrowserTaskKind = BrowserTaskKind.SOURCE,
    val sessionId: String? = null,
    val messageId: String? = null,
    val conversationRevision: String? = null,
    val query: String? = null
)

enum class BrowserTaskAction {
    VISIBLE,
    REQUEST_NAVIGATION,
    SELECT_RESULT,
    CONFIRM_NAVIGATION,
    REJECT_NAVIGATION,
    COMPLETE,
    FAIL,
    RETRY,
    CANCEL
}

data class BrowserTaskEvent(
    val taskId: String,
    val navigationId: Long,
    val action: BrowserTaskAction,
    val url: String? = null,
    val message: String? = null,
    val query: String? = null,
    val userInitiated: Boolean = false
)

data class BrowserTaskEventRecord(
    val navigationId: Long,
    val phase: BrowserTaskPhase,
    val url: String?,
    val message: String?
)

object BrowserTaskStateMachine {
    private val nextTaskId = AtomicLong(0L)

    fun create(
        owner: String,
        initialUrl: String,
        kind: BrowserTaskKind = BrowserTaskKind.SOURCE,
        sessionId: String? = null,
        messageId: String? = null,
        conversationRevision: String? = null,
        query: String? = null
    ): BrowserTask = BrowserTask(
        taskId = "browser-${nextTaskId.incrementAndGet()}",
        owner = owner.ifBlank { "unknown" },
        kind = kind,
        initialUrl = initialUrl,
        sessionId = sessionId,
        messageId = messageId,
        conversationRevision = conversationRevision,
        query = query
    )

    fun create(request: BrowserTaskLaunch): BrowserTask = create(
        request.owner, request.initialUrl, request.kind, request.sessionId,
        request.messageId, request.conversationRevision, request.query
    )

    fun beginBackgroundSearch(
        owner: String,
        sessionId: String?,
        messageId: String?,
        query: String
    ): BrowserTask = create(
        owner = owner,
        initialUrl = "",
        kind = BrowserTaskKind.SEARCH,
        sessionId = sessionId,
        messageId = messageId,
        query = query
    ).copy(phase = BrowserTaskPhase.SEARCHING).record()

    fun publishSearchResults(task: BrowserTask, urls: List<String>, message: String?): BrowserTask {
        if (task.kind != BrowserTaskKind.SEARCH || task.phase != BrowserTaskPhase.SEARCHING) return task
        val candidates = urls.mapNotNull(InternalBrowserUrlPolicy::normalizeInitialUrl)
            .distinct().take(12)
        return task.copy(
            phase = if (candidates.isEmpty()) BrowserTaskPhase.COMPLETED
                else BrowserTaskPhase.AWAITING_CONFIRMATION,
            candidateUrls = candidates,
            message = message,
            navigationId = task.navigationId + 1L
        ).record()
    }

    fun selectSearchResult(task: BrowserTask, url: String): BrowserTask {
        if (task.kind != BrowserTaskKind.SEARCH ||
            task.phase != BrowserTaskPhase.AWAITING_CONFIRMATION || url !in task.candidateUrls
        ) return task
        return create(
            owner = task.owner,
            initialUrl = url,
            kind = BrowserTaskKind.SOURCE,
            sessionId = task.sessionId,
            messageId = task.messageId,
            conversationRevision = task.conversationRevision,
            query = task.query
        ).copy(parentTaskId = task.taskId, events = task.events)
    }

    fun acknowledgeVisible(task: BrowserTask): BrowserTask =
        if (task.isTerminal) task else task.copy(windowVisible = true)

    fun begin(task: BrowserTask): BrowserTask {
        if (task.phase != BrowserTaskPhase.QUEUED || !task.windowVisible) return task
        val url = InternalBrowserUrlPolicy.normalizeInitialUrl(task.initialUrl)
            ?: return block(task, "Invalid HTTPS source URL")
        return task.copy(
            phase = if (task.kind == BrowserTaskKind.SEARCH) {
                BrowserTaskPhase.SEARCHING
            } else {
                BrowserTaskPhase.NAVIGATING
            },
            currentUrl = url,
            navigationId = task.navigationId + 1L,
            message = null
        ).record()
    }

    /**
     * Records a requested URL. Cross-host URLs are never loaded implicitly;
     * they remain pending until [confirmNavigation] is called by the user.
     */
    fun requestNavigation(
        task: BrowserTask,
        url: String,
        requiresConfirmation: Boolean,
        userInitiated: Boolean = false
    ): BrowserTask {
        val normalizedUrl = InternalBrowserUrlPolicy.normalizeInitialUrl(url)
            ?: return if (task.isTerminal) task else block(task, "Invalid HTTPS navigation URL")
        if (task.phase == BrowserTaskPhase.COMPLETED && userInitiated) {
            val next = create(
                task.owner, task.initialUrl, task.kind, task.sessionId,
                task.messageId, task.conversationRevision, task.query
            ).copy(
                currentUrl = task.currentUrl,
                windowVisible = task.windowVisible,
                approvedOrigins = task.approvedOrigins,
                parentTaskId = task.taskId,
                events = task.events
            )
            return requestNavigation(next, normalizedUrl, requiresConfirmation)
        }
        if (task.isTerminal || !task.windowVisible) return task
        return if (requiresConfirmation) {
            task.copy(
                phase = BrowserTaskPhase.AWAITING_CONFIRMATION,
                pendingUrl = normalizedUrl,
                navigationId = task.navigationId + 1L,
                message = null
            ).record()
        } else {
            task.copy(
                phase = BrowserTaskPhase.NAVIGATING,
                currentUrl = normalizedUrl,
                navigationId = task.navigationId + 1L,
                pendingUrl = null,
                message = null
            ).record()
        }
    }

    fun confirmNavigation(task: BrowserTask): BrowserTask {
        val url = task.pendingUrl ?: return task
        if (task.phase != BrowserTaskPhase.AWAITING_CONFIRMATION) return task
        val origin = InternalBrowserUrlPolicy.allowedOrigin(url) ?: return task
        return task.copy(
            phase = BrowserTaskPhase.NAVIGATING,
            currentUrl = url,
            pendingUrl = null,
            message = null,
            approvedOrigins = task.approvedOrigins + origin
        ).record()
    }

    fun rejectNavigation(task: BrowserTask, message: String): BrowserTask {
        if (task.phase != BrowserTaskPhase.AWAITING_CONFIRMATION) return task
        return task.copy(
            phase = BrowserTaskPhase.BLOCKED,
            pendingUrl = null,
            message = message
        ).record()
    }

    fun complete(task: BrowserTask, url: String? = task.currentUrl): BrowserTask {
        if (task.isTerminal || !task.windowVisible ||
            (task.phase != BrowserTaskPhase.NAVIGATING && task.phase != BrowserTaskPhase.SEARCHING)
        ) return task
        if (url != task.currentUrl) return task
        return task.copy(
            phase = BrowserTaskPhase.COMPLETED,
            currentUrl = url ?: task.currentUrl,
            pendingUrl = null,
            message = null
        ).record()
    }

    fun cancel(task: BrowserTask, message: String? = null): BrowserTask =
        if (task.isTerminal) task.copy(windowVisible = false) else task.copy(
            phase = BrowserTaskPhase.CANCELLED,
            pendingUrl = null,
            windowVisible = false,
            message = message
        ).record()

    fun block(task: BrowserTask, message: String): BrowserTask =
        if (task.isTerminal) task else task.copy(
            phase = BrowserTaskPhase.BLOCKED,
            pendingUrl = null,
            message = message
        ).record()

    fun fail(task: BrowserTask, message: String): BrowserTask =
        if (task.isTerminal) task else task.copy(
            phase = BrowserTaskPhase.FAILED,
            pendingUrl = null,
            message = message
        ).record()

    fun reduce(task: BrowserTask, event: BrowserTaskEvent): BrowserTask {
        if (event.taskId != task.taskId || event.navigationId != task.navigationId) return task
        return when (event.action) {
            BrowserTaskAction.VISIBLE -> begin(acknowledgeVisible(task))
            BrowserTaskAction.SELECT_RESULT -> if (event.userInitiated) {
                event.url?.let { selectSearchResult(task, it) } ?: task
            } else task
            BrowserTaskAction.REQUEST_NAVIGATION -> {
                val url = event.url ?: return task
                val allowed = InternalBrowserUrlPolicy.allowsNavigation(
                    task.initialUrl, url, additionalApprovedOrigins = task.approvedOrigins
                )
                requestNavigation(task, url, !allowed, event.userInitiated).let { next ->
                    if (event.query == null || next == task) next else next.copy(query = event.query)
                }
            }
            BrowserTaskAction.CONFIRM_NAVIGATION -> if (event.userInitiated) confirmNavigation(task) else task
            BrowserTaskAction.REJECT_NAVIGATION -> rejectNavigation(task, event.message ?: "Navigation declined")
            BrowserTaskAction.COMPLETE -> complete(task, event.url)
            BrowserTaskAction.FAIL -> fail(task, event.message ?: "Browser navigation failed")
            BrowserTaskAction.RETRY -> {
                if (task.phase != BrowserTaskPhase.FAILED && task.phase != BrowserTaskPhase.BLOCKED) task
                else begin(acknowledgeVisible(create(
                    task.owner, task.currentUrl ?: task.initialUrl, task.kind, task.sessionId,
                    task.messageId, task.conversationRevision, task.query
                ).copy(parentTaskId = task.taskId)))
            }
            BrowserTaskAction.CANCEL -> cancel(task, event.message)
        }
    }

    private fun BrowserTask.record(): BrowserTask = copy(
        events = (events + BrowserTaskEventRecord(navigationId, phase, pendingUrl ?: currentUrl, message)).takeLast(16)
    )
}

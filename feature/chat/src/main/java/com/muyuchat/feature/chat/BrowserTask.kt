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
    val message: String? = null
) {
    val isTerminal: Boolean
        get() = phase == BrowserTaskPhase.CANCELLED ||
            phase == BrowserTaskPhase.FAILED
}

object BrowserTaskStateMachine {
    private val nextTaskId = AtomicLong(0L)

    fun create(
        owner: String,
        initialUrl: String,
        kind: BrowserTaskKind = BrowserTaskKind.SOURCE
    ): BrowserTask = BrowserTask(
        taskId = "browser-${nextTaskId.incrementAndGet()}",
        owner = owner.ifBlank { "unknown" },
        kind = kind,
        initialUrl = initialUrl
    )

    fun begin(task: BrowserTask): BrowserTask {
        if (task.phase != BrowserTaskPhase.QUEUED) return task
        return task.copy(
            phase = if (task.kind == BrowserTaskKind.SEARCH) {
                BrowserTaskPhase.SEARCHING
            } else {
                BrowserTaskPhase.NAVIGATING
            },
            currentUrl = task.initialUrl,
            message = null
        )
    }

    /**
     * Records a requested URL. Cross-host URLs are never loaded implicitly;
     * they remain pending until [confirmNavigation] is called by the user.
     */
    fun requestNavigation(
        task: BrowserTask,
        url: String,
        requiresConfirmation: Boolean
    ): BrowserTask {
        if (task.isTerminal || url.isBlank()) return task
        return if (requiresConfirmation) {
            task.copy(
                phase = BrowserTaskPhase.AWAITING_CONFIRMATION,
                pendingUrl = url,
                message = null
            )
        } else {
            task.copy(
                phase = BrowserTaskPhase.NAVIGATING,
                currentUrl = url,
                pendingUrl = null,
                message = null
            )
        }
    }

    fun confirmNavigation(task: BrowserTask): BrowserTask {
        val url = task.pendingUrl ?: return task
        if (task.phase != BrowserTaskPhase.AWAITING_CONFIRMATION) return task
        return task.copy(
            phase = BrowserTaskPhase.NAVIGATING,
            currentUrl = url,
            pendingUrl = null,
            message = null
        )
    }

    fun rejectNavigation(task: BrowserTask, message: String): BrowserTask {
        if (task.phase != BrowserTaskPhase.AWAITING_CONFIRMATION) return task
        return task.copy(
            phase = BrowserTaskPhase.BLOCKED,
            pendingUrl = null,
            message = message
        )
    }

    fun complete(task: BrowserTask, url: String? = task.currentUrl): BrowserTask {
        if (task.phase == BrowserTaskPhase.CANCELLED) return task
        return task.copy(
            phase = BrowserTaskPhase.COMPLETED,
            currentUrl = url ?: task.currentUrl,
            pendingUrl = null,
            message = null
        )
    }

    fun cancel(task: BrowserTask, message: String? = null): BrowserTask =
        if (task.phase == BrowserTaskPhase.CANCELLED) task else task.copy(
            phase = BrowserTaskPhase.CANCELLED,
            pendingUrl = null,
            message = message
        )

    fun block(task: BrowserTask, message: String): BrowserTask =
        if (task.phase == BrowserTaskPhase.CANCELLED) task else task.copy(
            phase = BrowserTaskPhase.BLOCKED,
            pendingUrl = null,
            message = message
        )

    fun fail(task: BrowserTask, message: String): BrowserTask =
        if (task.phase == BrowserTaskPhase.CANCELLED) task else task.copy(
            phase = BrowserTaskPhase.FAILED,
            pendingUrl = null,
            message = message
        )
}

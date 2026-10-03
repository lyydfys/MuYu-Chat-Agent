package com.muyuchat.mca.presentation

import com.muyuchat.mca.ChatRuntimeContextPlan
import com.muyuchat.mca.KnowledgeBaseStore
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.WorldBookRecord
import com.muyuchat.mca.WorldBookScope

/**
 * The context-source operations: which of the selected sources are still usable for a chat turn,
 * and what the user is told about the sources this turn actually carried.
 *
 * ## Where this comes from
 *
 * Both decisions were inline inside the reply-generation coroutine of `MainViewModel`, in the block
 * that builds `contextStatus` right after `chatContextComposer.compose(...)`: a chain of `if`s over
 * `selectedKnowledgeBaseIds`, the assistant's file-context flag and `runtimeContextPlan`, then the
 * world-book applicability count, then one `updateGenerationUi { it.copy(statusMessage = ...) }`.
 * Moving it here means the decision can be read and tested without a 20,000-line class, and the
 * strings the user sees are pinned by tests instead of by reading.
 *
 * ## Why the operation is synchronous
 *
 * [ChatContextOperations.planTurnContext] performs its port call and returns the change to publish.
 * The caller keeps doing what it did before -- run it next to the composer on `Dispatchers.IO` and
 * publish the result through the gate that already exists -- so the dispatcher choice and the moment
 * the state becomes visible stay in the caller. Making this `suspend` would move the dispatcher in
 * here and re-time the publish, which is the change this refactor must not make.
 *
 * ## What the port is for
 *
 * The snapshot cannot answer whether a selected knowledge base still exists. A session's persisted
 * knowledge-base binding is restored straight from the store when the conversation is opened, and a
 * base deleted while another conversation was active stays in that binding; `compose` then retrieves
 * against an id that is gone and the user is told "本轮知识库未命中". [ChatContextAvailabilityPort]
 * answers that one question against storage. It is the only read the decision needs, so the port
 * exposes nothing else.
 *
 * ## Not wired yet
 *
 * Only [ChatContextOperations.turnContextStatus] is wired. The pruning of dead knowledge-base ids in
 * [ChatContextOperations.planTurnContext] is a real behaviour change and stays unwired until it is
 * authorised on its own.
 *
 * ## Failure semantics
 *
 * The port may throw. When it does, this operation does **not** prune anything: dropping the user's
 * selection because availability could not be read would silently disable retrieval. The failure is
 * therefore "keep the selection, still tell the user about this turn" -- never a cleared selection.
 */
internal interface ChatContextAvailabilityPort {
    /**
     * The ids of the knowledge bases that exist right now.
     *
     * The implementation reads storage, so the caller must invoke the operation off the main thread.
     */
    fun availableKnowledgeBaseIds(): Set<String>
}

/**
 * The state an operation needs before it decides anything.
 *
 * Deliberately narrow: five fields, each one actually read by [ChatContextOperations]. The two
 * assistant-dependent values are passed in already resolved, because the resolution chain
 * (`assistantSnapshot?.fileContextEnabled ?: selectedAssistant()?.fileContextEnabled ?: true`) reads
 * state this file must not reach for.
 */
internal data class ChatContextSnapshot(
    val selectedKnowledgeBaseIds: Set<String>,
    /** Already resolved by the caller; `true` is the documented fallback when no assistant says otherwise. */
    val fileContextEnabled: Boolean,
    val worldBooks: List<WorldBookRecord>,
    /** The assistant the turn is attributed to: a snapshot's id, or the selected one. */
    val assistantId: String,
    val activeChatSessionId: String?
)

/**
 * What the caller must publish after a turn's context was assembled.
 *
 * Every field is nullable and means "leave this alone" -- a turn that has nothing to report must not
 * rewrite the status line, and a turn that is not carrying a stale selection must not touch the
 * selection. [isNoOp] is the caller's guard so the atomic update can be skipped entirely.
 */
internal data class ChatContextChange(
    /**
     * Set only when the selection contains ids that no longer exist, in which case this is the
     * selection with those ids removed. Null means "every selected base is still usable".
     */
    val selectedKnowledgeBaseIds: Set<String>? = null,
    /** Null means "this turn has nothing to say"; it never means "clear the status line". */
    val statusMessage: String? = null
) {
    /** True when the caller has nothing to publish at all. */
    val isNoOp: Boolean
        get() = selectedKnowledgeBaseIds == null && statusMessage == null
}

internal object ChatContextOperations {

    /**
     * Decides what a finished turn's context assembly must change and what the user is told.
     *
     * [plan] is the result the composer just produced for this turn -- it is read, never re-run, so
     * this operation adds no second execution path and no second retrieval.
     */
    fun planTurnContext(
        port: ChatContextAvailabilityPort,
        snapshot: ChatContextSnapshot,
        plan: ChatRuntimeContextPlan
    ): ChatContextChange = ChatContextChange(
        selectedKnowledgeBaseIds = usableSelection(port, snapshot),
        statusMessage = turnContextStatus(snapshot, plan)
    )

    /**
     * How many world books apply to this turn at all.
     *
     * A book applies when it is enabled and its scope matches: GLOBAL always, ASSISTANT by assistant
     * id, CHAT by session id. Kept public to this file because the count is a decision of its own --
     * zero means the turn says nothing about world books, however the selection went.
     */
    fun applicableWorldBookCount(snapshot: ChatContextSnapshot): Int = snapshot.worldBooks.count { book ->
        book.enabled && when (book.scope) {
            WorldBookScope.GLOBAL -> true
            WorldBookScope.ASSISTANT -> book.assistantId == snapshot.assistantId
            WorldBookScope.CHAT -> book.chatSessionId == snapshot.activeChatSessionId
        }
    }

    /**
     * The selection with ids that no longer exist removed, or null when there is nothing to remove.
     *
     * Returns null -- "change nothing" -- in three cases: nothing is selected, the selection is
     * entirely usable, or the port could not be read. An empty selection is never reported as a
     * change on its own, so an untouched selection is never written back.
     */
    private fun usableSelection(
        port: ChatContextAvailabilityPort,
        snapshot: ChatContextSnapshot
    ): Set<String>? {
        if (snapshot.selectedKnowledgeBaseIds.isEmpty()) return null
        val available = runCatching { port.availableKnowledgeBaseIds() }
            .getOrElse { return null }
        val usable = snapshot.selectedKnowledgeBaseIds.intersect(available)
        return usable.takeIf { it != snapshot.selectedKnowledgeBaseIds }
    }

    /**
     * The one-line summary of what this turn carried, or null when there is nothing worth saying.
     *
     * The wording is reproduced verbatim from the inline block it replaces, including the separators
     * and the parenthetical explanations, because these strings are user-visible.
     *
     * Note which selection it reads: the **snapshot's**, not the pruned one. Pruning is a repair for
     * the next turn; letting it change this turn's wording would turn "本轮知识库未命中" into silence
     * for a turn that genuinely did retrieve nothing.
     */
    /**
     * The one-line summary of what this turn carried.
     *
     * `internal` rather than `private` so the caller can use the wording decision on its own. The
     * pruning decision inside [planTurnContext] is a **behaviour change** -- the inline version never
     * dropped ids -- so it is deliberately not wired yet; it needs its own authorisation and its own
     * test pinning the new behaviour.
     */
    internal fun turnContextStatus(
        snapshot: ChatContextSnapshot,
        plan: ChatRuntimeContextPlan
    ): String? = buildList {
        if (snapshot.selectedKnowledgeBaseIds.isNotEmpty()) {
            add(
                when {
                    // The role setting disables only document retrieval, so this is reported first.
                    !snapshot.fileContextEnabled -> "知识库检索已关闭（当前助手的文件上下文开关已关闭）"
                    plan.knowledge.chunks.isEmpty() -> "本轮知识库未命中（已按当前问题检索）"
                    else -> "本轮知识库命中 ${plan.knowledge.chunks.size} 段"
                }
            )
        }
        if (applicableWorldBookCount(snapshot) > 0) {
            add(
                if (plan.worldBook.selectedEntryIds.isEmpty()) {
                    "本轮世界书未命中关键词"
                } else {
                    "本轮世界书命中 ${plan.worldBook.selectedEntryIds.size} 条"
                }
            )
        }
    }.joinToString("；").takeIf { it.isNotBlank() }
}

/**
 * Reads the fields the context operations decide on.
 *
 * The two assistant-dependent values are parameters, not lookups: `selectedAssistant()` is private
 * to `MainViewModel`, and resolving the fallback chain is the caller's job. Keeping the snapshot
 * builder here means `MainViewModel` does not grow a helper for a migrated operation -- which matters,
 * because that class has methods close to the JVM 64 KB bytecode limit.
 */
internal fun MainUiState.chatContextSnapshot(
    assistantId: String,
    fileContextEnabled: Boolean
): ChatContextSnapshot = ChatContextSnapshot(
    selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
    fileContextEnabled = fileContextEnabled,
    worldBooks = worldBooks,
    assistantId = assistantId,
    activeChatSessionId = activeChatSessionId
)

/**
 * Applies a change in one step, so the caller can publish atomically.
 *
 * A field the operation did not decide on is left exactly as it was: the nulls in [ChatContextChange]
 * mean "unchanged", not "clear". A no-op returns the same instance, so the caller can assert that
 * nothing was published.
 */
internal fun MainUiState.withChatContextChange(change: ChatContextChange): MainUiState {
    if (change.isNoOp) return this
    return copy(
        selectedKnowledgeBaseIds = change.selectedKnowledgeBaseIds ?: selectedKnowledgeBaseIds,
        statusMessage = change.statusMessage ?: statusMessage
    )
}

/**
 * Adapts the knowledge-base store to [ChatContextAvailabilityPort].
 *
 * Kept beside the operations rather than beside the caller, so the direction stays explicit: storage
 * knows how to satisfy the port, and the operations never learn that storage exists.
 *
 * `loadBases()` blocks on `Dispatchers.IO` internally, which is why the caller must run the operation
 * on an IO dispatcher -- the same one the composer call beside it already uses.
 */
internal class StoreChatContextAvailabilityPort(
    private val knowledgeBaseStore: KnowledgeBaseStore
) : ChatContextAvailabilityPort {

    override fun availableKnowledgeBaseIds(): Set<String> =
        knowledgeBaseStore.loadBases().mapTo(mutableSetOf()) { it.id }
}

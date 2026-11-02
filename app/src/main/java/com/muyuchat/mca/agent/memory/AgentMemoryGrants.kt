package com.muyuchat.mca.agent.memory

import com.muyuchat.mca.agent.tools.AgentToolJson
import org.json.JSONArray

/**
 * Frozen memory selection and the two capability types that guard memory writes (plan §7.2).
 *
 * The grant model is intentionally narrower than the workspace grant:
 *
 * - a task selects space ids **at creation**; the selection is persisted once and can never be
 *   widened afterwards, only revoked ([AgentMemorySelectionService]);
 * - a read grant covers exactly the frozen ids. There is no "all spaces" value, no wildcard and no
 *   default that means "everything", so a missing or empty grant denies rather than broadens;
 * - a write needs an explicit [AgentMemoryWriteApproval] for *one* space. Space lifecycle actions
 *   need the separate [AgentMemoryWriteScope.SpaceAdmin] scope. No approval type authorizes both,
 *   and no approval is persisted in the run policy, so "always allow" cannot be represented.
 */
class AgentMemorySelection private constructor(
    val spaceIds: List<String>
) {
    init {
        require(spaceIds.isNotEmpty()) { "a memory selection needs at least one space" }
        require(spaceIds == spaceIds.distinct().sorted()) { "memory selection ids must be sorted and unique" }
        require(spaceIds.size <= AgentMemoryLimits.MAX_SELECTED_SPACES) {
            "a memory selection is limited to ${AgentMemoryLimits.MAX_SELECTED_SPACES} spaces"
        }
        require(spaceIds.all(::isValidSpaceId)) { "memory selection contains an invalid space id" }
    }

    /** Canonical persisted form: sorted JSON array, exactly what [selectionHash] hashes. */
    fun toJson(): String = AgentToolJson.canonical(JSONArray().also { array -> spaceIds.forEach(array::put) })

    fun hash(): String = AgentMemoryText.sha256(toJson())

    fun covers(scope: Collection<String>): Boolean =
        scope.isNotEmpty() && scope.all { it in spaceIds }

    override fun toString(): String = "AgentMemorySelection(${spaceIds.size} spaces)"

    companion object {
        fun of(spaceIds: Collection<String>): AgentMemorySelection =
            AgentMemorySelection(spaceIds.map(String::trim).distinct().sorted())

        fun single(spaceId: String): AgentMemorySelection = of(listOf(spaceId))

        fun isValidSpaceId(spaceId: String): Boolean =
            spaceId.length in 1..AgentMemoryLimits.MAX_SPACE_ID_CHARS &&
                SPACE_ID_PATTERN.matches(spaceId)

        private val SPACE_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
    }
}

/**
 * Read grant derived from a task's frozen selection.
 *
 * [revoked] is the authoritative tombstone: once the user revokes the task selection (or a selected
 * space is deleted), the executor observes `revoked=true` and denies before touching the store.
 */
data class AgentMemoryReadGrant(
    val taskId: String,
    val spaceIds: List<String>,
    val revoked: Boolean = false
) {
    init {
        require(taskId.isNotBlank()) { "grant task id must not be blank" }
        require(spaceIds.isNotEmpty()) { "a memory read grant needs at least one space" }
        require(spaceIds == spaceIds.distinct().sorted()) { "grant space ids must be sorted and unique" }
    }

    fun covers(scope: Collection<String>): Boolean = !revoked && scope.isNotEmpty() && scope.all { it in spaceIds }
}

/** Why a memory write was authorized; recorded for the admin surface and tests, never for a model. */
enum class AgentMemoryWriteReason {
    SPACE_ADMIN,
    USER_IMPORT,
    USER_REVOKE,
    USER_DELETE,
    USER_EDIT,

    /** Undelete/restore of a tombstoned item (user-initiated management). */
    USER_RESTORE,

    /** Acceptance of a reviewed extraction candidate (user-initiated review). */
    USER_EXTRACT_ACCEPT
}

/** What a memory write approval covers. The two variants never overlap. */
sealed interface AgentMemoryWriteScope {
    /** Create or delete the space container itself. Never authorizes item content. */
    data object SpaceAdmin : AgentMemoryWriteScope

    /** One concrete space's item content. [spaceId] is a real id; a wildcard cannot be expressed. */
    data class Items(val spaceId: String) : AgentMemoryWriteScope {
        init {
            require(AgentMemorySelection.isValidSpaceId(spaceId)) {
                "a memory item approval must name one concrete space id"
            }
        }
    }
}

/**
 * Explicit one-space user approval for a memory write.
 *
 * Deliberately a plain value object created by the admin surface for the *current* user action; it
 * is not persisted, not cached and not derived from the run policy. A model tool cannot mint it,
 * which is why P1 exposes no write tool at all.
 */
data class AgentMemoryWriteApproval(
    val principal: String,
    val reason: AgentMemoryWriteReason,
    val scope: AgentMemoryWriteScope,
    val approvedAtMillis: Long
) {
    init {
        require(principal.isNotBlank()) { "memory write approval needs a principal" }
        require(approvedAtMillis > 0L) { "memory write approval needs a positive timestamp" }
    }

    fun authorizesSpaceAdmin(): Boolean = scope is AgentMemoryWriteScope.SpaceAdmin

    fun authorizesItems(spaceId: String): Boolean =
        scope is AgentMemoryWriteScope.Items && scope.spaceId == spaceId

    override fun toString(): String = "AgentMemoryWriteApproval(reason=$reason, scope=$scope)"

    companion object {
        fun spaceAdmin(
            principal: String,
            reason: AgentMemoryWriteReason = AgentMemoryWriteReason.SPACE_ADMIN,
            approvedAtMillis: Long
        ): AgentMemoryWriteApproval = AgentMemoryWriteApproval(
            principal = principal,
            reason = reason,
            scope = AgentMemoryWriteScope.SpaceAdmin,
            approvedAtMillis = approvedAtMillis
        )

        fun items(
            spaceId: String,
            principal: String,
            reason: AgentMemoryWriteReason,
            approvedAtMillis: Long
        ): AgentMemoryWriteApproval = AgentMemoryWriteApproval(
            principal = principal,
            reason = reason,
            scope = AgentMemoryWriteScope.Items(spaceId),
            approvedAtMillis = approvedAtMillis
        )
    }
}

/** Narrow read seam so the tool depends on grant resolution, not on the whole admin service. */
interface AgentMemoryReadGrantSource {
    suspend fun grantForTask(taskId: String): AgentMemoryReadGrant?
}

/** Outcome of freezing a task's memory selection. */
sealed interface AgentMemorySelectionOutcome {
    data class Frozen(val selection: AgentMemorySelection) : AgentMemorySelectionOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemorySelectionOutcome
}

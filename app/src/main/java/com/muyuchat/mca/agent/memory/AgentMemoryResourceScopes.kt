package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.contract.AgentEffectKind
import com.muyuchat.core.agent.contract.AgentResourceAccess
import com.muyuchat.core.agent.contract.AgentResourceScope
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.contract.AgentScopePreconditionCodec
import com.muyuchat.core.agent.contract.FrozenToolCommand

/**
 * Typed resource identity of `memory.search` (N02c).
 *
 * V1 represented memory by the fake workspace path `memory`, so an approval could not state which
 * spaces would be read. The typed scope names the exact frozen space ids and the effect class; the
 * executor re-derives it from the current read grant and rejects a mismatch
 * ([com.muyuchat.core.agent.contract.AgentErrorCodes.RESOURCE_SCOPE_OUTDATED]) instead of executing
 * under an outdated or fabricated scope.
 */
object AgentMemoryResourceScopes {
    /** Null when the task has no frozen selection: memory.search must fail closed. */
    fun precondition(spaceIds: Collection<String>): AgentScopePrecondition? {
        val normalized = spaceIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        if (normalized.isEmpty()) return null
        return AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Memory(
                    spaceIds = normalized,
                    access = AgentResourceAccess.READ
                )
            ),
            effects = setOf(AgentEffectKind.LOCAL_READ)
        )
    }

    /**
     * True only when the frozen command carries exactly this typed scope and the fingerprint bound
     * to the approval. A V1 pseudo-path precondition (or any other drift) returns false.
     */
    fun matches(command: FrozenToolCommand, expected: AgentScopePrecondition): Boolean {
        val canonical = AgentScopePreconditionCodec.canonicalJson(expected)
        return command.resourcePreconditionsJson == canonical &&
            command.resourceFingerprint == AgentScopePreconditionCodec.fingerprintJson(canonical)
    }
}

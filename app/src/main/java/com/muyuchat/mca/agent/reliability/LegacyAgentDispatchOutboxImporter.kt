package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStore
import com.muyuchat.mca.agent.data.RoomAgentDispatchOutboxStore
import kotlinx.coroutines.CancellationException

/**
 * One-time bridge from the legacy file outbox to the Room outbox.
 *
 * It copies only non-terminal rows and preserves their exact status, lease and revision, so a
 * `DISPATCHING` legacy row is still fenced by the startup reconciler and never resent. Import is
 * idempotent by idempotency key and is safe to call on every startup. The legacy document is left
 * untouched as evidence; it is never written again.
 */
class LegacyAgentDispatchOutboxImporter(
    private val legacy: AgentDispatchOutboxStore,
    private val room: RoomAgentDispatchOutboxStore,
    private val limit: Int = DEFAULT_IMPORT_LIMIT
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }

    suspend fun import(): Int {
        val active = try {
            legacy.listActive(limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A corrupt legacy document is preserved for review; Room starts empty and the
            // startup reconciler still fences any leftover APPROVED invocation.
            return 0
        }
        var imported = 0
        for (entry in active) {
            try {
                if (room.importLegacy(entry)) imported += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Leave this row for the next startup; one failure must not block the others.
            }
        }
        return imported
    }

    private companion object {
        const val DEFAULT_IMPORT_LIMIT = 256
    }
}

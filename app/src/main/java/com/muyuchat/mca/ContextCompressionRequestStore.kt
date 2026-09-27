package com.muyuchat.mca

/** A manual compression request belongs to the conversation where the user made it. */
internal class ContextCompressionRequestStore {
    private val pendingSessionIds = linkedSetOf<String>()

    @Synchronized
    fun request(sessionId: String): Boolean {
        val owner = sessionId.trim()
        if (owner.isBlank()) return false
        return pendingSessionIds.add(owner)
    }

    @Synchronized
    fun isPending(sessionId: String?): Boolean =
        !sessionId.isNullOrBlank() && sessionId in pendingSessionIds

    @Synchronized
    fun consume(sessionId: String?): Boolean {
        val owner = sessionId?.trim()?.takeIf(String::isNotBlank) ?: return false
        return pendingSessionIds.remove(owner)
    }

    @Synchronized
    fun clear(sessionId: String?) {
        sessionId?.trim()?.takeIf(String::isNotBlank)?.let(pendingSessionIds::remove)
    }

    @Synchronized
    fun clearAll() {
        pendingSessionIds.clear()
    }
}

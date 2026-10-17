package com.muyuchat.mca

/** Pure scope-owner filtering used by persistence and lifecycle cleanup tests. */
internal fun List<WorldBookRecord>.withoutScopedOwner(
    scope: WorldBookScope,
    ownerId: String? = null
): List<WorldBookRecord> {
    if (scope == WorldBookScope.GLOBAL) return this
    return filterNot { book ->
        if (book.scope != scope) return@filterNot false
        when (scope) {
            WorldBookScope.ASSISTANT -> ownerId == null || book.assistantId == ownerId
            WorldBookScope.CHAT -> ownerId == null || book.chatSessionId == ownerId
            WorldBookScope.GLOBAL -> false
        }
    }
}

/** Pure batch filtering used after the corresponding owner transaction commits. */
internal fun List<WorldBookRecord>.withoutScopedOwners(
    scope: WorldBookScope,
    ownerIds: Set<String>
): List<WorldBookRecord> {
    if (scope == WorldBookScope.GLOBAL || ownerIds.isEmpty()) return this
    return filterNot { book ->
        when (scope) {
            WorldBookScope.ASSISTANT ->
                book.scope == scope && book.assistantId in ownerIds
            WorldBookScope.CHAT ->
                book.scope == scope && book.chatSessionId in ownerIds
            WorldBookScope.GLOBAL -> false
        }
    }
}

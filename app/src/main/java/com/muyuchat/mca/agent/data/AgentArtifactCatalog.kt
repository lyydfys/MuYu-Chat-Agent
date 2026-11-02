package com.muyuchat.mca.agent.data

/**
 * Read-only lookup of committed logical artifacts. The workspace read side needs it because the
 * physical immutable version (`artifacts/.versions/<artifactId>_<commitId>.blob`) is deliberately not
 * addressable by a logical path; the newest committed artifact row maps `artifacts/report.md` to its
 * physical version. Implemented by [AgentArtifactLedger]/[RoomAgentArtifactLedger].
 */
interface AgentArtifactCatalog {
    suspend fun latestCommitted(taskId: String, logicalPath: String): AgentStoredArtifact?

    /** Bounded list of latest committed artifacts for a task, ordered by logical path. */
    suspend fun listLatestCommitted(taskId: String, limit: Int): List<AgentStoredArtifact>
}

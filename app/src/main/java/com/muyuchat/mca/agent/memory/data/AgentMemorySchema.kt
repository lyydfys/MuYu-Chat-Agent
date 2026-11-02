package com.muyuchat.mca.agent.memory.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration 28 → 29: create the four memory tables and their indices.
 *
 * Additive only. No existing table is read, altered or rewritten, so chat/assistant/knowledge/Agent
 * rows are untouched; the migration is forward-only, and a code rollback must not downgrade the
 * database (the memory tables then simply stay unused).
 *
 * The SQL is the same text Room would generate for [AgentMemorySpaceEntity] and friends, so the
 * pure-JVM test can execute it with sqlite-jdbc and compare the resulting PRAGMA shape with the
 * exported `29.json` and with the entity declarations. The wrapper that registers it on the single
 * `mca.db` builder lives in `ChatSessionStoreRoomDatabase.kt`; nothing in this package self-registers.
 */
object AgentMemorySchema {
    const val SOURCE_VERSION: Int = 28
    const val TARGET_VERSION: Int = 29

    val MIGRATION_28_29: Migration = object : Migration(SOURCE_VERSION, TARGET_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CREATE_STATEMENTS.forEach(db::execSQL)
            INDEX_STATEMENTS.forEach(db::execSQL)
        }
    }

    /** Typed registration hook for the database owner. */
    fun memoryMigrations(): Array<Migration> = arrayOf(MIGRATION_28_29)

    val CREATE_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `agent_memory_spaces` (`spaceId` TEXT NOT NULL, " +
            "`displayName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`revision` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL DEFAULT 1, " +
            "PRIMARY KEY(`spaceId`))",
        "CREATE TABLE IF NOT EXISTS `agent_memory_items` (`memoryId` TEXT NOT NULL, " +
            "`spaceId` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, " +
            "`contentHash` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceRef` TEXT, " +
            "`tokenCount` INTEGER NOT NULL, `chunkCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `revision` INTEGER NOT NULL, " +
            "`revoked` INTEGER NOT NULL DEFAULT 0, `revokedAt` INTEGER, " +
            "`schemaVersion` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`memoryId`), " +
            "FOREIGN KEY(`spaceId`) REFERENCES `agent_memory_spaces`(`spaceId`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE TABLE IF NOT EXISTS `agent_memory_chunks` (`chunkId` TEXT NOT NULL, " +
            "`memoryId` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
            "`text` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `estimatedTokens` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL DEFAULT 1, " +
            "PRIMARY KEY(`chunkId`), " +
            "FOREIGN KEY(`memoryId`) REFERENCES `agent_memory_items`(`memoryId`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`spaceId`) REFERENCES `agent_memory_spaces`(`spaceId`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE TABLE IF NOT EXISTS `agent_memory_task_selections` (`taskId` TEXT NOT NULL, " +
            "`spaceIdsJson` TEXT NOT NULL, `selectionHash` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`revoked` INTEGER NOT NULL DEFAULT 0, `revokedAt` INTEGER, " +
            "`schemaVersion` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`taskId`))"
    )

    val INDEX_STATEMENTS: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_agent_memory_spaces_createdAt` " +
            "ON `agent_memory_spaces` (`createdAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_memory_items_spaceId_contentHash` " +
            "ON `agent_memory_items` (`spaceId`, `contentHash`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_memory_items_spaceId_revoked_updatedAt` " +
            "ON `agent_memory_items` (`spaceId`, `revoked`, `updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_memory_items_contentHash` " +
            "ON `agent_memory_items` (`contentHash`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_memory_chunks_memoryId_position` " +
            "ON `agent_memory_chunks` (`memoryId`, `position`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_memory_chunks_spaceId_memoryId_position` " +
            "ON `agent_memory_chunks` (`spaceId`, `memoryId`, `position`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_memory_chunks_contentHash` " +
            "ON `agent_memory_chunks` (`contentHash`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_memory_task_selections_selectionHash` " +
            "ON `agent_memory_task_selections` (`selectionHash`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_memory_task_selections_revoked` " +
            "ON `agent_memory_task_selections` (`revoked`)"
    )

    /** Table names that must exist after 28 → 29; asserted by the migration tests. */
    val TABLE_NAMES: List<String> = listOf(
        "agent_memory_spaces",
        "agent_memory_items",
        "agent_memory_chunks",
        "agent_memory_task_selections"
    )
}

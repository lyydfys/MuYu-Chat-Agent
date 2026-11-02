package com.muyuchat.mca.agent.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration 32 → 33: create `agent_park_tokens` and its unique `wakeId` index.
 *
 * Additive only. No existing table is read, altered or rewritten, so chat/assistant/knowledge/Agent
 * rows are untouched; the migration is forward-only, and a code rollback must not downgrade the
 * database (the table then simply stays unused).
 *
 * The SQL is the exact text Room generates for [AgentParkTokenEntity] (`NOT NULL` for non-null
 * fields, `INTEGER`/`TEXT` affinities, no defaults), so the JVM migration test can execute it with
 * sqlite-jdbc and compare the resulting PRAGMA shape with the entity declaration. The builder
 * registration lives in `ChatSessionStoreRoomDatabase.kt`; nothing in this package self-registers.
 */
object AgentParkTokenSchema {
    const val SOURCE_VERSION: Int = 32
    const val TARGET_VERSION: Int = 33

    val MIGRATION_32_33: Migration = object : Migration(SOURCE_VERSION, TARGET_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CREATE_STATEMENTS.forEach(db::execSQL)
            INDEX_STATEMENTS.forEach(db::execSQL)
        }
    }

    /** Typed registration hook for the database owner. */
    fun parkTokenMigrations(): Array<Migration> = arrayOf(MIGRATION_32_33)

    val CREATE_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `agent_park_tokens` (`runId` TEXT NOT NULL, " +
            "`epoch` INTEGER NOT NULL, `checkpointRevision` INTEGER NOT NULL, " +
            "`wakeId` TEXT NOT NULL, `kind` TEXT NOT NULL, `approvalId` TEXT, " +
            "`invocationId` TEXT, `questionId` TEXT, `transcriptJson` TEXT NOT NULL, " +
            "`createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`runId`))"
    )

    val INDEX_STATEMENTS: List<String> = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_park_tokens_wakeId` " +
            "ON `agent_park_tokens` (`wakeId`)"
    )
}

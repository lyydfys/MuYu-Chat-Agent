package com.muyuchat.mca.agent.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive 33 → 34: the durable approved-dispatch outbox table. */
object AgentDispatchOutboxSchema {
    val CREATE_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `agent_dispatch_outbox` (" +
            "`idempotencyKey` TEXT NOT NULL, " +
            "`invocationId` TEXT NOT NULL, " +
            "`runId` TEXT NOT NULL, " +
            "`stepId` TEXT NOT NULL, " +
            "`callOrdinal` INTEGER NOT NULL, " +
            "`toolKey` TEXT NOT NULL, " +
            "`toolVersion` TEXT NOT NULL, " +
            "`approvedAtMillis` INTEGER NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`leaseOwner` TEXT, " +
            "`leaseExpiresAtMillis` INTEGER NOT NULL, " +
            "`dispatchAttempts` INTEGER NOT NULL, " +
            "`settledInvocationStatus` TEXT, " +
            "`errorCode` TEXT, " +
            "`updatedAtMillis` INTEGER NOT NULL, " +
            "`revision` INTEGER NOT NULL, " +
            "PRIMARY KEY(`idempotencyKey`))"
    )

    val INDEX_STATEMENTS: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_agent_dispatch_outbox_status_updatedAtMillis` " +
            "ON `agent_dispatch_outbox` (`status`, `updatedAtMillis`)"
    )

    val MIGRATION_33_34: Migration = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CREATE_STATEMENTS.forEach(db::execSQL)
            INDEX_STATEMENTS.forEach(db::execSQL)
        }
    }
}

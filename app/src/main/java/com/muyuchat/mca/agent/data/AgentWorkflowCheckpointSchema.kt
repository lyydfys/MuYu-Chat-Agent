package com.muyuchat.mca.agent.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive 34 → 35: durable graph frames move from the file CAS store into Room. */
object AgentWorkflowCheckpointSchema {
    val CREATE_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `agent_workflow_checkpoints` (" +
            "`frameKey` TEXT NOT NULL, " +
            "`revision` INTEGER NOT NULL, " +
            "`definitionId` TEXT NOT NULL, " +
            "`definitionVersion` INTEGER NOT NULL, " +
            "`payloadJson` TEXT NOT NULL, " +
            "`updatedAtMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`frameKey`))"
    )

    val MIGRATION_34_35: Migration = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CREATE_STATEMENTS.forEach(db::execSQL)
        }
    }
}

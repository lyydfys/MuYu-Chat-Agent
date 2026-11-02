package com.muyuchat.mca.agent.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration 27 → 28: create the Agent tables and indices.
 *
 * The exact table/column/index/foreign-key definitions live in [AGENT_SCHEMA_CREATE_STATEMENTS]
 * and [AGENT_SCHEMA_INDEX_STATEMENTS] so a pure-JVM test can execute them with sqlite-jdbc and
 * compare the resulting SQLite schema with these declarations. Room's own validation still runs
 * against the KSP-exported `28.json` in the instrumentation migration test.
 *
 * LEAD INTEGRATION (only the Lead may edit the database owner):
 * 1. Add the fifteen entities to `McaRoomDatabase`'s `entities` list and set `version = 28`.
 * 2. Register `MIGRATION_27_28` (or `agentMigrations()`) on the builder's `.addMigrations(...)`
 *    chain in `ChatSessionStoreRoomDatabase.kt` — nothing in this package self-registers.
 * 3. Add the DAO accessors (`agentTaskDao()`, `agentRunDao()`, … `agentCommandResultDao()`) to
 *    `McaRoomDatabase`; the exact names are listed in the K05 handoff report.
 * 4. Add `sourceSets.getByName("androidTest").assets.srcDir("schemas")` to `app/build.gradle.kts`
 *    so `MigrationTestHelper` can read `com.muyuchat.mca.McaRoomDatabase/27.json` and `28.json`
 *    from the test APK.
 * 5. Update `RoomSchemaContractTest`, which currently asserts each database exports exactly ONE
 *    schema version (it will fail once `28.json` exists); assert the highest exported version
 *    equals the source `@Database` version instead.
 */
object AgentDatabaseMigration {
    const val SOURCE_VERSION: Int = 27
    const val TARGET_VERSION: Int = 28

    /** Registered by the Lead on the single `mca.db` builder. */
    val MIGRATION_27_28: Migration = object : Migration(SOURCE_VERSION, TARGET_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            AGENT_SCHEMA_CREATE_STATEMENTS.forEach(db::execSQL)
            AGENT_SCHEMA_INDEX_STATEMENTS.forEach(db::execSQL)
        }
    }

    /** Typed registration hook for the Lead's migration chain. */
    fun agentMigrations(): Array<Migration> = arrayOf(MIGRATION_27_28)

    val AGENT_SCHEMA_CREATE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `agent_workspaces` (
            `workspaceId` TEXT NOT NULL,
            `displayName` TEXT NOT NULL,
            `storageRef` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`workspaceId`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_tasks` (
            `taskId` TEXT NOT NULL,
            `workspaceId` TEXT NOT NULL,
            `goal` TEXT NOT NULL,
            `displayTitle` TEXT NOT NULL,
            `lifecycle` TEXT NOT NULL,
            `lastRunId` TEXT,
            `displayStatus` TEXT NOT NULL,
            `revision` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `modelBindingJson` TEXT,
            `policyJson` TEXT,
            `budgetJson` TEXT,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`taskId`),
            FOREIGN KEY(`workspaceId`) REFERENCES `agent_workspaces`(`workspaceId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_runs` (
            `runId` TEXT NOT NULL,
            `taskId` TEXT NOT NULL,
            `runOrdinal` INTEGER NOT NULL,
            `state` TEXT NOT NULL,
            `reason` TEXT NOT NULL,
            `epoch` INTEGER NOT NULL,
            `modelBindingJson` TEXT NOT NULL,
            `policyJson` TEXT NOT NULL,
            `budgetJson` TEXT NOT NULL,
            `spentBudgetJson` TEXT NOT NULL,
            `lastSequence` INTEGER NOT NULL,
            `revision` INTEGER NOT NULL,
            `successorRunId` TEXT,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`runId`),
            FOREIGN KEY(`taskId`) REFERENCES `agent_tasks`(`taskId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_steps` (
            `stepId` TEXT NOT NULL,
            `runId` TEXT NOT NULL,
            `stepIndex` INTEGER NOT NULL,
            `kind` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `modelAttemptId` TEXT,
            `invocationId` TEXT,
            `startedAt` INTEGER NOT NULL,
            `endedAt` INTEGER,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`stepId`),
            FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_model_attempts` (
            `attemptId` TEXT NOT NULL,
            `runId` TEXT NOT NULL,
            `stepId` TEXT,
            `stepIndex` INTEGER NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `backendRequestId` TEXT NOT NULL,
            `backendIdentityRef` TEXT,
            `bindingFingerprint` TEXT NOT NULL,
            `terminal` TEXT NOT NULL,
            `usageJson` TEXT,
            `stopRequestedAt` INTEGER,
            `startedAt` INTEGER NOT NULL,
            `finishedAt` INTEGER,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`attemptId`),
            FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_invocations` (
            `invocationId` TEXT NOT NULL,
            `runId` TEXT NOT NULL,
            `stepId` TEXT NOT NULL,
            `callOrdinal` INTEGER NOT NULL,
            `toolKey` TEXT NOT NULL,
            `toolVersion` TEXT NOT NULL,
            `schemaHash` TEXT NOT NULL,
            `frozenArgumentsRef` TEXT,
            `argumentsHash` TEXT NOT NULL,
            `resourceFingerprint` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `idempotencyKey` TEXT NOT NULL,
            `resultRef` TEXT,
            `possibleSideEffects` INTEGER NOT NULL,
            `runningStepKey` TEXT,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`invocationId`),
            FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`stepId`) REFERENCES `agent_steps`(`stepId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_approvals` (
            `approvalId` TEXT NOT NULL,
            `runId` TEXT NOT NULL,
            `invocationId` TEXT NOT NULL,
            `principal` TEXT NOT NULL,
            `argumentsHash` TEXT NOT NULL,
            `toolSchemaHash` TEXT NOT NULL,
            `resourceFingerprint` TEXT NOT NULL,
            `policyVersion` INTEGER NOT NULL,
            `runEpoch` INTEGER NOT NULL,
            `expiresAt` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `decision` TEXT,
            `createdAt` INTEGER NOT NULL,
            `consumedAt` INTEGER,
            `pendingInvocationKey` TEXT,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`approvalId`),
            FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`invocationId`) REFERENCES `agent_invocations`(`invocationId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_questions` (
            `questionId` TEXT NOT NULL,
            `runId` TEXT NOT NULL,
            `stepIndex` INTEGER NOT NULL,
            `text` TEXT NOT NULL,
            `optionsJson` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `askedAt` INTEGER NOT NULL,
            `answeredAt` INTEGER,
            `answer` TEXT,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`questionId`),
            FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_events` (
            `eventId` TEXT NOT NULL,
            `runId` TEXT NOT NULL,
            `sequence` INTEGER NOT NULL,
            `type` TEXT NOT NULL,
            `version` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `commandId` TEXT,
            `causationId` TEXT,
            `redactedPayloadRef` TEXT,
            `summary` TEXT,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`eventId`),
            FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_commands` (
            `commandId` TEXT NOT NULL,
            `scopeId` TEXT NOT NULL,
            `commandType` TEXT NOT NULL,
            `contentHash` TEXT NOT NULL,
            `resultRef` TEXT NOT NULL,
            `appliedRevision` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`commandId`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_command_results` (
            `commandId` TEXT NOT NULL,
            `contentHash` TEXT NOT NULL,
            `appliedRevision` INTEGER NOT NULL,
            `taskRevision` INTEGER,
            `runId` TEXT,
            `createdAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`commandId`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_extensions` (
            `extensionId` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `version` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `sourceHash` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `manifestRef` TEXT,
            `installedAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`extensionId`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_grants` (
            `grantId` TEXT NOT NULL,
            `taskId` TEXT NOT NULL,
            `runId` TEXT,
            `scopeType` TEXT NOT NULL,
            `scopeJson` TEXT NOT NULL,
            `policyVersion` INTEGER NOT NULL,
            `modelFingerprint` TEXT,
            `grantedAt` INTEGER NOT NULL,
            `expiresAt` INTEGER,
            `revokedAt` INTEGER,
            `revokedReason` TEXT,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`grantId`),
            FOREIGN KEY(`taskId`) REFERENCES `agent_tasks`(`taskId`) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_artifacts` (
            `artifactId` TEXT NOT NULL,
            `taskId` TEXT NOT NULL,
            `invocationId` TEXT,
            `relativePath` TEXT NOT NULL,
            `mediaType` TEXT NOT NULL,
            `sizeBytes` INTEGER NOT NULL,
            `sha256` TEXT NOT NULL,
            `commitState` TEXT NOT NULL,
            `storageRef` TEXT NOT NULL,
            `version` INTEGER NOT NULL,
            `isLatest` INTEGER NOT NULL,
            `latestKey` TEXT,
            `commitId` TEXT,
            `createdAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`artifactId`),
            FOREIGN KEY(`taskId`) REFERENCES `agent_tasks`(`taskId`) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`invocationId`) REFERENCES `agent_invocations`(`invocationId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_artifact_commits` (
            `commitId` TEXT NOT NULL,
            `invocationId` TEXT NOT NULL,
            `artifactId` TEXT NOT NULL,
            `stagingRef` TEXT NOT NULL,
            `targetLogicalPath` TEXT NOT NULL,
            `expectedOldHash` TEXT,
            `newHash` TEXT NOT NULL,
            `newSizeBytes` INTEGER NOT NULL,
            `phase` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `schemaVersion` INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY(`commitId`),
            FOREIGN KEY(`invocationId`) REFERENCES `agent_invocations`(`invocationId`) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`artifactId`) REFERENCES `agent_artifacts`(`artifactId`) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent()
    )

    val AGENT_SCHEMA_INDEX_STATEMENTS: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_agent_workspaces_createdAt` ON `agent_workspaces` (`createdAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_tasks_workspaceId` ON `agent_tasks` (`workspaceId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_tasks_updatedAt` ON `agent_tasks` (`updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_tasks_lifecycle_updatedAt` ON `agent_tasks` (`lifecycle`, `updatedAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_runs_taskId_runOrdinal` ON `agent_runs` (`taskId`, `runOrdinal`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_runs_taskId_updatedAt` ON `agent_runs` (`taskId`, `updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_runs_state` ON `agent_runs` (`state`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_steps_runId_stepIndex` ON `agent_steps` (`runId`, `stepIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_steps_runId_status` ON `agent_steps` (`runId`, `status`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_steps_modelAttemptId` ON `agent_steps` (`modelAttemptId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_steps_invocationId` ON `agent_steps` (`invocationId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_model_attempts_backendRequestId` ON `agent_model_attempts` (`backendRequestId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_model_attempts_runId_stepIndex_ordinal` ON `agent_model_attempts` (`runId`, `stepIndex`, `ordinal`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_model_attempts_runId_stepId_ordinal` ON `agent_model_attempts` (`runId`, `stepId`, `ordinal`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_model_attempts_runId_terminal` ON `agent_model_attempts` (`runId`, `terminal`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_invocations_stepId_callOrdinal` ON `agent_invocations` (`stepId`, `callOrdinal`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_invocations_idempotencyKey` ON `agent_invocations` (`idempotencyKey`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_invocations_runningStepKey` ON `agent_invocations` (`runningStepKey`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_invocations_runId_status` ON `agent_invocations` (`runId`, `status`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_invocations_stepId` ON `agent_invocations` (`stepId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_invocations_runId` ON `agent_invocations` (`runId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_approvals_pendingInvocationKey` ON `agent_approvals` (`pendingInvocationKey`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_approvals_invocationId` ON `agent_approvals` (`invocationId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_approvals_runId_status` ON `agent_approvals` (`runId`, `status`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_approvals_status_expiresAt` ON `agent_approvals` (`status`, `expiresAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_approvals_runId` ON `agent_approvals` (`runId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_questions_runId_status` ON `agent_questions` (`runId`, `status`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_questions_runId_stepIndex` ON `agent_questions` (`runId`, `stepIndex`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_events_runId_sequence` ON `agent_events` (`runId`, `sequence`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_events_commandId` ON `agent_events` (`commandId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_events_createdAt` ON `agent_events` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_commands_scopeId_createdAt` ON `agent_commands` (`scopeId`, `createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_commands_contentHash` ON `agent_commands` (`contentHash`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_command_results_contentHash` ON `agent_command_results` (`contentHash`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_command_results_createdAt` ON `agent_command_results` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_extensions_kind_enabled` ON `agent_extensions` (`kind`, `enabled`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_extensions_sourceHash` ON `agent_extensions` (`sourceHash`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_extensions_updatedAt` ON `agent_extensions` (`updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_grants_taskId_scopeType` ON `agent_grants` (`taskId`, `scopeType`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_grants_runId` ON `agent_grants` (`runId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_grants_expiresAt` ON `agent_grants` (`expiresAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_artifacts_taskId_relativePath_version` ON `agent_artifacts` (`taskId`, `relativePath`, `version`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_artifacts_taskId_latestKey` ON `agent_artifacts` (`taskId`, `latestKey`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifacts_taskId_relativePath` ON `agent_artifacts` (`taskId`, `relativePath`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifacts_taskId_commitState` ON `agent_artifacts` (`taskId`, `commitState`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifacts_taskId_createdAt` ON `agent_artifacts` (`taskId`, `createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifacts_invocationId` ON `agent_artifacts` (`invocationId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifact_commits_artifactId` ON `agent_artifact_commits` (`artifactId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifact_commits_invocationId` ON `agent_artifact_commits` (`invocationId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifact_commits_phase_updatedAt` ON `agent_artifact_commits` (`phase`, `updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_artifact_commits_artifactId_updatedAt` ON `agent_artifact_commits` (`artifactId`, `updatedAt`)"
    )

    /** Names that must exist after the migration; asserted by the instrumentation migration test. */
    val AGENT_TABLE_NAMES: List<String> = listOf(
        "agent_workspaces",
        "agent_tasks",
        "agent_runs",
        "agent_steps",
        "agent_model_attempts",
        "agent_invocations",
        "agent_approvals",
        "agent_questions",
        "agent_events",
        "agent_commands",
        "agent_command_results",
        "agent_extensions",
        "agent_grants",
        "agent_artifacts",
        "agent_artifact_commits"
    )
}

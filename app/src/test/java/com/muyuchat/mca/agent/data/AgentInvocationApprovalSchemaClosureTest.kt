package com.muyuchat.mca.agent.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P0 persistence closure: the invocation/approval tables must make duplicate proposals,
 * duplicate running tools and duplicate pending approvals impossible in SQL, before any adapter
 * logic is trusted.
 *
 * This is a pure-JVM sqlite-jdbc check of the real 27 -> 28 statements, independent of the
 * existing broad schema contract test. It does not prove Room's generated DAO behavior; the Room
 * adapter CAS remains an instrumentation item.
 */
class AgentInvocationApprovalSchemaClosureTest {

    @Test
    fun invocationOrdinalAndIdempotencyKeyAreUnique() {
        withAgentDatabase { connection ->
            insertWorkspace(connection)
            insertTask(connection, taskId = "t1")
            insertRun(connection, runId = "r1", taskId = "t1")
            insertStep(connection, stepId = "s1", runId = "r1", index = 0)

            insertInvocation(
                connection,
                invocationId = "i1",
                stepId = "s1",
                callOrdinal = 0,
                idempotencyKey = "idem-1",
                runningStepKey = "s1"
            )

            assertSqlRejected("duplicate (stepId, callOrdinal) must never create a second proposal") {
                insertInvocation(
                    connection,
                    invocationId = "i2",
                    stepId = "s1",
                    callOrdinal = 0,
                    idempotencyKey = "idem-2",
                    runningStepKey = null
                )
            }

            assertSqlRejected("a second RUNNING invocation on the same step is impossible") {
                insertInvocation(
                    connection,
                    invocationId = "i3",
                    stepId = "s1",
                    callOrdinal = 1,
                    idempotencyKey = "idem-3",
                    runningStepKey = "s1"
                )
            }

            exec(connection, "UPDATE agent_invocations SET runningStepKey = NULL WHERE invocationId = 'i1'")
            insertInvocation(
                connection,
                invocationId = "i4",
                stepId = "s1",
                callOrdinal = 1,
                idempotencyKey = "idem-4",
                runningStepKey = null
            )

            assertSqlRejected("a replayed idempotency key must not duplicate the invocation") {
                insertInvocation(
                    connection,
                    invocationId = "i5",
                    stepId = "s1",
                    callOrdinal = 2,
                    idempotencyKey = "idem-4",
                    runningStepKey = null
                )
            }
        }
    }

    @Test
    fun atMostOnePendingApprovalPerInvocationAndForeignKeysRejectUnknownOwners() {
        withAgentDatabase { connection ->
            insertWorkspace(connection)
            insertTask(connection, taskId = "t1")
            insertRun(connection, runId = "r1", taskId = "t1")
            insertStep(connection, stepId = "s1", runId = "r1", index = 0)
            insertInvocation(
                connection,
                invocationId = "i1",
                stepId = "s1",
                callOrdinal = 0,
                idempotencyKey = "idem-1",
                runningStepKey = null
            )

            insertApproval(
                connection,
                approvalId = "p1",
                invocationId = "i1",
                status = "PENDING",
                pendingKey = "i1"
            )
            assertSqlRejected("one invocation may have only one pending approval") {
                insertApproval(
                    connection,
                    approvalId = "p2",
                    invocationId = "i1",
                    status = "PENDING",
                    pendingKey = "i1"
                )
            }

            insertApproval(
                connection,
                approvalId = "p3",
                invocationId = "i1",
                status = "CONSUMED",
                pendingKey = null
            )

            assertSqlRejected("an approval cannot reference a missing invocation") {
                insertApproval(
                    connection,
                    approvalId = "p4",
                    invocationId = "missing-invocation",
                    status = "PENDING",
                    pendingKey = "missing-invocation"
                )
            }

            assertSqlRejected("an invocation cannot reference a missing run (foreign run rejected)") {
                insertInvocation(
                    connection,
                    invocationId = "i9",
                    stepId = "s1",
                    callOrdinal = 9,
                    idempotencyKey = "idem-9",
                    runningStepKey = null,
                    runId = "foreign-run"
                )
            }
        }
    }

    @Test
    fun pendingInvocationKeyIndexIsUniqueNotJustTheAdapterConvention() {
        withAgentDatabase { connection ->
            val index = indexInfo(connection, "index_agent_approvals_pendingInvocationKey")
            assertTrue("pendingInvocationKey index must exist", index != null)
            assertTrue("pendingInvocationKey index must be UNIQUE", index!!.second)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Schema and inserts (mirrors the existing schema test; kept local for independence).
    // ---------------------------------------------------------------------------------------

    private fun withAgentDatabase(block: (Connection) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            exec(connection, "PRAGMA foreign_keys = ON")
            AgentDatabaseMigration.AGENT_SCHEMA_CREATE_STATEMENTS.forEach { exec(connection, it) }
            AgentDatabaseMigration.AGENT_SCHEMA_INDEX_STATEMENTS.forEach { exec(connection, it) }
            block(connection)
        }
    }

    private fun indexInfo(connection: Connection, name: String): Pair<String, Boolean>? {
        AgentDatabaseMigration.AGENT_TABLE_NAMES.forEach { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_list(`$table`)").use { cursor ->
                    while (cursor.next()) {
                        if (cursor.getString("name") == name) {
                            return table to (cursor.getInt("unique") == 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun exec(connection: Connection, sql: String) {
        connection.createStatement().use { it.executeUpdate(sql) }
    }

    private fun assertSqlRejected(message: String, block: () -> Unit) {
        try {
            block()
            fail(message)
        } catch (_: SQLException) {
            // Expected constraint rejection.
        }
    }

    private fun insertWorkspace(connection: Connection) {
        exec(
            connection,
            "INSERT INTO agent_workspaces (workspaceId, displayName, storageRef, createdAt, updatedAt) " +
                "VALUES ('w1', 'name', 'ref', 1, 1)"
        )
    }

    private fun insertTask(connection: Connection, taskId: String) {
        exec(
            connection,
            "INSERT INTO agent_tasks (taskId, workspaceId, goal, displayTitle, lifecycle, lastRunId, " +
                "displayStatus, revision, createdAt, updatedAt, modelBindingJson, policyJson, budgetJson) " +
                "VALUES ('$taskId', 'w1', 'goal', 'title', 'ACTIVE', NULL, 'IDLE', 0, 1, 1, NULL, NULL, NULL)"
        )
    }

    private fun insertRun(connection: Connection, runId: String, taskId: String) {
        exec(
            connection,
            "INSERT INTO agent_runs (runId, taskId, runOrdinal, state, reason, epoch, modelBindingJson, " +
                "policyJson, budgetJson, spentBudgetJson, lastSequence, revision, successorRunId, createdAt, updatedAt) " +
                "VALUES ('$runId', '$taskId', 1, 'RUNNING', 'NONE', 1, '{}', '{}', '{}', '{}', 0, 1, NULL, 1, 1)"
        )
    }

    private fun insertStep(connection: Connection, stepId: String, runId: String, index: Int) {
        exec(
            connection,
            "INSERT INTO agent_steps (stepId, runId, stepIndex, kind, status, modelAttemptId, invocationId, " +
                "startedAt, endedAt) VALUES ('$stepId', '$runId', $index, 'TOOL', 'WAITING', NULL, NULL, 1, NULL)"
        )
    }

    private fun insertInvocation(
        connection: Connection,
        invocationId: String,
        stepId: String,
        callOrdinal: Int,
        idempotencyKey: String,
        runningStepKey: String?,
        runId: String = "r1"
    ) {
        val running = runningStepKey?.let { "'$it'" } ?: "NULL"
        exec(
            connection,
            "INSERT INTO agent_invocations (invocationId, runId, stepId, callOrdinal, toolKey, toolVersion, " +
                "schemaHash, frozenArgumentsRef, argumentsHash, resourceFingerprint, status, idempotencyKey, " +
                "resultRef, possibleSideEffects, runningStepKey, createdAt, updatedAt) VALUES " +
                "('$invocationId', '$runId', '$stepId', $callOrdinal, 'artifact.write_text', '1.0.0', 'schema', " +
                "NULL, 'args', 'resource', 'PROPOSED', '$idempotencyKey', NULL, 0, $running, 1, 1)"
        )
    }

    private fun insertApproval(
        connection: Connection,
        approvalId: String,
        invocationId: String,
        status: String,
        pendingKey: String?
    ) {
        val pending = pendingKey?.let { "'$it'" } ?: "NULL"
        exec(
            connection,
            "INSERT INTO agent_approvals (approvalId, runId, invocationId, principal, argumentsHash, " +
                "toolSchemaHash, resourceFingerprint, policyVersion, runEpoch, expiresAt, status, decision, " +
                "createdAt, consumedAt, pendingInvocationKey) VALUES ('$approvalId', 'r1', '$invocationId', " +
                "'local-user', 'args', 'schema', 'resource', 1, 1, 999, '$status', NULL, 1, NULL, $pending)"
        )
    }
}

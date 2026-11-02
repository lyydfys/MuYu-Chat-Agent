package com.muyuchat.mca.agent.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM contract test for the 27 → 28 SQL.
 *
 * It builds the Agent schema with sqlite-jdbc, parses the real `@Entity` Kotlin sources and asserts
 * every declared property exists as a column in the same order, every declared index is unique or
 * plain exactly as declared, and every foreign key exists. The instrumentation
 * `AgentRoomMigrationTest` remains the authority for Room's own identity-hash validation; this test
 * catches drift inside the data layer before a device run.
 */
class AgentDatabaseSchemaSqlTest {
    @Test
    fun `agent schema creates every table with its declared columns in order`() {
        newDatabase().use { connection ->
            createAgentSchema(connection)
            val blocks = entityBlocks()
            assertEquals(
                "the entity files and the migration table list must agree",
                AgentDatabaseMigration.AGENT_TABLE_NAMES.toSet(),
                blocks.map { it.table }.toSet()
            )
            blocks.forEach { block ->
                val actual = columnNames(connection, block.table)
                assertEquals(
                    "${block.table}: entity properties and PRAGMA columns must match in order",
                    block.properties,
                    actual
                )
            }
            AgentDatabaseMigration.AGENT_TABLE_NAMES.forEach { table ->
                val cursor = connection.createStatement().executeQuery("PRAGMA table_info(`$table`)")
                var schemaVersionDefault: String? = null
                while (cursor.next()) {
                    if (cursor.getString("name") == "schemaVersion") {
                        schemaVersionDefault = cursor.getString("dflt_value")
                        assertEquals("$table.schemaVersion must be NOT NULL", 1, cursor.getInt("notnull"))
                    }
                }
                cursor.close()
                assertEquals("$table.schemaVersion must default to 1", "1", schemaVersionDefault)
            }
        }
    }

    @Test
    fun `agent schema creates every declared index with the right uniqueness`() {
        newDatabase().use { connection ->
            createAgentSchema(connection)
            val expected = AgentDatabaseMigration.AGENT_SCHEMA_INDEX_STATEMENTS.map { statement ->
                val match = requireNotNull(
                    Regex("CREATE (UNIQUE )?INDEX IF NOT EXISTS `([^`]+)` ON `([^`]+)`")
                        .find(statement)
                ) { "unparsable index statement: $statement" }
                IndexExpectation(
                    name = match.groupValues[2],
                    table = match.groupValues[3],
                    unique = match.groupValues[1].isNotBlank()
                )
            }
            assertTrue("expected a substantial index set", expected.size >= 40)
            expected.forEach { index ->
                val actual = indexInfo(connection, index.name)
                assertNotNull("index ${index.name} is missing", actual)
                assertEquals("index ${index.name} table", index.table, actual!!.table)
                assertEquals("index ${index.name} uniqueness", index.unique, actual.unique)
            }
        }
    }

    @Test
    fun `agent schema creates every declared foreign key`() {
        newDatabase().use { connection ->
            createAgentSchema(connection)
            val declared = AgentDatabaseMigration.AGENT_SCHEMA_CREATE_STATEMENTS.flatMap { statement ->
                val table = requireNotNull(
                    Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(statement)
                ).groupValues[1]
                Regex("FOREIGN KEY\\(`([^`]+)`\\) REFERENCES `([^`]+)`\\(`([^`]+)`\\)")
                    .findAll(statement)
                    .map { match ->
                        ForeignKeyExpectation(
                            table = table,
                            column = match.groupValues[1],
                            parentTable = match.groupValues[2],
                            parentColumn = match.groupValues[3]
                        )
                    }.toList()
            }
            assertTrue("expected foreign keys", declared.size >= 12)
            val actual = AgentDatabaseMigration.AGENT_TABLE_NAMES.flatMap { table ->
                foreignKeys(connection, table)
            }.toSet()
            assertEquals(declared.toSet(), actual)
        }
    }

    @Test
    fun `unique constraints reject duplicate identities`() {
        newDatabase().use { connection ->
            createAgentSchema(connection)
            exec(connection, "INSERT INTO agent_workspaces (workspaceId, displayName, storageRef, createdAt, updatedAt) VALUES ('w1', 'name', 'ref', 1, 1)")
            insertTask(connection, taskId = "t1", workspaceId = "w1")
            assertUniqueViolation {
                insertTask(connection, taskId = "t2", workspaceId = "w1")
            }
            insertRun(connection, runId = "r1", taskId = "t1", ordinal = 1)
            assertUniqueViolation {
                insertRun(connection, runId = "r2", taskId = "t1", ordinal = 1)
            }
            insertEvent(connection, eventId = "e1", runId = "r1", sequence = 1)
            assertUniqueViolation {
                insertEvent(connection, eventId = "e2", runId = "r1", sequence = 1)
            }
            insertStep(connection, stepId = "s1", runId = "r1", index = 0)
            insertAttempt(connection, attemptId = "a1", runId = "r1", stepIndex = 0, ordinal = 1, requestId = "req-1")
            assertUniqueViolation {
                insertAttempt(connection, attemptId = "a2", runId = "r1", stepIndex = 0, ordinal = 1, requestId = "req-2")
            }
            assertUniqueViolation {
                insertAttempt(connection, attemptId = "a3", runId = "r1", stepIndex = 1, ordinal = 1, requestId = "req-1")
            }
            insertInvocation(connection, invocationId = "i1", stepId = "s1", callOrdinal = 0, idempotencyKey = "idem-1", runningStepKey = "s1")
            assertUniqueViolation {
                insertInvocation(connection, invocationId = "i2", stepId = "s1", callOrdinal = 1, idempotencyKey = "idem-2", runningStepKey = "s1")
            }
            assertUniqueViolation {
                insertInvocation(connection, invocationId = "i3", stepId = "s1", callOrdinal = 0, idempotencyKey = "idem-3", runningStepKey = null)
            }
            exec(connection, "UPDATE agent_invocations SET runningStepKey = NULL WHERE invocationId = 'i1'")
            insertInvocation(connection, invocationId = "i4", stepId = "s1", callOrdinal = 1, idempotencyKey = "idem-4", runningStepKey = null)
            insertApproval(connection, approvalId = "p1", invocationId = "i1", pendingKey = "i1")
            assertUniqueViolation {
                insertApproval(connection, approvalId = "p2", invocationId = "i1", pendingKey = "i1")
            }
            insertApproval(connection, approvalId = "p3", invocationId = "i1", pendingKey = null)
            insertArtifact(connection, artifactId = "art1", taskId = "t1", version = 1, latestKey = "artifacts/a.txt")
            assertUniqueViolation {
                insertArtifact(connection, artifactId = "art2", taskId = "t1", version = 1, latestKey = null)
            }
            assertUniqueViolation {
                insertArtifact(connection, artifactId = "art3", taskId = "t1", version = 2, latestKey = "artifacts/a.txt")
            }
            insertArtifact(connection, artifactId = "art4", taskId = "t1", version = 2, latestKey = null)
            exec(connection, "INSERT INTO agent_commands (commandId, scopeId, commandType, contentHash, resultRef, appliedRevision, createdAt) VALUES ('c1', 't1', 'CREATE_TASK', 'h', '{}', 0, 1)")
            assertUniqueViolation {
                exec(connection, "INSERT INTO agent_commands (commandId, scopeId, commandType, contentHash, resultRef, appliedRevision, createdAt) VALUES ('c1', 't1', 'CREATE_TASK', 'h2', '{}', 0, 1)")
            }
        }
    }

    @Test
    fun `agent migration preserves a v27 chat fixture and reports the right versions`() {
        newDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE chat_sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)"
                )
                statement.executeUpdate(
                    "CREATE TABLE chat_messages (sessionId TEXT NOT NULL, position INTEGER NOT NULL, " +
                        "content TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(sessionId, position))"
                )
                statement.executeUpdate(
                    "CREATE TABLE assistants (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)"
                )
            }
            exec(connection, "INSERT INTO chat_sessions (id, title) VALUES ('s1', 'Legacy chat')")
            exec(connection, "INSERT INTO chat_messages (sessionId, position, content, createdAt) VALUES ('s1', 0, 'hello 世界', 123)")
            exec(connection, "INSERT INTO assistants (id, name) VALUES ('a1', 'Legacy assistant')")
            createAgentSchema(connection)
            assertEquals("Legacy chat", singleString(connection, "SELECT title FROM chat_sessions WHERE id = 's1'"))
            assertEquals(
                "hello 世界",
                singleString(connection, "SELECT content FROM chat_messages WHERE sessionId = 's1' AND position = 0")
            )
            assertEquals("Legacy assistant", singleString(connection, "SELECT name FROM assistants WHERE id = 'a1'"))
            assertEquals("ok", singleString(connection, "PRAGMA integrity_check"))
            assertEquals(27, AgentDatabaseMigration.MIGRATION_27_28.startVersion)
            assertEquals(28, AgentDatabaseMigration.MIGRATION_27_28.endVersion)
            assertEquals(28, AgentDatabaseMigration.SOURCE_VERSION + 1)
        }
    }

    private fun createAgentSchema(connection: Connection) {
        AgentDatabaseMigration.AGENT_SCHEMA_CREATE_STATEMENTS.forEach { statement ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }
        AgentDatabaseMigration.AGENT_SCHEMA_INDEX_STATEMENTS.forEach { statement ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    private fun newDatabase(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    private fun columnNames(connection: Connection, table: String): List<String> {
        val names = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.next()) names += cursor.getString("name")
            }
        }
        return names
    }

    private data class IndexInfo(val table: String, val unique: Boolean)

    private fun indexInfo(connection: Connection, name: String): IndexInfo? {
        return AgentDatabaseMigration.AGENT_TABLE_NAMES.asSequence().mapNotNull { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_list(`$table`)").use { cursor ->
                    while (cursor.next()) {
                        if (cursor.getString("name") == name) {
                            return@mapNotNull IndexInfo(table, cursor.getInt("unique") == 1)
                        }
                    }
                }
            }
            null
        }.firstOrNull()
    }

    private data class IndexExpectation(val name: String, val table: String, val unique: Boolean)

    private data class ForeignKeyExpectation(
        val table: String,
        val column: String,
        val parentTable: String,
        val parentColumn: String
    )

    private fun foreignKeys(connection: Connection, table: String): Set<ForeignKeyExpectation> {
        val keys = linkedSetOf<ForeignKeyExpectation>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                while (cursor.next()) {
                    keys += ForeignKeyExpectation(
                        table = table,
                        column = cursor.getString("from"),
                        parentTable = cursor.getString("table"),
                        parentColumn = cursor.getString("to")
                    )
                }
            }
        }
        return keys
    }

    private fun exec(connection: Connection, sql: String) {
        connection.createStatement().use { it.executeUpdate(sql) }
    }

    private fun assertUniqueViolation(block: () -> Unit) {
        try {
            block()
            fail("expected a UNIQUE constraint violation")
        } catch (_: SQLException) {
            // expected
        }
    }

    private fun singleString(connection: Connection, sql: String): String? {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { cursor ->
                return if (cursor.next()) cursor.getString(1) else null
            }
        }
    }

    private fun insertTask(connection: Connection, taskId: String, workspaceId: String) {
        exec(
            connection,
            "INSERT INTO agent_tasks (taskId, workspaceId, goal, displayTitle, lifecycle, lastRunId, " +
                "displayStatus, revision, createdAt, updatedAt, modelBindingJson, policyJson, budgetJson) " +
                "VALUES ('$taskId', '$workspaceId', 'goal', 'title', 'ACTIVE', NULL, 'IDLE', 0, 1, 1, NULL, NULL, NULL)"
        )
    }

    private fun insertRun(connection: Connection, runId: String, taskId: String, ordinal: Long) {
        exec(
            connection,
            "INSERT INTO agent_runs (runId, taskId, runOrdinal, state, reason, epoch, modelBindingJson, " +
                "policyJson, budgetJson, spentBudgetJson, lastSequence, revision, successorRunId, createdAt, updatedAt) " +
                "VALUES ('$runId', '$taskId', $ordinal, 'RUNNING', 'NONE', 1, '{}', '{}', '{}', '{}', 0, 0, NULL, 1, 1)"
        )
    }

    private fun insertEvent(connection: Connection, eventId: String, runId: String, sequence: Long) {
        exec(
            connection,
            "INSERT INTO agent_events (eventId, runId, sequence, type, version, createdAt, commandId, " +
                "causationId, redactedPayloadRef, summary) VALUES ('$eventId', '$runId', $sequence, 'run.state_changed', 1, 1, NULL, NULL, NULL, NULL)"
        )
    }

    private fun insertStep(connection: Connection, stepId: String, runId: String, index: Int) {
        exec(
            connection,
            "INSERT INTO agent_steps (stepId, runId, stepIndex, kind, status, modelAttemptId, invocationId, " +
                "startedAt, endedAt) VALUES ('$stepId', '$runId', $index, 'TOOL', 'RUNNING', NULL, NULL, 1, NULL)"
        )
    }

    private fun insertAttempt(
        connection: Connection,
        attemptId: String,
        runId: String,
        stepIndex: Int,
        ordinal: Int,
        requestId: String
    ) {
        exec(
            connection,
            "INSERT INTO agent_model_attempts (attemptId, runId, stepId, stepIndex, ordinal, backendRequestId, " +
                "backendIdentityRef, bindingFingerprint, terminal, usageJson, stopRequestedAt, startedAt, finishedAt) " +
                "VALUES ('$attemptId', '$runId', NULL, $stepIndex, $ordinal, '$requestId', NULL, 'binding', 'RUNNING', NULL, NULL, 1, NULL)"
        )
    }

    private fun insertInvocation(
        connection: Connection,
        invocationId: String,
        stepId: String,
        callOrdinal: Int,
        idempotencyKey: String,
        runningStepKey: String?
    ) {
        val running = if (runningStepKey == null) "NULL" else "'$runningStepKey'"
        exec(
            connection,
            "INSERT INTO agent_invocations (invocationId, runId, stepId, callOrdinal, toolKey, toolVersion, " +
                "schemaHash, frozenArgumentsRef, argumentsHash, resourceFingerprint, status, idempotencyKey, " +
                "resultRef, possibleSideEffects, runningStepKey, createdAt, updatedAt) VALUES " +
                "('$invocationId', 'r1', '$stepId', $callOrdinal, 'workspace.read_text', '1.0.0', 'schema', NULL, " +
                "'args', 'resource', 'RUNNING', '$idempotencyKey', NULL, 0, $running, 1, 1)"
        )
    }

    private fun insertApproval(
        connection: Connection,
        approvalId: String,
        invocationId: String,
        pendingKey: String?
    ) {
        val pending = if (pendingKey == null) "NULL" else "'$pendingKey'"
        val status = if (pendingKey == null) "CONSUMED" else "PENDING"
        exec(
            connection,
            "INSERT INTO agent_approvals (approvalId, runId, invocationId, principal, argumentsHash, " +
                "toolSchemaHash, resourceFingerprint, policyVersion, runEpoch, expiresAt, status, decision, " +
                "createdAt, consumedAt, pendingInvocationKey) VALUES ('$approvalId', 'r1', '$invocationId', " +
                "'local-user', 'args', 'schema', 'resource', 1, 1, 999, '$status', NULL, 1, NULL, $pending)"
        )
    }

    private fun insertArtifact(
        connection: Connection,
        artifactId: String,
        taskId: String,
        version: Long,
        latestKey: String?
    ) {
        val latest = if (latestKey == null) "NULL" else "'$latestKey'"
        val isLatest = if (latestKey == null) 0 else 1
        exec(
            connection,
            "INSERT INTO agent_artifacts (artifactId, taskId, invocationId, relativePath, mediaType, sizeBytes, " +
                "sha256, commitState, storageRef, version, isLatest, latestKey, commitId, createdAt) VALUES " +
                "('$artifactId', '$taskId', NULL, 'artifacts/a.txt', 'text/plain', 1, '${"a".repeat(64)}', " +
                "'COMMITTED', 'artifacts/.versions/x', $version, $isLatest, $latest, NULL, 1)"
        )
    }

    private data class EntityBlock(
        val table: String,
        val className: String,
        val properties: List<String>
    )

    private fun entityBlocks(): List<EntityBlock> {
        val files = listOf(
            "AgentTaskRunEntities.kt",
            "AgentExecutionEntities.kt",
            "AgentArtifactEntities.kt",
            "AgentSupportEntities.kt"
        )
        val blocks = mutableListOf<EntityBlock>()
        val sourceDirectory = File(
            projectRoot(),
            "app/src/main/java/com/muyuchat/mca/agent/data"
        )
        require(sourceDirectory.isDirectory) { "agent data source directory is missing" }
        files.forEach { name ->
            val text = File(sourceDirectory, name).readText()
            var search = 0
            while (true) {
                val annotationStart = text.indexOf("@Entity(", search)
                if (annotationStart < 0) break
                val open = annotationStart + "@Entity".length
                val close = matchingParen(text, open)
                val annotation = text.substring(open + 1, close)
                val table = Regex("tableName\\s*=\\s*\"([^\"]+)\"")
                    .find(annotation)?.groupValues?.get(1) ?: continue
                val classStart = text.indexOf("data class ", close)
                require(classStart > close) { "$name: data class after @Entity not found" }
                val className = Regex("data class (\\w+)").find(text.substring(classStart))
                    ?.groupValues?.get(1) ?: continue
                val constructorOpen = text.indexOf('(', classStart)
                val constructorClose = matchingParen(text, constructorOpen)
                val constructor = text.substring(constructorOpen + 1, constructorClose)
                val properties = Regex("\\bval\\s+(\\w+)\\s*:")
                    .findAll(constructor)
                    .map { it.groupValues[1] }
                    .toList()
                blocks += EntityBlock(table, className, properties)
                search = constructorClose
            }
        }
        return blocks
    }

    private fun matchingParen(text: String, openIndex: Int): Int {
        require(openIndex in text.indices && text[openIndex] == '(') { "expected '(' at $openIndex" }
        var depth = 0
        for (index in openIndex until text.length) {
            when (text[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("unbalanced parentheses")
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("unable to locate the MCA project root")
    }

    @Test
    fun `entity source declares the expected number of tables`() {
        val blocks = entityBlocks()
        assertFalse(blocks.isEmpty())
        assertEquals(blocks.size, blocks.map { it.table }.toSet().size)
    }
}

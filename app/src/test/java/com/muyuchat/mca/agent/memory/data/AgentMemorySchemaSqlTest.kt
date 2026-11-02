package com.muyuchat.mca.agent.memory.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM contract test for the 28 → 29 memory schema.
 *
 * Three independent sources must agree:
 * 1. the `@Entity` declarations in `AgentMemoryEntities.kt`,
 * 2. the migration SQL in [AgentMemorySchema],
 * 3. the exported `29.json` that Room validates the migrated database against.
 *
 * The test builds the real v28 schema from `28.json`, applies the v29 statements (exactly what
 * [AgentMemorySchema.MIGRATION_28_29] does), and proves both that legacy rows survive and that the
 * new tables/indices/foreign keys/unique constraints match. Room's own identity-hash validation
 * runs in the instrumentation `AgentMemoryRoomMigrationTest`.
 */
class AgentMemorySchemaSqlTest {

    @Test
    fun `migration creates every memory table with the declared columns in order`() {
        newDatabase().use { connection ->
            applyMemorySchema(connection)
            val blocks = entityBlocks()
            assertEquals(
                "entity sources and the migration table list must agree",
                AgentMemorySchema.TABLE_NAMES.toSet(),
                blocks.map { it.table }.toSet()
            )
            blocks.forEach { block ->
                assertEquals(
                    "${block.table}: entity properties and PRAGMA columns must match in order",
                    block.properties,
                    columnNames(connection, block.table)
                )
            }
            AgentMemorySchema.TABLE_NAMES.forEach { table ->
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
    fun `exported 29 json agrees with the migration sql`() {
        val entities = exportedEntities()
        val migrated = HashMap<String, List<String>>()
        AgentMemorySchema.CREATE_STATEMENTS.forEach { statement ->
            migrated[tableOf(statement)] = columnsOf(statement)
        }
        AgentMemorySchema.TABLE_NAMES.forEach { table ->
            val exported = requireNotNull(entities[table]) { "29.json does not export $table" }
            assertEquals(
                "$table: exported create statement and migration SQL must declare the same columns",
                migrated.getValue(table),
                columnsOf(exported.getString("createSql").replace("\${TABLE_NAME}", table))
            )
            val exportedFields = exported.getJSONArray("fields").let { fields ->
                (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
            }
            assertEquals("$table: 29.json field order must match its create statement", exportedFields, migrated.getValue(table))
        }
        val exportedIndexNames = AgentMemorySchema.TABLE_NAMES.flatMap { table ->
            val entity = entities.getValue(table)
            val indices = entity.optJSONArray("indices") ?: return@flatMap emptyList<String>()
            (0 until indices.length()).map { indices.getJSONObject(it).getString("name") }
        }.toSet()
        val migrationIndexNames = AgentMemorySchema.INDEX_STATEMENTS.map { statement ->
            requireNotNull(Regex("INDEX IF NOT EXISTS `([^`]+)`").find(statement)).groupValues[1]
        }.toSet()
        assertEquals(exportedIndexNames, migrationIndexNames)
    }

    @Test
    fun `every declared index exists with the right uniqueness`() {
        newDatabase().use { connection ->
            applyMemorySchema(connection)
            AgentMemorySchema.INDEX_STATEMENTS.forEach { statement ->
                val match = requireNotNull(
                    Regex("CREATE (UNIQUE )?INDEX IF NOT EXISTS `([^`]+)` ON `([^`]+)`")
                        .find(statement)
                ) { "unparsable index: $statement" }
                val info = indexInfo(connection, match.groupValues[3], match.groupValues[2])
                assertNotNull("index ${match.groupValues[2]} is missing", info)
                assertEquals(
                    "index ${match.groupValues[2]} uniqueness",
                    match.groupValues[1].isNotBlank(),
                    info!!.unique
                )
            }
        }
    }

    @Test
    fun `memory foreign keys point at the expected parents`() {
        newDatabase().use { connection ->
            applyMemorySchema(connection)
            assertEquals(
                setOf(
                    ForeignKeyExpectation("agent_memory_items", "spaceId", "agent_memory_spaces", "spaceId"),
                    ForeignKeyExpectation("agent_memory_chunks", "memoryId", "agent_memory_items", "memoryId"),
                    ForeignKeyExpectation("agent_memory_chunks", "spaceId", "agent_memory_spaces", "spaceId")
                ),
                AgentMemorySchema.TABLE_NAMES.flatMap { table -> foreignKeys(connection, table) }.toSet()
            )
        }
    }

    @Test
    fun `source hash dedupe is enforced by a unique index`() {
        newDatabase().use { connection ->
            applyMemorySchema(connection)
            exec(
                connection,
                "INSERT INTO agent_memory_spaces (spaceId, displayName, createdAt, updatedAt, revision) " +
                    "VALUES ('space-1', 'Work', 1, 1, 1)"
            )
            insertItem(connection, "memory-1", "space-1", "a".repeat(64))
            try {
                insertItem(connection, "memory-2", "space-1", "a".repeat(64))
                fail("expected the (spaceId, contentHash) unique index to reject a duplicate")
            } catch (_: java.sql.SQLException) {
                // expected
            }
            insertItem(connection, "memory-3", "space-1", "b".repeat(64))
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM agent_memory_items"))
        }
    }

    @Test
    fun `28 to 29 preserves chat and agent rows and reports the version boundary`() {
        newDatabase().use { connection ->
            createV28Schema(connection)
            exec(connection, "INSERT INTO chat_sessions (id, title, pinned, manualTitle, updatedAt) " +
                "VALUES ('session-1', 'Legacy chat', 0, 0, 123)")
            exec(
                connection,
                "INSERT INTO chat_messages (sessionId, position, role, content, createdAt) " +
                    "VALUES ('session-1', 0, 'user', 'hello 世界', 124)"
            )
            exec(
                connection,
                "INSERT INTO agent_workspaces (workspaceId, displayName, storageRef, createdAt, updatedAt) " +
                    "VALUES ('workspace-1', 'Legacy workspace', 'workspace-1', 1, 1)"
            )

            applyMemorySchema(connection)

            assertEquals("Legacy chat", singleString(connection, "SELECT title FROM chat_sessions WHERE id = 'session-1'"))
            assertEquals(
                "hello 世界",
                singleString(connection, "SELECT content FROM chat_messages WHERE sessionId = 'session-1' AND position = 0")
            )
            assertEquals(
                "Legacy workspace",
                singleString(connection, "SELECT displayName FROM agent_workspaces WHERE workspaceId = 'workspace-1'")
            )
            AgentMemorySchema.TABLE_NAMES.forEach { table ->
                assertTrue("missing memory table $table", hasTable(connection, table))
            }
            assertEquals("ok", singleString(connection, "PRAGMA integrity_check"))
            assertEquals(28, AgentMemorySchema.MIGRATION_28_29.startVersion)
            assertEquals(29, AgentMemorySchema.MIGRATION_28_29.endVersion)
            assertEquals(29, AgentMemorySchema.SOURCE_VERSION + 1)
        }
    }

    @Test
    fun `entity sources declare each table once`() {
        val blocks = entityBlocks()
        assertEquals(blocks.size, blocks.map { it.table }.toSet().size)
        assertEquals(4, blocks.size)
    }

    private data class EntityBlock(val table: String, val properties: List<String>)

    private fun entityBlocks(): List<EntityBlock> {
        val text = File(
            projectRoot(),
            "app/src/main/java/com/muyuchat/mca/agent/memory/data/AgentMemoryEntities.kt"
        ).readText()
        val blocks = mutableListOf<EntityBlock>()
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
            require(classStart > close) { "data class after @Entity not found" }
            val constructorOpen = text.indexOf('(', classStart)
            val constructorClose = matchingParen(text, constructorOpen)
            val constructor = text.substring(constructorOpen + 1, constructorClose)
            val properties = Regex("\\bval\\s+(\\w+)\\s*:").findAll(constructor)
                .map { it.groupValues[1] }
                .toList()
            blocks += EntityBlock(table, properties)
            search = constructorClose
        }
        return blocks
    }

    private fun exportedEntities(): Map<String, JSONObject> {
        val directory = File(projectRoot(), "app/schemas/com.muyuchat.mca.McaRoomDatabase")
        val file = File(directory, "29.json")
        assertTrue("29.json must be exported", file.isFile)
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            entity.getString("tableName") to entity
        }
    }

    private fun applyMemorySchema(connection: Connection) {
        AgentMemorySchema.CREATE_STATEMENTS.forEach { statement ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }
        AgentMemorySchema.INDEX_STATEMENTS.forEach { statement ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    /**
     * Builds the exact v28 schema Room exported (all 26 tables). This is what a real user install
     * upgrading from app version 28 has on disk.
     */
    private fun createV28Schema(connection: Connection) {
        val directory = File(projectRoot(), "app/schemas/com.muyuchat.mca.McaRoomDatabase")
        val entities = JSONObject(File(directory, "28.json").readText())
            .getJSONObject("database")
            .getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            connection.createStatement().use { statement ->
                statement.executeUpdate(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val indices = entity.optJSONArray("indices") ?: continue
            for (position in 0 until indices.length()) {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", table)
                    )
                }
            }
        }
    }

    private fun insertItem(connection: Connection, memoryId: String, spaceId: String, hash: String) {
        exec(
            connection,
            "INSERT INTO agent_memory_items (memoryId, spaceId, title, text, contentHash, sourceKind, " +
                "sourceRef, tokenCount, chunkCount, createdAt, updatedAt, revision, revoked, revokedAt) VALUES " +
                "('$memoryId', '$spaceId', 'title', 'text', '$hash', 'USER_TEXT', NULL, 1, 1, 1, 1, 0, 0, NULL)"
        )
    }

    private fun tableOf(statement: String): String =
        requireNotNull(Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(statement)).groupValues[1]

    private fun columnsOf(statement: String): List<String> {
        val body = statement.substringAfter("(", "").substringBefore("PRIMARY KEY")
        return Regex("`([A-Za-z0-9_]+)`").findAll(body).map { it.groupValues[1] }.toList()
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

    private data class IndexInfo(val name: String, val unique: Boolean)

    private fun indexInfo(connection: Connection, table: String, name: String): IndexInfo? {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(`$table`)").use { cursor ->
                while (cursor.next()) {
                    if (cursor.getString("name") == name) {
                        return IndexInfo(name, cursor.getInt("unique") == 1)
                    }
                }
            }
        }
        return null
    }

    private data class ForeignKeyExpectation(
        val table: String,
        val column: String,
        val parentTable: String,
        val parentColumn: String
    )

    private fun foreignKeys(connection: Connection, table: String): List<ForeignKeyExpectation> {
        val keys = mutableListOf<ForeignKeyExpectation>()
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

    private fun count(connection: Connection, sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { cursor ->
                if (cursor.next()) cursor.getInt(1) else 0
            }
        }

    private fun exec(connection: Connection, sql: String) {
        connection.createStatement().use { it.executeUpdate(sql) }
    }

    private fun singleString(connection: Connection, sql: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { cursor ->
                if (cursor.next()) cursor.getString(1) else null
            }
        }

    private fun hasTable(connection: Connection, table: String): Boolean =
        count(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'") > 0

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
}

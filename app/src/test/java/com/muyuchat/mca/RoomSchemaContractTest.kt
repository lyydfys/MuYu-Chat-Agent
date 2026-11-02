package com.muyuchat.mca

import java.io.File
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests over the exported Room schemas.
 *
 * Room validates an existing database against the identity hash of the schema it was
 * generated from. If a migration produces a table that differs in any way -- a missing
 * column, a different column order, a changed affinity -- the mismatch only appears on a
 * real upgrade, as "Room cannot verify the data integrity", and the app cannot open its
 * own database.
 *
 * The migration tests build their own minimal tables and prove that data survives. These
 * tests cover the other half: that the structure Room expects is itself coherent, that the
 * exported schema still matches the source declarations, and that a migration path exists
 * from every historical version to the current one.
 */
class RoomSchemaContractTest {

    @Test
    fun `both databases export the version declared in source`() {
        // The chat @Database declaration moved to its own file in the persistence-layer split.
        // The contract below is unchanged: it still reads the declaration, not the store.
        val chatSource = sourceFile("app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt")
        // The runtime profile @Database declaration moved to its own file in the data-layer split.
        // The contract below is unchanged: it still reads the declaration, not the store.
        val profileSource = sourceFile("app/src/main/java/com/muyuchat/mca/ModelRuntimeProfileDatabase.kt")

        assertEquals(
            "chat database version must match the exported schema name",
            declaredVersion(chatSource),
            schemaVersion("com.muyuchat.mca.McaRoomDatabase")
        )
        assertEquals(
            "runtime profile database version must match the exported schema name",
            declaredVersion(profileSource),
            schemaVersion("com.muyuchat.mca.ModelRuntimeProfileDatabase")
        )
        assertTrue(
            "schema export must stay enabled or the migration contract loses its reference",
            chatSource.contains("exportSchema = true") && profileSource.contains("exportSchema = true")
        )
    }

    @Test
    fun `every exported entity table accepts its own create statement`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                schemaEntities("com.muyuchat.mca.McaRoomDatabase").forEach { entity ->
                    val table = entity.getString("tableName")
                    statement.executeUpdate(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    @Test
    fun `create statement columns match the declared fields in order`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            schemaEntities("com.muyuchat.mca.McaRoomDatabase").forEach { entity ->
                val table = entity.getString("tableName")
                connection.createStatement().use { statement ->
                    statement.executeUpdate(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                }

                val declared = entity.getJSONArray("fields").let { fields ->
                    (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                }
                val created = mutableListOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                        while (rows.next()) created += rows.getString("name")
                    }
                }

                assertEquals(
                    "$table: the create statement must define exactly the declared columns, in order",
                    declared,
                    created
                )
            }
        }
    }

    @Test
    fun `every declared index can be created on its table`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            for (entity in schemaEntities("com.muyuchat.mca.McaRoomDatabase")) {
                val table = entity.getString("tableName")
                connection.createStatement().use { statement ->
                    statement.executeUpdate(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                }
                // Room omits "indices" entirely for a table that declares none.
                val indices = entity.optJSONArray("indices") ?: continue
                for (index in 0 until indices.length()) {
                    val createSql = indices.getJSONObject(index).getString("createSql")
                        .replace("\${TABLE_NAME}", table)
                    connection.createStatement().use { statement -> statement.executeUpdate(createSql) }
                }
            }
        }
    }

    @Test
    fun `a migration path exists from every historical version to the current one`() {
        // The edges come from the builder registration, which is what Room actually executes.
        // Schema files elsewhere declare the Migration objects; reading one file for their literal
        // ranges missed both the named-constant files and the video chain. Comments are stripped
        // first so a `)` inside a KDoc cannot truncate an addMigrations(...) argument list.
        val source = codeOnly(
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt")
        )
        val current = declaredVersion(source)

        val migrations = registeredMigrations(source)
            .map { name ->
                val (from, to) = name.removePrefix("MIGRATION_").split('_').map { it.toInt() }
                from to to
            }
        assertTrue("no migration registration was found", migrations.isNotEmpty())

        // Walk forward from each historical version and confirm it can reach `current`.
        val unreachable = (1 until current).filter { start ->
            val seen = mutableSetOf(start)
            var frontier = listOf(start)
            while (frontier.isNotEmpty()) {
                val next = frontier.flatMap { from ->
                    migrations.filter { it.first == from }.map { it.second }
                }.filter { seen.add(it) }
                frontier = next
            }
            current !in seen
        }

        assertEquals(
            "every schema version must be able to migrate to $current; unreachable: $unreachable",
            emptyList<Int>(),
            unreachable
        )
    }

    @Test
    fun `the migration chain retires every version below the current one`() {
        // This file is the single builder registration point even though the 27..32 migration SQL
        // lives in the Agent/memory/video data packages. Registration tokens are read from the real
        // call argument lists (see registeredMigrations), and the declared set must cover both the
        // inferred-type (`= object : Migration`) and explicitly-typed (`: Migration = ...`) forms.
        val source = codeOnly(
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt")
        )
        val current = declaredVersion(source)
        val registered = registeredMigrations(source)

        val declared =
            Regex("(?:private|internal) val (MIGRATION_\\d+_\\d+)\\s*(?::\\s*Migration)?\\s*=\\s*object\\s*:\\s*Migration")
                .findAll(source)
                .map { it.groupValues[1] }
                .toSet()

        assertEquals(
            "every declared migration must be registered on the builder",
            emptySet<String>(),
            declared - registered
        )
        assertTrue(
            "the final step into version $current must be registered",
            registered.any { it.endsWith("_$current") }
        )
    }

    @Test
    fun `the runtime profile database exports a usable schema`() {
        Class.forName("org.sqlite.JDBC")
        val entities = schemaEntities("com.muyuchat.mca.ModelRuntimeProfileDatabase")
        assertTrue("the runtime profile database must export its entities", entities.size > 1)

        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            entities.forEach { entity ->
                val table = entity.getString("tableName")
                connection.createStatement().use { statement ->
                    statement.executeUpdate(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    /**
     * Removes comments so structural checks only see executable code. Migration arguments carry
     * KDoc-adjacent comments with parentheses; without this, the first `)` ends the call early.
     */
    private fun codeOnly(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

    /** Every `MIGRATION_<from>_<to>` token inside a real `addMigrations(...)` argument list. */
    private fun registeredMigrations(source: String): Set<String> {
        val names = mutableSetOf<String>()
        var searchFrom = 0
        while (true) {
            val call = source.indexOf("addMigrations(", searchFrom)
            if (call < 0) break
            var index = call + "addMigrations".length
            var depth = 0
            var end = -1
            while (index < source.length) {
                when (source[index]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
                index += 1
            }
            if (end < 0) break
            Regex("MIGRATION_\\d+_\\d+")
                .findAll(source.substring(call, end))
                .forEach { names += it.value }
            searchFrom = end + 1
        }
        return names
    }

    private fun declaredVersion(source: String): Int =
        Regex("@Database\\([\\s\\S]*?version\\s*=\\s*(\\d+)").find(source)
            ?.groupValues?.get(1)?.toInt()
            ?: error("no @Database version found")

    private fun schemaVersion(databaseClassName: String): Int {
        val directory = File(projectRoot(), "app/schemas/$databaseClassName")
        val files = directory.listFiles { file -> file.extension == "json" }
        requireNotNull(files) { "no exported schema for $databaseClassName at ${directory.path}" }
        require(files.isNotEmpty()) { "$databaseClassName must export at least one schema version" }
        // Migration history legitimately keeps every exported version (27.json, 28.json, ...).
        // What must stay true is that the highest exported version is exactly the one declared in
        // the @Database source; a stale export or a version bump without re-export fails here.
        return files.maxOf { it.nameWithoutExtension.toInt() }
    }

    private fun schemaEntities(databaseClassName: String): List<JSONObject> {
        val directory = File(projectRoot(), "app/schemas/$databaseClassName")
        val files = requireNotNull(directory.listFiles { file -> file.extension == "json" }) {
            "no exported schema for $databaseClassName at ${directory.path}"
        }
        val latest = requireNotNull(files.maxByOrNull { it.nameWithoutExtension.toInt() }) {
            "no exported schema for $databaseClassName at ${directory.path}"
        }
        val schema = JSONObject(latest.readText())
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it) }
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private fun sourceFile(relativePath: String): String =
        File(projectRoot(), relativePath).readText()
}

package com.muyuchat.mca.schemacheck

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.sql.Connection
import org.json.JSONObject
import org.junit.Assert.assertEquals

/**
 * Loads the KSP-exported Room schema JSONs and compares a real SQLite database against them.
 *
 * The comparison is deliberately structural but **name-based**: Room's own `TableInfo` compares
 * columns as a map keyed by name, so a migration that appends columns with `ALTER TABLE` (different
 * physical ordinal but same name/affinity/not-null/default) is equivalent for Room. This helper
 * therefore asserts the column *set* and each column's attributes, never `PRAGMA table_info.cid`
 * order. Index column order (which is semantic) and foreign keys are compared exactly.
 */
internal object SchemaJsonSupport {

    const val DATABASE_DIRECTORY = "app/schemas/com.muyuchat.mca.McaRoomDatabase"

    fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    fun schemaDirectory(): File = File(projectRoot(), DATABASE_DIRECTORY)

    fun schemaFile(version: Int): File = File(schemaDirectory(), "$version.json")

    /** Returns the `database` object of `app/schemas/.../<version>.json`. */
    fun loadDatabase(version: Int): JSONObject {
        val file = schemaFile(version)
        require(file.isFile) { "missing exported schema $file" }
        return JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("database")
    }

    /** Creates the exact schema Room exported for [version] (table DDL + indices). */
    fun buildDatabase(connection: Connection, database: JSONObject) {
        val entities = database.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            connection.createStatement().use { statement ->
                statement.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val indices = entity.optJSONArray("indices") ?: continue
            for (position in 0 until indices.length()) {
                connection.createStatement().use { statement ->
                    statement.execute(
                        indices.getJSONObject(position).getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
        }
    }

    /**
     * Same DDL application through the [SupportSQLiteDatabase] seam the production `Migration`
     * objects receive. The migration suites hold a JDBC-backed adapter (not a raw `Connection`), so
     * this overload keeps one schema builder instead of forking a second DDL loop in each test.
     */
    fun buildDatabase(database: SupportSQLiteDatabase, databaseJson: JSONObject) {
        val entities = databaseJson.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (position in 0 until indices.length()) {
                database.execSQL(
                    indices.getJSONObject(position).getString("createSql")
                        .replace("\${TABLE_NAME}", table)
                )
            }
        }
    }

    fun entityTableNames(database: JSONObject): List<String> {
        val entities = database.getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.sorted()
    }

    fun actualTableNames(database: SupportSQLiteDatabase): List<String> =
        database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (!name.startsWith("sqlite_") && name != "room_master_table") add(name)
                }
            }.sorted()
        }

    fun assertIntegrity(database: SupportSQLiteDatabase, label: String) {
        database.query("PRAGMA integrity_check").use { cursor ->
            val ok = cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            assertEquals("$label: PRAGMA integrity_check", true, ok)
        }
    }

    fun tableInfo(database: SupportSQLiteDatabase, table: String): Map<String, ColumnShape> {
        val nameIndex = 1
        val typeIndex = 2
        val notNullIndex = 3
        val defaultIndex = 4
        val primaryKeyIndex = 5
        return database.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(nameIndex),
                        ColumnShape(
                            affinity = cursor.getString(typeIndex).orEmpty().uppercase(),
                            notNull = cursor.getInt(notNullIndex) == 1,
                            defaultValue = cursor.getString(defaultIndex),
                            primaryKeyPosition = cursor.getInt(primaryKeyIndex)
                        )
                    )
                }
            }
        }
    }

    fun indexShapes(database: SupportSQLiteDatabase, table: String): Map<String, IndexShape> =
        database.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = 1
            val uniqueIndex = 2
            val result = linkedMapOf<String, IndexShape>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                if (name.startsWith("sqlite_autoindex_")) continue
                val unique = cursor.getInt(uniqueIndex) == 1
                result[name] = IndexShape(unique = unique, columns = indexColumns(database, name))
            }
            result
        }

    private fun indexColumns(database: SupportSQLiteDatabase, index: String): List<String> {
        val seqNoIndex = 0
        val nameIndex = 2
        val rows = mutableListOf<Pair<Int, String>>()
        database.query("PRAGMA index_info(`$index`)").use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getInt(seqNoIndex) to cursor.getString(nameIndex)
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    fun foreignKeyShapes(database: SupportSQLiteDatabase, table: String): Set<ForeignKeyShape> =
        database.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val parentTableIndex = 2
            val fromIndex = 3
            val toIndex = 4
            val onUpdateIndex = 5
            val onDeleteIndex = 6
            buildSet {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeyShape(
                            table = cursor.getString(parentTableIndex),
                            from = cursor.getString(fromIndex),
                            to = cursor.getString(toIndex),
                            onUpdate = cursor.getString(onUpdateIndex),
                            onDelete = cursor.getString(onDeleteIndex)
                        )
                    )
                }
            }
        }

    fun jsonColumnShapes(entity: JSONObject): Map<String, ColumnShape> {
        val primaryKey = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
        val primaryKeyPositions = (0 until primaryKey.length()).associate { primaryKey.getString(it) to it + 1 }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).associate { index ->
            val field = fields.getJSONObject(index)
            val column = field.getString("columnName")
            column to ColumnShape(
                affinity = field.getString("affinity").uppercase(),
                notNull = field.optBoolean("notNull", false),
                defaultValue = if (field.has("defaultValue") && !field.isNull("defaultValue")) {
                    field.getString("defaultValue")
                } else {
                    null
                },
                primaryKeyPosition = primaryKeyPositions[column] ?: 0
            )
        }
    }

    fun jsonIndexShapes(entity: JSONObject): Map<String, IndexShape> {
        val indices = entity.optJSONArray("indices") ?: return emptyMap()
        return (0 until indices.length()).associate { index ->
            val entry = indices.getJSONObject(index)
            val columns = entry.getJSONArray("columnNames")
            entry.getString("name") to IndexShape(
                unique = entry.optBoolean("unique", false),
                columns = (0 until columns.length()).map { columns.getString(it) }
            )
        }
    }

    fun jsonForeignKeyShapes(entity: JSONObject): Set<ForeignKeyShape> {
        val foreignKeys = entity.optJSONArray("foreignKeys") ?: return emptySet()
        return buildSet {
            for (index in 0 until foreignKeys.length()) {
                val foreignKey = foreignKeys.getJSONObject(index)
                val columns = foreignKey.getJSONArray("columns")
                val referenced = foreignKey.getJSONArray("referencedColumns")
                for (position in 0 until columns.length()) {
                    add(
                        ForeignKeyShape(
                            table = foreignKey.getString("table"),
                            from = columns.getString(position),
                            to = referenced.getString(position),
                            onUpdate = foreignKey.getString("onUpdate"),
                            onDelete = foreignKey.getString("onDelete")
                        )
                    )
                }
            }
        }
    }

    /**
     * Asserts that [database] has exactly the schema Room expects for [schema].
     * Column order is intentionally not compared (Room compares by name); index column order is.
     */
    fun assertDatabaseMatches(database: SupportSQLiteDatabase, schema: JSONObject, label: String) {
        val expectedTables = entityTableNames(schema)
        assertEquals("$label: table set", expectedTables, actualTableNames(database))

        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            val expectedColumns = jsonColumnShapes(entity)
            val actualColumns = tableInfo(database, table)
            assertEquals(
                "$label/$table: column names (by name; physical order not asserted)",
                expectedColumns.keys.sorted(),
                actualColumns.keys.sorted()
            )
            for (column in expectedColumns.keys) {
                assertEquals(
                    "$label/$table.$column: column shape",
                    expectedColumns.getValue(column),
                    actualColumns.getValue(column)
                )
            }
            assertEquals(
                "$label/$table: index shapes",
                jsonIndexShapes(entity),
                indexShapes(database, table)
            )
            assertEquals(
                "$label/$table: foreign keys",
                jsonForeignKeyShapes(entity),
                foreignKeyShapes(database, table)
            )
        }
    }

    fun rowCount(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            if (!cursor.moveToFirst()) error("no count row for $table")
            cursor.getInt(0)
        }

    fun singleString(database: SupportSQLiteDatabase, sql: String): String? =
        database.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun singleInt(database: SupportSQLiteDatabase, sql: String): Int =
        database.query(sql).use { cursor ->
            if (!cursor.moveToFirst()) error("no row for $sql")
            cursor.getInt(0)
        }
}

internal data class ColumnShape(
    val affinity: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKeyPosition: Int
)

internal data class IndexShape(
    val unique: Boolean,
    val columns: List<String>
)

internal data class ForeignKeyShape(
    val table: String,
    val from: String,
    val to: String,
    val onUpdate: String,
    val onDelete: String
)

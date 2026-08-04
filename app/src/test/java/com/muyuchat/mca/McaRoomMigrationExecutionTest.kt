package com.muyuchat.mca

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McaRoomMigrationExecutionTest {
    @Test
    fun migration16To17ExecutesTheKnowledgeSchemaWithoutDestructiveSql() {
        val recorder = RecordingDatabase()
        val migration = migration("MIGRATION_16_17")

        migration.migrate(recorder.database)

        assertEquals(16, migration.startVersion)
        assertEquals(17, migration.endVersion)
        listOf(
            "knowledge_bases",
            "knowledge_documents",
            "knowledge_chunks",
            "chat_knowledge_base_bindings"
        ).forEach { table ->
            assertTrue(recorder.statements.any { it.contains("CREATE TABLE IF NOT EXISTS $table") })
        }
        assertEquals(6, recorder.statements.count { it.startsWith("CREATE INDEX IF NOT EXISTS") })
        assertFalse(recorder.statements.any { it.contains("DROP TABLE", ignoreCase = true) })
        assertFalse(recorder.statements.any { it.contains("DELETE FROM", ignoreCase = true) })
    }

    @Test
    fun migration17To18AddsCharacterCardJsonExactlyOnce() {
        val migration = migration("MIGRATION_17_18")
        val missingColumn = RecordingDatabase(
            queryColumns = setOf("id", "name", "systemPrompt")
        )

        migration.migrate(missingColumn.database)

        assertEquals(17, migration.startVersion)
        assertEquals(18, migration.endVersion)
        assertEquals(
            listOf("ALTER TABLE assistants ADD COLUMN characterCardJson TEXT"),
            missingColumn.statements
        )

        val existingColumn = RecordingDatabase(
            queryColumns = setOf("id", "name", "systemPrompt", "characterCardJson")
        )
        migration.migrate(existingColumn.database)
        assertTrue(existingColumn.statements.isEmpty())
    }

    private fun migration(fieldName: String): Migration {
        val field = McaRoomDatabase::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(null) as Migration
    }

    private class RecordingDatabase(
        private val queryColumns: Set<String> = emptySet()
    ) {
        val statements = mutableListOf<String>()
        val database: SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "execSQL" -> {
                    statements += arguments.orEmpty().first() as String
                    null
                }
                "query" -> cursor(queryColumns.toList())
                "isOpen" -> true
                "inTransaction" -> false
                "toString" -> "RecordingSupportSQLiteDatabase"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> arguments?.firstOrNull() === this
                else -> defaultValue(method.returnType)
            }
        } as SupportSQLiteDatabase
    }

    private companion object {
        fun cursor(columns: List<String>): Cursor {
            var position = -1
            var closed = false
            return Proxy.newProxyInstance(
                Cursor::class.java.classLoader,
                arrayOf(Cursor::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getColumnIndex", "getColumnIndexOrThrow" -> 0
                    "moveToNext" -> {
                        val next = position + 1
                        if (next < columns.size) {
                            position = next
                            true
                        } else {
                            false
                        }
                    }
                    "getString" -> columns[position]
                    "getCount" -> columns.size
                    "close" -> {
                        closed = true
                        null
                    }
                    "isClosed" -> closed
                    "toString" -> "RecordingCursor"
                    "hashCode" -> columns.hashCode()
                    else -> defaultValue(method.returnType)
                }
            } as Cursor
        }

        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}

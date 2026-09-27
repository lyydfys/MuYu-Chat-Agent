package com.muyuchat.mca

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryMigrationTest {
    @Test
    fun migrationsPreserveRawMessagesAndGiveLegacyRowsStableIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE chat_sessions (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("""
                            CREATE TABLE chat_messages (
                                sessionId TEXT NOT NULL, position INTEGER NOT NULL,
                                content TEXT NOT NULL, createdAt INTEGER NOT NULL,
                                PRIMARY KEY (sessionId, position)
                            )
                        """.trimIndent())
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("The fixture invokes the production migrations explicitly")
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            val original = "```kotlin\n  val x = 1\n```\n\n"
            db.execSQL("INSERT INTO chat_sessions (id) VALUES (?)", arrayOf("session"))
            db.execSQL("INSERT INTO chat_messages (sessionId, position, content, createdAt) VALUES (?, ?, ?, ?)",
                arrayOf<Any>("session", 0, original, 123L))
            McaRoomDatabase.MIGRATION_23_24.migrate(db)
            McaRoomDatabase.MIGRATION_24_25.migrate(db)

            db.query("SELECT content, messageId, pinned, contextAssemblyTraceJson FROM chat_messages").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(original, row.getString(0))
                assertEquals("session:0:123", row.getString(1))
                assertEquals(0, row.getInt(2))
                assertTrue(row.isNull(3))
                assertFalse(row.moveToNext())
            }
            db.query("SELECT contextSummariesJson FROM chat_sessions").use { row ->
                assertTrue(row.moveToFirst())
                assertTrue(row.isNull(0))
            }
            val trace = "{\"schemaVersion\":1,\"assembledHash\":\"fixture\"}"
            db.execSQL("UPDATE chat_messages SET pinned = 1, contextAssemblyTraceJson = ? WHERE messageId = ?",
                arrayOf(trace, "session:0:123"))
            db.query("SELECT content, pinned, contextAssemblyTraceJson FROM chat_messages").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(original, row.getString(0))
                assertEquals(1, row.getInt(1))
                assertEquals(trace, row.getString(2))
            }
        } finally {
            helper.close()
        }
    }
}

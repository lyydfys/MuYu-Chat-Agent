package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * T2B21-T4（b 工位）拆片：legacy（android.database.sqlite）规整 helper 族
 * （normalizeLegacy* / legacy* 工具）自 ChatSessionStoreRoomDatabase.kt 逐字节平移；
 * 仅声明行 `private fun`→`internal fun` 去缩进登记。
 */

internal fun normalizeLegacyFileAssetsTable(db: SQLiteDatabase) {
            if (!legacyTableExists(db, "file_assets")) return
            val columns = legacyTableColumns(db, "file_assets")
            val expectedColumns = setOf(
                "id",
                "name",
                "mimeType",
                "text",
                "preview",
                "truncated",
                "source",
                "createdAt",
                "sizeBytes",
                "chatSessionId",
                "projectId"
            )
            if (columns.containsAll(expectedColumns) && "contentPreview" !in columns && "uriString" !in columns) return
            val textExpression = firstColumnOrDefault(columns, listOf("text", "contentPreview", "preview"), "''")
            val previewExpression = firstColumnOrDefault(columns, listOf("preview", "contentPreview", "text"), "''")
            db.execSQL("DROP TABLE IF EXISTS file_assets_new")
            db.execSQL(
                """
                CREATE TABLE file_assets_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    text TEXT NOT NULL,
                    preview TEXT NOT NULL,
                    truncated INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    chatSessionId TEXT,
                    projectId TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO file_assets_new (
                    id, name, mimeType, text, preview, truncated, source, createdAt, sizeBytes, chatSessionId, projectId
                )
                SELECT
                    ${legacyColumnOrDefault(columns, "id", "hex(randomblob(16))")},
                    ${legacyColumnOrDefault(columns, "name", "'文件'")},
                    ${legacyColumnOrDefault(columns, "mimeType", "'text/plain'")},
                    $textExpression,
                    $previewExpression,
                    ${legacyColumnOrDefault(columns, "truncated", "0")},
                    ${legacyColumnOrDefault(columns, "source", "'uploaded'")},
                    ${legacyColumnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                    ${legacyColumnOrDefault(columns, "sizeBytes", "0")},
                    ${legacyNullableColumn(columns, "chatSessionId")},
                    ${legacyNullableColumn(columns, "projectId")}
                FROM file_assets
                """.trimIndent()
            )
            db.execSQL("DROP TABLE file_assets")
            db.execSQL("ALTER TABLE file_assets_new RENAME TO file_assets")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_createdAt ON file_assets(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_chatSessionId ON file_assets(chatSessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_projectId ON file_assets(projectId)")
        }

internal fun normalizeLegacyChatMessagesTable(db: SQLiteDatabase) {
            if (!legacyTableExists(db, "chat_messages")) return
            val columns = legacyTableColumns(db, "chat_messages")
            db.execSQL("DROP TABLE IF EXISTS chat_messages_new")
            db.execSQL(
                """
                CREATE TABLE chat_messages_new (
                    sessionId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    tokenCount INTEGER,
                    reasoningContent TEXT NOT NULL DEFAULT '',
                    reasoningDurationMs INTEGER NOT NULL DEFAULT 0,
                    imageAttachmentsJson TEXT NOT NULL DEFAULT '[]',
                    sourceReferencesJson TEXT NOT NULL DEFAULT '[]',
                    webSearchTraceJson TEXT NOT NULL DEFAULT '{}',
                    generationMetricsJson TEXT,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    contextAssemblyTraceJson TEXT,
                    PRIMARY KEY(sessionId, position)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO chat_messages_new (
                    sessionId, position, role, content, createdAt, tokenCount,
                    reasoningContent, reasoningDurationMs, imageAttachmentsJson, sourceReferencesJson, webSearchTraceJson,
                    generationMetricsJson, pinned, contextAssemblyTraceJson
                )
                SELECT
                    ${legacyColumnOrDefault(columns, "sessionId", "''")},
                    ${legacyColumnOrDefault(columns, "position", "0")},
                    ${legacyColumnOrDefault(columns, "role", "'USER'")},
                    ${legacyColumnOrDefault(columns, "content", "''")},
                    ${legacyColumnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                    ${legacyNullableColumn(columns, "tokenCount")},
                    ${legacyColumnOrDefault(columns, "reasoningContent", "''")},
                    ${legacyColumnOrDefault(columns, "reasoningDurationMs", "0")},
                    ${legacyColumnOrDefault(columns, "imageAttachmentsJson", "'[]'")},
                    ${legacyColumnOrDefault(columns, "sourceReferencesJson", "'[]'")},
                    ${legacyColumnOrDefault(columns, "webSearchTraceJson", "'{}'")},
                    ${legacyNullableColumn(columns, "generationMetricsJson")},
                    ${legacyColumnOrDefault(columns, "pinned", "0")},
                    ${legacyNullableColumn(columns, "contextAssemblyTraceJson")}
                FROM chat_messages
                """.trimIndent()
            )
            db.execSQL("DROP TABLE chat_messages")
            db.execSQL("ALTER TABLE chat_messages_new RENAME TO chat_messages")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")
        }

internal fun legacyTableExists(db: SQLiteDatabase, tableName: String): Boolean =
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
                cursor.moveToFirst()
            }

internal fun legacyTableColumns(db: SQLiteDatabase, tableName: String): Set<String> =
            db.rawQuery("PRAGMA table_info(`$tableName`)", emptyArray()).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameIndex))
                    }
                }
            }

internal fun legacyColumnOrDefault(columns: Set<String>, name: String, defaultSql: String): String =
            if (name in columns) "`$name`" else defaultSql

internal fun legacyNullableColumn(columns: Set<String>, name: String): String =
            if (name in columns) "`$name`" else "NULL"

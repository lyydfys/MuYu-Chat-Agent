package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * T2B21-T4（b 工位）拆片：file_assets / image_assets 非破坏性规整 helper（tableExists /
 * tableColumns / columnOrDefault 等工具）自 ChatSessionStoreRoomDatabase.kt 逐字节平移；
 * 仅声明行 `private fun`→`internal fun` 去缩进登记。
 */

internal fun normalizeFileAssetsTable(db: SupportSQLiteDatabase) {
            val existed = tableExists(db, "file_assets")
            val columns = if (existed) tableColumns(db, "file_assets") else emptySet()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS file_assets_new (
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
            if (existed) {
                val textExpression = firstColumnOrDefault(columns, listOf("text", "contentPreview", "preview"), "''")
                val previewExpression = firstColumnOrDefault(columns, listOf("preview", "contentPreview", "text"), "''")
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO file_assets_new (
                        id, name, mimeType, text, preview, truncated, source, createdAt, sizeBytes, chatSessionId, projectId
                    )
                    SELECT
                        ${columnOrDefault(columns, "id", "hex(randomblob(16))")},
                        ${columnOrDefault(columns, "name", "'文件'")},
                        ${columnOrDefault(columns, "mimeType", "'text/plain'")},
                        $textExpression,
                        $previewExpression,
                        ${columnOrDefault(columns, "truncated", "0")},
                        ${columnOrDefault(columns, "source", "'uploaded'")},
                        ${columnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                        ${columnOrDefault(columns, "sizeBytes", "0")},
                        ${nullableColumn(columns, "chatSessionId")},
                        ${nullableColumn(columns, "projectId")}
                    FROM file_assets
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE file_assets")
            }
            db.execSQL("ALTER TABLE file_assets_new RENAME TO file_assets")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_createdAt ON file_assets(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_chatSessionId ON file_assets(chatSessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_projectId ON file_assets(projectId)")
        }

internal fun normalizeImageAssetsTable(db: SupportSQLiteDatabase) {
            val existed = tableExists(db, "image_assets")
            val columns = if (existed) tableColumns(db, "image_assets") else emptySet()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS image_assets_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    uriString TEXT NOT NULL,
                    source TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    generationMetadataJson TEXT NOT NULL DEFAULT '',
                    chatSessionId TEXT,
                    projectId TEXT
                )
                """.trimIndent()
            )
            if (existed) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO image_assets_new (
                        id, name, uriString, source, prompt, createdAt, sizeBytes, width, height, generationMetadataJson, chatSessionId, projectId
                    )
                    SELECT
                        ${columnOrDefault(columns, "id", "hex(randomblob(16))")},
                        ${columnOrDefault(columns, "name", "'图片'")},
                        ${columnOrDefault(columns, "uriString", "''")},
                        ${columnOrDefault(columns, "source", "'uploaded'")},
                        ${columnOrDefault(columns, "prompt", "''")},
                        ${columnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                        ${columnOrDefault(columns, "sizeBytes", "0")},
                        ${columnOrDefault(columns, "width", "0")},
                        ${columnOrDefault(columns, "height", "0")},
                        ${columnOrDefault(columns, "generationMetadataJson", "''")},
                        ${nullableColumn(columns, "chatSessionId")},
                        ${nullableColumn(columns, "projectId")}
                    FROM image_assets
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE image_assets")
            }
            db.execSQL("ALTER TABLE image_assets_new RENAME TO image_assets")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_image_assets_createdAt ON image_assets(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_image_assets_chatSessionId ON image_assets(chatSessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_image_assets_projectId ON image_assets(projectId)")
        }

internal fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean =
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
                cursor.moveToFirst()
            }

internal fun tableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> =
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameIndex))
                    }
                }
            }

internal fun columnOrDefault(columns: Set<String>, name: String, defaultSql: String): String =
            if (name in columns) "COALESCE(`$name`, $defaultSql)" else defaultSql

internal fun nullableColumn(columns: Set<String>, name: String): String =
            if (name in columns) "`$name`" else "NULL"

internal fun firstColumnOrDefault(columns: Set<String>, names: List<String>, defaultSql: String): String =
            names.firstOrNull { it in columns }?.let { "`$it`" } ?: defaultSql

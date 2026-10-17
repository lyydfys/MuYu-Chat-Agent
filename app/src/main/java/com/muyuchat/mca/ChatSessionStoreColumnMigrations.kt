package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * T2B21-T4（b 工位）拆片：ChatSession 列迁移 helper 族（add*ColumnIfMissing /
 * create*TableIfMissing）自 ChatSessionStoreRoomDatabase.kt 逐字节平移；仅声明行
 * `private fun`→`internal fun` 去缩进登记。27 个 Migration 对象与 addMigrations 清单一律留在
 * 原壳文件；本文件不注册 migration、不定义 @Database。
 */

internal fun addMessageAttachmentsColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imageAttachmentsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

internal fun addMessageSourceReferencesColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN sourceReferencesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

internal fun addMessageWebSearchTraceColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN webSearchTraceJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

internal fun addImageGenerationMetadataColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("generationMetadataJson" !in tableColumns(db, "image_assets")) {
                db.execSQL(
                    "ALTER TABLE image_assets ADD COLUMN generationMetadataJson TEXT NOT NULL DEFAULT ''"
                )
            }
        }

internal fun addImageFavoriteColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("favorite" !in tableColumns(db, "image_assets")) {
                db.execSQL(
                    "ALTER TABLE image_assets ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0"
                )
            }
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_image_assets_favorite ON image_assets (favorite)"
            )
        }

internal fun addChatSessionBindingColumnsIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN assistantId TEXT")
            }
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN modelMode TEXT")
            }
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN modelId TEXT")
            }
        }

internal fun addChatSessionAssistantSnapshotColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("assistantSnapshotJson" !in tableColumns(db, "chat_sessions")) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN assistantSnapshotJson TEXT")
            }
        }

internal fun addChatAppearanceColumnsIfMissing(db: SupportSQLiteDatabase) {
            if ("appearanceJson" !in tableColumns(db, "chat_sessions")) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN appearanceJson TEXT")
            }
            if ("appearanceJson" !in tableColumns(db, "assistants")) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN appearanceJson TEXT")
            }
        }

internal fun createMemoriesTableIfMissing(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT NOT NULL PRIMARY KEY,
                    assistantId TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    content TEXT NOT NULL,
                    source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_assistantId ON memories(assistantId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_scope ON memories(scope)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_createdAt ON memories(createdAt)")
        }

internal fun createAssistantsTableIfMissing(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS assistants (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    avatar TEXT NOT NULL,
                    tag TEXT NOT NULL DEFAULT '',
                    systemPrompt TEXT NOT NULL,
                    defaultModelMode TEXT NOT NULL,
                    defaultModelId TEXT,
                    paramsJson TEXT NOT NULL,
                    memoryEnabled INTEGER NOT NULL,
                    webSearchEnabled INTEGER NOT NULL,
                    fileContextEnabled INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_assistants_updatedAt ON assistants(updatedAt)")
            addAssistantTagColumnIfMissing(db)
        }

internal fun addAssistantTagColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE assistants ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
            }
        }

internal fun addAssistantCharacterCardJsonColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("characterCardJson" !in tableColumns(db, "assistants")) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN characterCardJson TEXT")
            }
        }

package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * T2B21-T4（b 工位）拆片：知识库表迁移 helper 族（createKnowledgeBaseTablesIfMissing /
 * rebuild*WithForeignKeys / 索引）自 ChatSessionStoreRoomDatabase.kt 逐字节平移；仅声明行
 * `private fun`→`internal fun` 去缩进登记。迁移对象本身留在原壳文件。
 */

internal fun createKnowledgeBaseTablesIfMissing(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_bases (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    embeddingModelFingerprint TEXT,
                    indexState TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    source TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    contentLength INTEGER NOT NULL,
                    chunkCount INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    documentId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    estimatedTokens INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_knowledge_base_bindings (
                    chatSessionId TEXT NOT NULL,
                    knowledgeBaseId TEXT NOT NULL,
                    PRIMARY KEY(chatSessionId, knowledgeBaseId)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_bases_updatedAt ON knowledge_bases(updatedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_knowledgeBaseId ON knowledge_documents(knowledgeBaseId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_contentHash ON knowledge_documents(contentHash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_knowledgeBaseId ON knowledge_chunks(knowledgeBaseId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId ON knowledge_chunks(documentId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_knowledge_base_bindings_knowledgeBaseId ON chat_knowledge_base_bindings(knowledgeBaseId)")
        }

        /**
         * SQLite cannot add a foreign key to an existing table. Rebuild only
         * the dependent knowledge tables and copy rows whose parents still
         * exist, preserving all valid v19 data before replacing the old tables.
         */
internal fun rebuildKnowledgeTablesWithForeignKeys(db: SupportSQLiteDatabase) {
            rebuildKnowledgeDocumentsWithForeignKeys(db)
            rebuildKnowledgeChunksWithForeignKeys(db)
            rebuildChatKnowledgeBaseBindingsWithForeignKeys(db)
        }

internal fun rebuildKnowledgeDocumentsWithForeignKeys(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "knowledge_documents")) {
                createKnowledgeDocumentsTableWithForeignKeys(db, "knowledge_documents")
                createKnowledgeDocumentIndices(db)
                return
            }

            createKnowledgeDocumentsTableWithForeignKeys(db, "knowledge_documents_new")
            db.execSQL(
                """
                INSERT INTO knowledge_documents_new (
                    id, knowledgeBaseId, title, source, contentHash, contentLength, chunkCount, createdAt, updatedAt
                )
                SELECT
                    documents.id,
                    documents.knowledgeBaseId,
                    documents.title,
                    documents.source,
                    documents.contentHash,
                    documents.contentLength,
                    documents.chunkCount,
                    documents.createdAt,
                    documents.updatedAt
                FROM knowledge_documents AS documents
                INNER JOIN knowledge_bases AS bases ON bases.id = documents.knowledgeBaseId
                """.trimIndent()
            )
            db.execSQL("DROP TABLE knowledge_documents")
            db.execSQL("ALTER TABLE knowledge_documents_new RENAME TO knowledge_documents")
            createKnowledgeDocumentIndices(db)
        }

internal fun rebuildKnowledgeChunksWithForeignKeys(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "knowledge_chunks")) {
                createKnowledgeChunksTableWithForeignKeys(db, "knowledge_chunks")
                createKnowledgeChunkIndices(db)
                return
            }

            createKnowledgeChunksTableWithForeignKeys(db, "knowledge_chunks_new")
            db.execSQL(
                """
                INSERT INTO knowledge_chunks_new (
                    id, knowledgeBaseId, documentId, position, content, contentHash, estimatedTokens
                )
                SELECT
                    chunks.id,
                    chunks.knowledgeBaseId,
                    chunks.documentId,
                    chunks.position,
                    chunks.content,
                    chunks.contentHash,
                    chunks.estimatedTokens
                FROM knowledge_chunks AS chunks
                INNER JOIN knowledge_bases AS bases ON bases.id = chunks.knowledgeBaseId
                INNER JOIN knowledge_documents AS documents
                    ON documents.id = chunks.documentId
                    AND documents.knowledgeBaseId = chunks.knowledgeBaseId
                """.trimIndent()
            )
            db.execSQL("DROP TABLE knowledge_chunks")
            db.execSQL("ALTER TABLE knowledge_chunks_new RENAME TO knowledge_chunks")
            createKnowledgeChunkIndices(db)
        }

internal fun rebuildChatKnowledgeBaseBindingsWithForeignKeys(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "chat_knowledge_base_bindings")) {
                createChatKnowledgeBaseBindingsTableWithForeignKeys(db, "chat_knowledge_base_bindings")
                createChatKnowledgeBaseBindingIndices(db)
                return
            }

            createChatKnowledgeBaseBindingsTableWithForeignKeys(
                db,
                "chat_knowledge_base_bindings_new"
            )
            db.execSQL(
                """
                INSERT INTO chat_knowledge_base_bindings_new (chatSessionId, knowledgeBaseId)
                SELECT bindings.chatSessionId, bindings.knowledgeBaseId
                FROM chat_knowledge_base_bindings AS bindings
                INNER JOIN chat_sessions AS sessions ON sessions.id = bindings.chatSessionId
                INNER JOIN knowledge_bases AS bases ON bases.id = bindings.knowledgeBaseId
                """.trimIndent()
            )
            db.execSQL("DROP TABLE chat_knowledge_base_bindings")
            db.execSQL(
                "ALTER TABLE chat_knowledge_base_bindings_new RENAME TO chat_knowledge_base_bindings"
            )
            createChatKnowledgeBaseBindingIndices(db)
        }

internal fun createKnowledgeDocumentsTableWithForeignKeys(
            db: SupportSQLiteDatabase,
            tableName: String
        ) {
            db.execSQL(
                """
                CREATE TABLE $tableName (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    source TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    contentLength INTEGER NOT NULL,
                    chunkCount INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

internal fun createKnowledgeChunksTableWithForeignKeys(
            db: SupportSQLiteDatabase,
            tableName: String
        ) {
            db.execSQL(
                """
                CREATE TABLE $tableName (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    documentId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    estimatedTokens INTEGER NOT NULL,
                    FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
                    FOREIGN KEY(documentId) REFERENCES knowledge_documents(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

internal fun createChatKnowledgeBaseBindingsTableWithForeignKeys(
            db: SupportSQLiteDatabase,
            tableName: String
        ) {
            db.execSQL(
                """
                CREATE TABLE $tableName (
                    chatSessionId TEXT NOT NULL,
                    knowledgeBaseId TEXT NOT NULL,
                    PRIMARY KEY(chatSessionId, knowledgeBaseId),
                    FOREIGN KEY(chatSessionId) REFERENCES chat_sessions(id) ON DELETE CASCADE,
                    FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

internal fun createKnowledgeDocumentIndices(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_documents_knowledgeBaseId " +
                    "ON knowledge_documents(knowledgeBaseId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_documents_contentHash " +
                    "ON knowledge_documents(contentHash)"
            )
        }

internal fun createKnowledgeChunkIndices(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_knowledgeBaseId " +
                    "ON knowledge_chunks(knowledgeBaseId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId " +
                    "ON knowledge_chunks(documentId)"
            )
        }

internal fun createChatKnowledgeBaseBindingIndices(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chat_knowledge_base_bindings_knowledgeBaseId " +
                    "ON chat_knowledge_base_bindings(knowledgeBaseId)"
            )
        }

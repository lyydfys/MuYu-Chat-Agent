package com.muyuchat.mca.agent.extensions.skills

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF tree adapter for Skill import. Only `java.net`/Android provider types are used here; the
 * importer still owns every path/size/entry limit. The adapter never opens script files by itself —
 * [AgentSkillTreeDocument.openStream] is called only by the importer for regular files it installs.
 */
class AgentSafSkillTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri
) {
    fun root(): AgentSkillTreeDocument = SafDocument(
        resolver = resolver,
        treeUri = treeUri,
        documentId = DocumentsContract.getTreeDocumentId(treeUri),
        displayName = queryDisplayName(DocumentsContract.getTreeDocumentId(treeUri)),
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        sizeBytes = 0L
    )

    private fun queryDisplayName(documentId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: "skills"
    }

    private class SafDocument(
        private val resolver: ContentResolver,
        private val treeUri: Uri,
        override val documentId: String,
        override val displayName: String,
        private val mimeType: String,
        override val sizeBytes: Long
    ) : AgentSkillTreeDocument {
        override val type: AgentSkillDocumentType
            get() = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                AgentSkillDocumentType.DIRECTORY
            } else {
                AgentSkillDocumentType.FILE
            }

        override fun children(): List<AgentSkillTreeDocument> {
            if (type != AgentSkillDocumentType.DIRECTORY) return emptyList()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val children = ArrayList<AgentSkillTreeDocument>()
            try {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val childMime = cursor.getString(2)
                            ?: DocumentsContract.Document.MIME_TYPE_DIR
                        val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        children += SafDocument(
                            resolver = resolver,
                            treeUri = treeUri,
                            documentId = childId,
                            displayName = name,
                            mimeType = childMime,
                            sizeBytes = size.coerceAtLeast(0L)
                        )
                    }
                }
            } catch (failure: Exception) {
                skillFailure(
                    AgentSkillErrorCode.INVALID_PATH,
                    "无法读取所选 Skills 目录。"
                )
            }
            return children
        }

        override fun openStream(): java.io.InputStream {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            return try {
                resolver.openInputStream(uri)
                    ?: skillFailure(AgentSkillErrorCode.STORE_FAILURE, "无法打开 Skill 文件。")
            } catch (failure: AgentSkillException) {
                throw failure
            } catch (failure: Exception) {
                skillFailure(AgentSkillErrorCode.STORE_FAILURE, "无法读取 Skill 文件。")
            }
        }
    }
}

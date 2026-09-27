package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Imports the pinned Hy-MT2 package from a user-selected SAF directory. */
internal class OfflinePromptTranslationBundleInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val installGate = Mutex()

    suspend fun install(treeUri: Uri): VerifiedOfflinePromptTranslationBundle = installGate.withLock {
        withContext(Dispatchers.IO) {
            require(treeUri.scheme == "content" && DocumentsContract.isTreeUri(treeUri)) {
                "Select an offline translation package directory."
            }
            val activeRoot = File(appContext.filesDir, "offline-prompt-translation")
            val treeDocument = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val selectedChildren = children(treeUri, treeDocument)
            val translationDirectory = if (selectedChildren.keys == setOf(OfflinePromptTranslationContract.TRANSLATION_DIRECTORY)) {
                selectedChildren.getValue(OfflinePromptTranslationContract.TRANSLATION_DIRECTORY).also {
                    require(it.isDirectory) { "The translation package component is not a directory." }
                }.uri
            } else {
                treeDocument
            }
            val documents = children(treeUri, translationDirectory)
            val expected = setOf(
                OfflinePromptTranslationContract.MANIFEST_FILE_NAME,
                HyMt2PromptTranslationContract.MODEL_FILE,
                HyMt2PromptTranslationContract.MODEL_NOTICE,
                HyMt2PromptTranslationContract.RUNTIME_NOTICE
            )
            require(documents.keys == expected && documents.values.none { it.isDirectory }) {
                "The selected directory does not contain exactly the pinned Hy-MT2 model, manifest and notices."
            }

            val stage = File(appContext.filesDir, "offline-prompt-translation.install-${UUID.randomUUID()}")
            check(stage.mkdir()) { "Could not create an offline translation installation directory." }
            try {
                val stagedTranslation = File(stage, OfflinePromptTranslationContract.TRANSLATION_DIRECTORY)
                check(stagedTranslation.mkdir()) { "Could not stage the offline translation package." }
                val manifestDocument = documents.getValue(OfflinePromptTranslationContract.MANIFEST_FILE_NAME)
                val manifestBytes = readBounded(manifestDocument.uri, OfflinePromptTranslationContract.MAX_MANIFEST_BYTES)
                requirePinnedManifest(JSONObject(String(manifestBytes, Charsets.UTF_8)))
                File(stagedTranslation, OfflinePromptTranslationContract.MANIFEST_FILE_NAME).writeBytes(manifestBytes)
                copyPinned(
                    documents.getValue(HyMt2PromptTranslationContract.MODEL_FILE).uri,
                    File(stagedTranslation, HyMt2PromptTranslationContract.MODEL_FILE),
                    HyMt2PromptTranslationContract.MODEL_BYTES,
                    HyMt2PromptTranslationContract.MODEL_SHA256
                )
                copyPinned(
                    documents.getValue(HyMt2PromptTranslationContract.MODEL_NOTICE).uri,
                    File(stagedTranslation, HyMt2PromptTranslationContract.MODEL_NOTICE),
                    11_639L,
                    HyMt2PromptTranslationContract.MODEL_NOTICE_SHA256
                )
                copyPinned(
                    documents.getValue(HyMt2PromptTranslationContract.RUNTIME_NOTICE).uri,
                    File(stagedTranslation, HyMt2PromptTranslationContract.RUNTIME_NOTICE),
                    1_099L,
                    OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256
                )
                OfflinePromptTranslationBundleVerifier.requireVerified(stage)
                currentCoroutineContext().ensureActive()
                val preservedRoot = if (activeRoot.exists()) {
                    require(OfflinePromptTranslationBundleVerifier.verify(activeRoot) is
                        OfflinePromptTranslationBundleVerification.Rejected) {
                        "An offline translation package is already installed; its files were preserved."
                    }
                    File(appContext.filesDir, "offline-prompt-translation.invalid-${UUID.randomUUID()}").also {
                        check(activeRoot.renameTo(it)) {
                            "The damaged offline translation package could not be preserved for recovery."
                        }
                    }
                } else null
                if (!stage.renameTo(activeRoot)) {
                    if (preservedRoot != null) {
                        check(preservedRoot.renameTo(activeRoot)) {
                            "The verified package could not be activated and the previous package needs manual recovery: ${preservedRoot.name}"
                        }
                    }
                    error("The verified offline translation package could not be activated.")
                }
                OfflinePromptTranslationBundleVerifier.requireVerified(activeRoot)
            } finally {
                // Only this invocation's staging directory is disposable. Existing installs and
                // unrelated application/model files are never touched by a failed import.
                if (stage.exists()) stage.deleteRecursively()
            }
        }
    }

    private fun requirePinnedManifest(root: JSONObject) {
        val model = requireNotNull(root.optJSONObject("model")) { "Translation manifest model is missing." }
        val runtime = requireNotNull(root.optJSONObject("runtime")) { "Translation manifest runtime is missing." }
        require(
            root.optString("kind") == OfflinePromptTranslationContract.MANIFEST_KIND &&
                root.optInt("contractVersion") == 2 &&
                root.optString("translatorFamily") == OfflineTranslatorFamily.HY_MT2.name &&
                root.optString("runtimeKind") == OfflineTranslationRuntimeKind.LLAMA_CPP.name &&
                model.optString("sourceId") == HyMt2PromptTranslationContract.SOURCE_ID &&
                model.optString("sourceRevision") == HyMt2PromptTranslationContract.SOURCE_REVISION &&
                model.optString("sha256") == HyMt2PromptTranslationContract.MODEL_SHA256 &&
                model.optLong("sizeBytes") == HyMt2PromptTranslationContract.MODEL_BYTES &&
                runtime.optString("sourceRevision") == HyMt2PromptTranslationContract.RUNTIME_REVISION
        ) { "The selected manifest is not the pinned Hy-MT2 translation package." }
    }

    private suspend fun copyPinned(source: Uri, destination: File, expectedBytes: Long, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count.toLong()
                    require(total <= expectedBytes) { "Offline translation file exceeds its pinned size." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        require(total == expectedBytes && digest.digest().toLowercaseHex() == expectedSha256) {
            "Offline translation file does not match its pinned size and SHA-256."
        }
    }

    private suspend fun readBounded(uri: Uri, maximumBytes: Int): ByteArray = open(uri).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maximumBytes) { "Offline translation manifest is too large." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun open(uri: Uri): InputStream = appContext.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("Offline translation package file could not be opened.")

    private fun children(treeUri: Uri, directoryUri: Uri): Map<String, BundleDocument> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(directoryUri)
        )
        val result = linkedMapOf<String, BundleDocument>()
        appContext.contentResolver.query(
            childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                require(result.size < 8) { "Offline translation directory contains too many entries." }
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn)
                require(!name.isNullOrBlank() && name !in result) { "Offline translation directory has duplicate or unnamed entries." }
                result[name] = BundleDocument(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    cursor.getString(typeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        } ?: throw IllegalArgumentException("The selected translation directory could not be read.")
        return result
    }

    private data class BundleDocument(val uri: Uri, val isDirectory: Boolean)
}

private fun ByteArray.toLowercaseHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

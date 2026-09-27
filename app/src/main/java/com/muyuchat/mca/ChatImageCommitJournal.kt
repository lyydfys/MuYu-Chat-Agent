package com.muyuchat.mca

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable hand-off between publishing a generated image and committing it to the chat session.
 * The journal contains image metadata only; image bytes stay in [assetDirectory].
 */
internal class ChatImageCommitJournal(
    ownedRoot: File,
    assetDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val root = ownedRoot.canonicalFile
    private val assets = assetDirectory.canonicalFile
    private val rootKey = root.path
    private val lock = ROOT_LOCKS.getOrPut(rootKey) { ReentrantLock() }

    init {
        require(root != assets) { "Journal and image asset directories must be distinct." }
        require(root.isDirectory || root.mkdirs()) { "Unable to create the image commit journal directory." }
        require(root.canonicalFile == root) { "Image commit journal root changed while being initialized." }
    }

    /** A recoverable image commit waiting for the caller to durably save the chat session. */
    internal data class Record(
        val jobId: String,
        val chatSessionId: String,
        val requestId: String,
        /** JSON produced by [serializeImageAssetRecord]. */
        val imageAssetMetadataJson: String,
        val stagedAtEpochMs: Long = 0L
    ) {
        fun imageAssetRecord(): ImageAssetRecord = deserializeImageAssetRecord(imageAssetMetadataJson)
    }

    internal data class RejectedEntry(val fileName: String, val reason: String)

    internal data class ScanResult(
        val validEntries: List<Record>,
        val rejectedEntries: List<RejectedEntry>
    )

    /**
     * Persist an entry before asking the caller to commit its image metadata into the session.
     * Repeating the same job ID replaces its prior staged record atomically.
     */
    fun stage(record: Record): Record = lock.withLock {
        validateRecord(record, requireAsset = true)
        val normalized = record.copy(stagedAtEpochMs = record.stagedAtEpochMs.takeIf { it > 0L } ?: clock())
        val target = journalFile(normalized.jobId)
        writeAtomic(target, normalized)
        normalized
    }

    /**
     * Read all well-formed entries. Bad or unsafe files are reported individually and never
     * escape the journal root or abort enumeration of other entries.
     */
    fun enumerate(): ScanResult = lock.withLock {
        val files = root.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .orEmpty()
            .sortedBy { it.name }
        val valid = mutableListOf<Record>()
        val rejected = mutableListOf<RejectedEntry>()
        files.forEach { file ->
            val result = runCatching { readAndValidate(file) }
            result.onSuccess(valid::add).onFailure { error ->
                rejected += RejectedEntry(
                    fileName = file.name.take(MAX_REPORTED_FILENAME_CHARS),
                    // Parser exceptions can quote untrusted file contents; keep diagnostics generic.
                    reason = if (error is SecurityException) {
                        "Journal entry is inaccessible or unsafe."
                    } else {
                        "Journal entry is corrupt or violates safety checks."
                    }
                )
            }
        }
        ScanResult(validEntries = valid, rejectedEntries = rejected)
    }

    /**
     * Remove an entry only after the caller confirms that the session transaction is durable.
     * The request ID check prevents a stale completion from deleting a newer retry's journal.
     */
    fun removeAfterDurableCommit(
        jobId: String,
        requestId: String,
        durableCommitConfirmed: Boolean
    ): Boolean {
        if (!durableCommitConfirmed) return false
        validateIdentifier(jobId, "jobId")
        validateIdentifier(requestId, "requestId")
        return lock.withLock {
            val file = journalFile(jobId)
            if (!file.exists()) return@withLock false
            val current = runCatching { readAndValidate(file) }.getOrNull() ?: return@withLock false
            if (current.jobId != jobId || current.requestId != requestId) return@withLock false
            requireJournalChild(file)
            file.delete() || !file.exists()
        }
    }

    /** Remove a staged entry after the caller durably rolled back the corresponding asset file. */
    fun removeAfterConfirmedAssetRollback(
        jobId: String,
        requestId: String,
        rollbackConfirmed: Boolean
    ): Boolean {
        if (!rollbackConfirmed) return false
        validateIdentifier(jobId, "jobId")
        validateIdentifier(requestId, "requestId")
        return lock.withLock {
            val file = journalFile(jobId)
            if (!file.exists()) return@withLock false
            val json = runCatching { readEnvelope(file) }.getOrNull() ?: return@withLock false
            if (json.optString("jobId") != jobId || json.optString("requestId") != requestId) {
                return@withLock false
            }
            requireJournalChild(file)
            file.delete() || !file.exists()
        }
    }

    private fun readAndValidate(file: File): Record {
        val json = readEnvelope(file)
        val record = Record(
            jobId = json.getString("jobId"),
            chatSessionId = json.getString("chatSessionId"),
            requestId = json.getString("requestId"),
            imageAssetMetadataJson = json.getString("imageAssetMetadataJson"),
            stagedAtEpochMs = json.getLong("stagedAtEpochMs")
        )
        validateRecord(record, requireAsset = true)
        require(record.stagedAtEpochMs > 0L) { "Journal timestamp is invalid." }
        return record
    }

    private fun readEnvelope(file: File): JSONObject {
        requireJournalChild(file)
        require(!Files.isSymbolicLink(file.toPath())) { "Journal entry must not be a symbolic link." }
        require(file.length() in 1L..MAX_JOURNAL_BYTES) { "Journal entry size is invalid." }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.optInt("schemaVersion", -1) == SCHEMA_VERSION) {
            "Unsupported image commit journal version."
        }
        val jobId = json.getString("jobId")
        validateIdentifier(jobId, "jobId")
        require(file.name == journalFileName(jobId)) { "Journal filename does not match its job ID." }
        return json
    }

    private fun validateRecord(record: Record, requireAsset: Boolean) {
        validateIdentifier(record.jobId, "jobId")
        validateIdentifier(record.chatSessionId, "chatSessionId")
        validateIdentifier(record.requestId, "requestId")
        require(record.imageAssetMetadataJson.toByteArray(Charsets.UTF_8).size in 1..MAX_ASSET_METADATA_BYTES) {
            "Image asset metadata size is invalid."
        }
        val asset = deserializeImageAssetRecord(record.imageAssetMetadataJson)
        require(asset.chatSessionId == null || asset.chatSessionId == record.chatSessionId) {
            "Image asset belongs to a different chat session."
        }
        requireNoCredentialFields(asset.generationMetadataJson)
        if (requireAsset) validateImageAssetFile(asset)
    }

    private fun validateImageAssetFile(asset: ImageAssetRecord) {
        require(assets.isDirectory) { "Image asset directory is unavailable." }
        require(assets.canonicalFile == assets) { "Image asset directory changed while being validated." }
        val file = fileFromOwnedAssetUri(asset.uriString)
        require(!Files.isSymbolicLink(file.toPath())) { "Image asset must not be a symbolic link." }
        val canonicalFile = file.canonicalFile
        require(canonicalFile.parentFile == assets) { "Image asset must be a direct child of its asset directory." }
        require(file.isFile && canonicalFile.isFile) { "Image asset file is missing." }
        require(asset.sizeBytes > 0L && canonicalFile.length() == asset.sizeBytes) {
            "Image asset size does not match its recorded metadata."
        }
    }

    private fun fileFromOwnedAssetUri(rawUri: String): File {
        val uri = runCatching { URI(rawUri) }.getOrElse { throw IllegalArgumentException("Image asset URI is invalid.") }
        require(uri.isAbsolute && uri.scheme.equals("file", ignoreCase = true)) {
            "Image asset must use an owned local file URI."
        }
        require(uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
            "Image asset URI must not include a host, query, or fragment."
        }
        val path = requireNotNull(uri.path) { "Image asset URI has no path." }
        require(path.isNotBlank() && path.split('/', '\\').none { it == ".." }) {
            "Image asset URI contains an unsafe path."
        }
        return File(uri)
    }

    private fun requireJournalChild(file: File) {
        require(file.canonicalFile.parentFile == root) { "Journal entry must be a direct child of its owned root." }
    }

    private fun journalFile(jobId: String): File {
        validateIdentifier(jobId, "jobId")
        val file = File(root, journalFileName(jobId))
        requireJournalChild(file)
        return file
    }

    private fun journalFileName(jobId: String): String = "$FILE_PREFIX${sha256(jobId)}$FILE_SUFFIX"

    private fun writeAtomic(target: File, record: Record) {
        requireJournalChild(target)
        val temp = File(root, ".${target.name}.${java.util.UUID.randomUUID()}.tmp")
        requireJournalChild(temp)
        try {
            val bytes = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("jobId", record.jobId)
                .put("chatSessionId", record.chatSessionId)
                .put("requestId", record.requestId)
                .put("imageAssetMetadataJson", record.imageAssetMetadataJson)
                .put("stagedAtEpochMs", record.stagedAtEpochMs)
                .toString()
                .toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_JOURNAL_BYTES) { "Image commit journal is too large." }
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun requireNoCredentialFields(rawGenerationMetadata: String) {
        if (rawGenerationMetadata.isBlank()) return
        val rootJson = runCatching { JSONObject(rawGenerationMetadata) }.getOrElse {
            throw IllegalArgumentException("Image generation metadata is not valid JSON.")
        }
        require(!containsCredentialField(rootJson)) {
            "Image metadata contains a credential field and cannot be journaled."
        }
    }

    private fun containsCredentialField(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            isCredentialField(key) || containsCredentialField(value.opt(key))
        }
        is org.json.JSONArray -> (0 until value.length()).any { containsCredentialField(value.opt(it)) }
        else -> false
    }

    private fun isCredentialField(raw: String): Boolean {
        val key = raw.lowercase().filter(Char::isLetterOrDigit)
        return key in FORBIDDEN_CREDENTIAL_KEYS ||
            key.endsWith("apikey") || key.endsWith("clientsecret") || key.endsWith("password") ||
            key.endsWith("authorization") || key.endsWith("accesstoken") || key.endsWith("refreshtoken")
    }

    private fun validateIdentifier(value: String, name: String) {
        require(value.length in 1..MAX_IDENTIFIER_CHARS && IDENTIFIER_PATTERN.matches(value)) {
            "$name is invalid."
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val FILE_PREFIX = "pending-"
        private const val FILE_SUFFIX = ".json"
        private const val MAX_JOURNAL_BYTES = 4L * 1024L * 1024L
        private const val MAX_ASSET_METADATA_BYTES = 3 * 1024 * 1024
        private const val MAX_IDENTIFIER_CHARS = 256
        private const val MAX_REPORTED_FILENAME_CHARS = 160
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        private val FORBIDDEN_CREDENTIAL_KEYS = setOf(
            "apikey", "authorization", "token", "accesstoken", "refreshtoken", "clientsecret",
            "secret", "secretkey", "password", "credential", "credentials", "bearertoken"
        )
        private val ROOT_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

        fun serializeImageAssetRecord(record: ImageAssetRecord): String = JSONObject()
            .put("id", record.id)
            .put("name", record.name)
            .put("uriString", record.uriString)
            .put("source", record.source)
            .put("prompt", record.prompt)
            .put("createdAt", record.createdAt)
            .put("sizeBytes", record.sizeBytes)
            .put("width", record.width)
            .put("height", record.height)
            .put("generationMetadataJson", record.generationMetadataJson)
            .put("favorite", record.favorite)
            .put("chatSessionId", record.chatSessionId ?: JSONObject.NULL)
            .put("projectId", record.projectId ?: JSONObject.NULL)
            .toString()

        fun deserializeImageAssetRecord(raw: String): ImageAssetRecord {
            require(raw.toByteArray(Charsets.UTF_8).size in 1..MAX_ASSET_METADATA_BYTES) {
                "Image asset metadata size is invalid."
            }
            val json = JSONObject(raw)
            require(json.length() == IMAGE_ASSET_FIELDS.size && json.keys().asSequence().all { it in IMAGE_ASSET_FIELDS }) {
                "Image asset metadata fields are invalid."
            }
            return ImageAssetRecord(
                id = json.getString("id"),
                name = json.getString("name"),
                uriString = json.getString("uriString"),
                source = json.getString("source"),
                prompt = json.optString("prompt", ""),
                createdAt = json.getLong("createdAt"),
                sizeBytes = json.getLong("sizeBytes"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                generationMetadataJson = json.optString("generationMetadataJson", ""),
                favorite = json.optBoolean("favorite", false),
                chatSessionId = json.optString("chatSessionId").takeUnless { it.isBlank() || json.isNull("chatSessionId") },
                projectId = json.optString("projectId").takeUnless { it.isBlank() || json.isNull("projectId") }
            ).also { asset ->
                require(asset.id.isNotBlank() && asset.id.length <= MAX_IDENTIFIER_CHARS) { "Image asset ID is invalid." }
                require(asset.name.isNotBlank() && asset.name.length <= MAX_ASSET_NAME_CHARS) { "Image asset name is invalid." }
                require(asset.source.isNotBlank() && asset.source.length <= MAX_ASSET_NAME_CHARS) { "Image asset source is invalid." }
                require(asset.prompt.length <= MAX_PROMPT_CHARS) { "Image asset prompt is too long." }
            }
        }

        private val IMAGE_ASSET_FIELDS = setOf(
            "id", "name", "uriString", "source", "prompt", "createdAt", "sizeBytes", "width", "height",
            "generationMetadataJson", "favorite", "chatSessionId", "projectId"
        )
        private const val MAX_ASSET_NAME_CHARS = 4_096
        private const val MAX_PROMPT_CHARS = 1_000_000

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

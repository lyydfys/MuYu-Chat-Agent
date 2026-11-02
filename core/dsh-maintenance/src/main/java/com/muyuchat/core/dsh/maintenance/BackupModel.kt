package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup scope, secret policy and manifest for D22b.
 *
 * Default export contains only user data and declarations: sessions, attachments, config,
 * plugin/skill declarations, user patches, profile metadata and the user workspace. Credentials,
 * cookies, tokens, caches, native executables, pid/socket files, interrupted downloads and
 * device-local tuning are excluded. A config file that looks like it carries a secret value is
 * skipped whole and recorded — never partially archived and never silently passed through.
 */

/** Hard bounds. Values are validated before any entry is read or written. */
data class DshBackupLimits(
    val maxEntries: Int = 200_000,
    val maxTotalBytes: Long = 8L * 1024L * 1024L * 1024L,
    val maxEntryBytes: Long = 2L * 1024L * 1024L * 1024L,
    /** Uncompressed / compressed bound (zip-bomb defence). */
    val maxCompressionRatio: Int = 200,
    val maxPathChars: Int = 1_024
)

/** Archive schema major. Any other major is refused instead of guessed. */
const val DSH_BACKUP_SCHEMA_VERSION: Int = 1
const val MANIFEST_ENTRY_NAME: String = "dsh-backup-manifest.json"

/** One archived file. The manifest is the authoritative inventory. */
data class DshBackupEntry(
    val path: String,
    val sha256: String,
    val bytes: Long
)

enum class DshBackupSkipReason {
    SECRET_NAME,
    SECRET_CONTENT,
    CACHE,
    EXECUTABLE,
    RUNTIME_PID_SOCKET,
    INTERRUPTED_DOWNLOAD,
    DEVICE_TUNING,
    LINK,
    LIMIT,
    UNREADABLE
}

data class DshBackupSkipped(val path: String, val reason: DshBackupSkipReason)

/** True when the archive container itself is encrypted by a user-selected cipher. */
data class DshBackupEncryption(val enabled: Boolean = false, val cipherId: String? = null) {
    init {
        require(!enabled || !cipherId.isNullOrBlank()) { "dsh: encrypted backup needs a cipher id" }
    }

    companion object {
        val NONE = DshBackupEncryption()
    }
}

data class DshBackupManifest(
    val schemaVersion: Int,
    val createdAtMillis: Long,
    val sourceRoots: List<String>,
    val entries: List<DshBackupEntry>,
    val skipped: List<DshBackupSkipped>,
    val totalBytes: Long,
    val closureId: String? = null,
    val encryption: DshBackupEncryption = DshBackupEncryption.NONE,
    /** Bounded, secret-free note describing the official reader/closure used for consistency. */
    val note: String = ""
) {
    fun validateShape(): DshMaintenanceFailure? {
        if (schemaVersion != DSH_BACKUP_SCHEMA_VERSION) {
            return DshMaintenanceFailure(
                DshMaintenanceError.VERSION_UNSUPPORTED,
                "backup schema major $schemaVersion is not supported",
                detail = "supported=$DSH_BACKUP_SCHEMA_VERSION"
            )
        }
        if (entries.size > 200_000) {
            return DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "too many manifest entries")
        }
        val seen = HashSet<String>()
        for (entry in entries) {
            if (!DshMaintenanceIo.isSafeRelativePath(entry.path)) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.ARCHIVE_MALICIOUS,
                    "manifest entry path is not a safe relative path"
                )
            }
            if (!seen.add(entry.path)) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.ARCHIVE_INVALID,
                    "manifest repeats an entry"
                )
            }
            if (entry.bytes < 0) {
                return DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "negative entry size")
            }
        }
        return null
    }

    companion object {
        const val CREATOR = "mca-dsh-maintenance"
    }
}

internal object DshBackupManifestCodec {
    fun encode(manifest: DshBackupManifest): String {
        val json = JSONObject()
        json.put("format", "mca.dsh.backup")
        json.put("schemaVersion", manifest.schemaVersion)
        json.put("createdAt", manifest.createdAtMillis)
        json.put("creator", DshBackupManifest.CREATOR)
        json.put("closureId", manifest.closureId ?: "")
        json.put("totalBytes", manifest.totalBytes)
        json.put("note", manifest.note.take(300))
        json.put("encryption", JSONObject().apply {
            put("enabled", manifest.encryption.enabled)
            put("cipherId", manifest.encryption.cipherId ?: "")
        })
        val roots = JSONArray()
        manifest.sourceRoots.forEach(roots::put)
        json.put("sourceRoots", roots)
        val entries = JSONArray()
        for (entry in manifest.entries) {
            entries.put(
                JSONObject().apply {
                    put("path", entry.path)
                    put("sha256", entry.sha256)
                    put("bytes", entry.bytes)
                }
            )
        }
        json.put("entries", entries)
        val skipped = JSONArray()
        for (skip in manifest.skipped) {
            skipped.put(JSONObject().apply { put("path", skip.path); put("reason", skip.reason.name) })
        }
        json.put("skipped", skipped)
        return json.toString()
    }

    fun decode(text: String): DshBackupManifest? = runCatching {
        val json = JSONObject(text)
        if (json.optString("format") != "mca.dsh.backup") return null
        val entriesJson = json.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<DshBackupEntry>(entriesJson.length())
        for (index in 0 until entriesJson.length()) {
            val entry = entriesJson.optJSONObject(index) ?: return null
            entries.add(
                DshBackupEntry(
                    path = entry.optString("path"),
                    sha256 = entry.optString("sha256"),
                    bytes = entry.optLong("bytes")
                )
            )
        }
        val skippedJson = json.optJSONArray("skipped") ?: JSONArray()
        val skipped = ArrayList<DshBackupSkipped>(skippedJson.length())
        for (index in 0 until skippedJson.length()) {
            val skip = skippedJson.optJSONObject(index) ?: continue
            val reason = DshBackupSkipReason.entries.firstOrNull { it.name == skip.optString("reason") }
                ?: DshBackupSkipReason.UNREADABLE
            skipped.add(DshBackupSkipped(skip.optString("path"), reason))
        }
        val rootsJson = json.optJSONArray("sourceRoots") ?: JSONArray()
        val roots = ArrayList<String>(rootsJson.length())
        for (index in 0 until rootsJson.length()) roots.add(rootsJson.optString(index))
        val encryptionJson = json.optJSONObject("encryption") ?: JSONObject()
        DshBackupManifest(
            schemaVersion = json.optInt("schemaVersion", -1),
            createdAtMillis = json.optLong("createdAt"),
            sourceRoots = roots,
            entries = entries,
            skipped = skipped,
            totalBytes = json.optLong("totalBytes"),
            closureId = json.optString("closureId").takeIf { it.isNotEmpty() },
            encryption = DshBackupEncryption(
                enabled = encryptionJson.optBoolean("enabled"),
                cipherId = encryptionJson.optString("cipherId").takeIf { it.isNotEmpty() }
            ),
            note = json.optString("note")
        )
    }.getOrNull()
}

/** One physical root to archive and the prefix it gets inside the archive. */
data class DshBackupSource(val root: File, val archivePrefix: String) {
    init {
        require(archivePrefix.isNotBlank() && DshMaintenanceIo.isSafeRelativePath(archivePrefix)) {
            "dsh: backup source prefix must be a safe relative path"
        }
    }
}

data class DshBackupSelection(
    val entries: List<DshBackupEntry>,
    val skipped: List<DshBackupSkipped>,
    val totalBytes: Long,
    val sourceRoots: List<String>,
    /** Archive path -> physical file, produced by the planner and consumed by the writer. */
    val physicalFiles: Map<String, File> = emptyMap(),
    val failure: DshMaintenanceFailure? = null
)

/** Default exclusion and secret-detection policy. Exposed for tests and the console preview. */
object DshBackupPolicy {

    private val EXCLUDED_DIRECTORY_NAMES = setOf(
        "cache", ".cache", "tmp", ".tmp", "temp", "logs", "log",
        "node_modules", ".git", "payload", "staging", "quarantine"
    )

    private val EXCLUDED_NAME_PATTERNS = listOf(
        Regex("(?i)^\\.credentials.*"),
        Regex("(?i).*credential.*"),
        Regex("(?i).*cookie.*"),
        Regex("(?i).*[._-]secret.*"),
        Regex("(?i)^secrets?\\..*"),
        Regex("(?i).*password.*"),
        Regex("(?i)^tokens?\\..*"),
        Regex("(?i)^auth\\.(json|ya?ml)$"),
        Regex("(?i)^\\.netrc$"),
        Regex("(?i)^id_(rsa|ed25519|ecdsa)(\\.pub)?$"),
        Regex("(?i).*\\.(pem|key|p12|pfx|keystore|jks)$"),
        Regex("(?i).*device[-_]tuning.*"),
        Regex("(?i).*hardware[-_]profile.*")
    )

    private val EXECUTABLE_SUFFIXES = listOf(".so", ".elf", ".dex", ".apk", ".jar", ".node", ".wasm")

    private val INTERRUPTED_SUFFIXES = listOf(".part", ".crdownload", ".download", ".tmp", ".partial")

    private val SECRET_ASSIGNMENT = Regex(
        "(?i)[\"']?(api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|" +
            "bearer[_-]?token|client[_-]?secret|secret[_-]?key|password|passwd)[\"']?\\s*[:=]\\s*" +
            "[\"']?([A-Za-z0-9_\\-\\.]{8,})"
    )

    private val SECRET_PREFIX = Regex("(?i)\\b(sk|pk|ghp|gho|xox[bap]|AKIA)[A-Za-z0-9_\\-]{12,}")

    private val CONFIG_TEXT_SUFFIXES = listOf(".json", ".yaml", ".yml", ".toml", ".ini", ".conf", ".env")

    /** True when [archivePath] (prefix included) must never enter an ordinary backup. */
    fun skipReason(archivePath: String): DshBackupSkipReason? {
        val segments = archivePath.split('/')
        if (segments.any { it.lowercase() in EXCLUDED_DIRECTORY_NAMES }) return DshBackupSkipReason.CACHE
        val name = segments.lastOrNull() ?: return null
        if (EXCLUDED_NAME_PATTERNS.any { it.matches(name) }) return DshBackupSkipReason.SECRET_NAME
        val lower = name.lowercase()
        if (EXECUTABLE_SUFFIXES.any { lower.endsWith(it) }) return DshBackupSkipReason.EXECUTABLE
        if (lower.endsWith(".pid") || lower.endsWith(".sock") || lower.endsWith(".socket")) {
            return DshBackupSkipReason.RUNTIME_PID_SOCKET
        }
        if (INTERRUPTED_SUFFIXES.any { lower.endsWith(it) }) return DshBackupSkipReason.INTERRUPTED_DOWNLOAD
        return null
    }

    /** Config-ish textual files are scanned for obvious secret values. */
    fun shouldScanContent(archivePath: String): Boolean {
        val lower = archivePath.lowercase()
        if (!CONFIG_TEXT_SUFFIXES.any { lower.endsWith(it) }) return false
        if (lower.startsWith("home/sessions/") || lower.startsWith("home/attachments/")) return false
        if (lower.startsWith("workspace/")) return false
        return lower.startsWith("home/config/") ||
            lower.startsWith("home/profile/") ||
            lower.startsWith("home/plugins/") ||
            lower.count { it == '/' } == 1
    }

    /** Returns true when [text] visibly assigns a secret value (conservative: skip the file). */
    fun containsSecret(text: String): Boolean =
        SECRET_ASSIGNMENT.containsMatchIn(text) || SECRET_PREFIX.containsMatchIn(text)
}

/** Walks the declared roots and produces the exact archive inventory. */
class DshBackupPlanner(
    val limits: DshBackupLimits = DshBackupLimits(),
    private val policy: DshBackupPolicy = DshBackupPolicy
) {
    fun plan(sources: List<DshBackupSource>): DshBackupSelection {
        val entries = ArrayList<DshBackupEntry>()
        val physicalFiles = LinkedHashMap<String, File>()
        val skipped = ArrayList<DshBackupSkipped>()
        var totalBytes = 0L
        var limitFailure: DshMaintenanceFailure? = null
        val visitedDirs = HashSet<String>()

        fun addSkip(path: String, reason: DshBackupSkipReason) {
            if (skipped.size < MAX_SKIPPED_REPORTED) skipped.add(DshBackupSkipped(path, reason))
        }

        fun walk(file: File, archivePath: String, depth: Int) {
            if (limitFailure != null) return
            if (depth > MAX_DEPTH) {
                addSkip(archivePath, DshBackupSkipReason.LIMIT)
                return
            }
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                addSkip(archivePath, DshBackupSkipReason.LINK)
                return
            }
            if (file.isDirectory) {
                val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (!visitedDirs.add(canonical)) return
                val children = file.listFiles() ?: return
                for (child in children.sortedBy { it.name }) {
                    walk(child, "$archivePath/${child.name}", depth + 1)
                }
                return
            }
            if (!file.isFile) return
            if (archivePath.length > limits.maxPathChars ||
                !DshMaintenanceIo.isSafeRelativePath(archivePath)
            ) {
                addSkip(archivePath, DshBackupSkipReason.LIMIT)
                return
            }
            val reason = policy.skipReason(archivePath)
            if (reason != null) {
                addSkip(archivePath, reason)
                return
            }
            val size = file.length()
            if (size > limits.maxEntryBytes) {
                addSkip(archivePath, DshBackupSkipReason.LIMIT)
                return
            }
            if (entries.size >= limits.maxEntries) {
                limitFailure = DshMaintenanceFailure(
                    DshMaintenanceError.SPACE_INSUFFICIENT,
                    "backup exceeds its entry budget"
                )
                return
            }
            if (policy.shouldScanContent(archivePath) && size <= MAX_CONTENT_SCAN_BYTES) {
                val text = runCatching { DshMaintenanceIo.readText(file) }.getOrNull()
                if (text != null && policy.containsSecret(text)) {
                    addSkip(archivePath, DshBackupSkipReason.SECRET_CONTENT)
                    return
                }
            }
            val sha = runCatching { DshMaintenanceIo.sha256(file) }.getOrNull()
            if (sha == null) {
                addSkip(archivePath, DshBackupSkipReason.UNREADABLE)
                return
            }
            if (totalBytes + size > limits.maxTotalBytes) {
                limitFailure = DshMaintenanceFailure(
                    DshMaintenanceError.SPACE_INSUFFICIENT,
                    "backup exceeds its total size budget"
                )
                return
            }
            totalBytes += size
            entries.add(DshBackupEntry(archivePath, sha, size))
            physicalFiles[archivePath] = file
        }

        for (source in sources) {
            val root = source.root
            if (!root.isDirectory) continue
            val children = root.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                walk(child, "${source.archivePrefix}/${child.name}", 0)
            }
        }
        return DshBackupSelection(
            entries = entries.sortedBy { it.path },
            skipped = skipped,
            totalBytes = totalBytes,
            sourceRoots = sources.filter { it.root.isDirectory }.map { it.archivePrefix },
            physicalFiles = physicalFiles,
            failure = limitFailure
        )
    }

    private companion object {
        const val MAX_DEPTH = 24
        const val MAX_SKIPPED_REPORTED = 500
        const val MAX_CONTENT_SCAN_BYTES = 512L * 1024L
    }
}

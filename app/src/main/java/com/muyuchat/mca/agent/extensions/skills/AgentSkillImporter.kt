package com.muyuchat.mca.agent.extensions.skills

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

internal object AgentSkillScriptPolicy {
    private const val SCRIPTS_DIRECTORY = "scripts"

    private val SCRIPT_EXTENSIONS = setOf(
        "sh", "bash", "zsh", "fish", "py", "pyw", "js", "mjs", "cjs", "ts",
        "rb", "pl", "php", "ps1", "bat", "cmd", "lua", "r", "groovy", "kt", "kts", "java"
    )

    private val RESOURCE_EXTENSIONS = setOf(
        "md", "markdown", "txt", "rst", "json", "yaml", "yml", "csv", "tsv",
        "xml", "pdf", "png", "jpg", "jpeg", "webp", "gif"
    )

    private val EXTENSIONLESS_RESOURCES = setOf("LICENSE", "NOTICE", "CHANGELOG")

    /** Scripts are preview-only: their paths are shown, but they are never opened or installed. */
    fun isScript(path: String): Boolean {
        if (path.split('/').firstOrNull() == SCRIPTS_DIRECTORY) return true
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        return name.substring(dot + 1).lowercase() in SCRIPT_EXTENSIONS
    }

    fun isInstalledResource(path: String): Boolean {
        if (path == AgentSkillManifest.ENTRY_PATH) return true
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name in EXTENSIONLESS_RESOURCES
        return name.substring(dot + 1).lowercase() in RESOURCE_EXTENSIONS
    }
}

data class AgentSkillPreviewFile(val path: String, val sizeBytes: Long)

/** Import preview shown before installation. Scripts are listed but never executed or installed. */
data class AgentSkillPackagePreview(
    val name: String,
    val description: String,
    val packageVersion: String,
    val sourceUri: String,
    val sourceFingerprint: String,
    val contentSha256: String,
    val files: List<AgentSkillPreviewFile>,
    val previewOnlyScripts: List<String>,
    val declaredToolPatterns: List<String>,
    val metadata: Map<String, AgentSkillMetadataValue>
)

private class AgentSkillPackageContent(
    val parsed: AgentSkillParsedDocument,
    val files: List<AgentSkillSourceFile>,
    val previewOnlyScripts: List<String>
)

/**
 * Imports a directory/SAF tree or ZIP into a private immutable snapshot (architecture 10.6 / 12.1).
 *
 * Every limit is checked before/during the copy; scripts are only listed (never opened) and the
 * `SKILL.md` frontmatter is parsed with [AgentSkillFrontmatterParser]. Real SAF access is provided
 * by an [AgentSkillTreeDocument] adapter outside this package (NOT_RUN in the JVM tests).
 */
class AgentSkillImporter(
    private val snapshots: AgentSkillSnapshotRepository,
    private val parser: AgentSkillFrontmatterParser = AgentSkillFrontmatterParser(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun importZip(
        sourceUri: String,
        packageFile: File,
        previous: AgentSkillManifest? = null,
        enable: Boolean = false
    ): AgentSkillManifest {
        if (packageFile.length() > AgentSkillLimits.MAX_PACKAGE_BYTES) {
            skillFailure(
                AgentSkillErrorCode.PACKAGE_TOO_LARGE,
                "package exceeds ${AgentSkillLimits.MAX_PACKAGE_BYTES} bytes"
            )
        }
        FileRandomAccessSource(packageFile).use { source ->
            AgentSkillZipArchive(source).use { archive ->
                val content = readZipContent(sourceUri, archive)
                return install(sourceUri, content, previous, enable, "zip")
            }
        }
    }

    fun importTree(
        sourceUri: String,
        root: AgentSkillTreeDocument,
        previous: AgentSkillManifest? = null,
        enable: Boolean = false
    ): AgentSkillManifest {
        val content = readTreeContent(sourceUri, root)
        return install(sourceUri, content, previous, enable, "tree")
    }

    fun inspectZip(sourceUri: String, packageFile: File): AgentSkillPackagePreview {
        if (packageFile.length() > AgentSkillLimits.MAX_PACKAGE_BYTES) {
            skillFailure(
                AgentSkillErrorCode.PACKAGE_TOO_LARGE,
                "package exceeds ${AgentSkillLimits.MAX_PACKAGE_BYTES} bytes"
            )
        }
        FileRandomAccessSource(packageFile).use { source ->
            AgentSkillZipArchive(source).use { archive ->
                return preview(sourceUri, readZipContent(sourceUri, archive), "zip")
            }
        }
    }

    fun inspectTree(sourceUri: String, root: AgentSkillTreeDocument): AgentSkillPackagePreview =
        preview(sourceUri, readTreeContent(sourceUri, root), "tree")

    private fun install(
        sourceUri: String,
        content: AgentSkillPackageContent,
        previous: AgentSkillManifest?,
        enable: Boolean,
        sourceKind: String
    ): AgentSkillManifest {
        val frontmatter = content.parsed.frontmatter
        val id = previous?.id ?: deriveId(frontmatter.name, content.files, content.previewOnlyScripts)
        val version = previous?.let { it.version + 1L } ?: 1L
        val enabled = previous?.enabled ?: enable
        val stored = snapshots.create(id, version, content.files)
        val declaredPatterns = frontmatter.allowedTools?.patterns?.map { it.canonical }.orEmpty()
        return AgentSkillManifest(
            id = id,
            name = frontmatter.name,
            description = frontmatter.description,
            packageVersion = frontmatter.packageVersion,
            source = AgentSkillSource(sourceUri, "$sourceKind:${stored.sha256}"),
            sha256 = stored.sha256,
            entryPath = AgentSkillManifest.ENTRY_PATH,
            enabled = enabled,
            declaredToolPatterns = declaredPatterns,
            resourcePaths = stored.files.map { it.relativePath }
                .filter { it != AgentSkillManifest.ENTRY_PATH }
                .sorted(),
            version = version,
            snapshotId = stored.snapshotId,
            installedAtMillis = clock(),
            metadata = frontmatter.metadata
        )
    }

    private fun preview(
        sourceUri: String,
        content: AgentSkillPackageContent,
        sourceKind: String
    ): AgentSkillPackagePreview {
        val hash = AgentSkillContentHash.digest(content.files)
        val frontmatter = content.parsed.frontmatter
        return AgentSkillPackagePreview(
            name = frontmatter.name,
            description = frontmatter.description,
            packageVersion = frontmatter.packageVersion,
            sourceUri = sourceUri,
            sourceFingerprint = "$sourceKind:$hash",
            contentSha256 = hash,
            files = content.files.map { AgentSkillPreviewFile(it.relativePath, it.sizeBytes) },
            previewOnlyScripts = content.previewOnlyScripts,
            declaredToolPatterns = frontmatter.allowedTools?.patterns?.map { it.canonical }.orEmpty(),
            metadata = frontmatter.metadata
        )
    }

    private fun readZipContent(
        sourceUri: String,
        archive: AgentSkillZipArchive
    ): AgentSkillPackageContent {
        val entries = archive.entries()
        val skillEntry = entries.firstOrNull { it.path == AgentSkillManifest.ENTRY_PATH && !it.isDirectory }
            ?: skillFailure(
                AgentSkillErrorCode.MISSING_SKILL_MD,
                "the package root must contain ${AgentSkillManifest.ENTRY_PATH}"
            )
        val skillMarkdown = archive.openStream(skillEntry).use { readBounded(it, AgentSkillLimits.MAX_SKILL_MD_BYTES) }
        val parsed = parser.parse(skillMarkdown)
        val files = ArrayList<AgentSkillSourceFile>()
        val scripts = ArrayList<String>()
        for (entry in entries.sortedBy { it.path }) {
            if (entry.isDirectory) continue
            when {
                entry.path == AgentSkillManifest.ENTRY_PATH -> files += zipSourceFile(archive, entry)
                AgentSkillScriptPolicy.isScript(entry.path) -> scripts += entry.path
                AgentSkillScriptPolicy.isInstalledResource(entry.path) -> files += zipSourceFile(archive, entry)
                else -> skillFailure(
                    AgentSkillErrorCode.UNSUPPORTED_RESOURCE,
                    "unsupported resource type (only Markdown/text/supported attachments are installed)",
                    entry.path
                )
            }
        }
        return AgentSkillPackageContent(parsed, files, scripts.sorted())
    }

    private fun readTreeContent(
        sourceUri: String,
        root: AgentSkillTreeDocument
    ): AgentSkillPackageContent {
        if (root.type != AgentSkillDocumentType.DIRECTORY) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "selected Skill root must be a directory")
        }
        if (root.documentId.isBlank()) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "selected Skill root has no stable document id")
        }
        val visited = HashSet<String>()
        if (!visited.add(root.documentId)) {
            skillFailure(AgentSkillErrorCode.LOOP_DOCUMENT_ID, "provider loop detected at the Skill root")
        }
        val files = ArrayList<AgentSkillSourceFile>()
        val scripts = ArrayList<String>()
        val paths = HashSet<String>()
        val directories = HashSet<String>()
        var entryCount = 0
        var expandedBytes = 0L

        fun visit(document: AgentSkillTreeDocument, parentPath: String, depth: Int) {
            if (depth > AgentSkillLimits.MAX_PATH_DEPTH) {
                skillFailure(AgentSkillErrorCode.PATH_DEPTH_EXCEEDED, "document tree is too deep")
            }
            val leaf = AgentSkillPathPolicy.normalizeLeafName(document.displayName)
            val path = if (parentPath.isEmpty()) leaf else "$parentPath/$leaf"
            when (document.type) {
                AgentSkillDocumentType.SYMLINK -> skillFailure(
                    AgentSkillErrorCode.SYMLINK_REJECTED,
                    "symlink documents are not allowed",
                    path
                )
                AgentSkillDocumentType.OTHER -> skillFailure(
                    AgentSkillErrorCode.NON_REGULAR_FILE,
                    "only regular files and directories are allowed",
                    path
                )
                AgentSkillDocumentType.DIRECTORY -> {
                    entryCount += 1
                    if (entryCount > AgentSkillLimits.MAX_ENTRIES) {
                        skillFailure(AgentSkillErrorCode.ENTRY_COUNT_EXCEEDED, "too many Skill entries")
                    }
                    if (!visited.add(document.documentId)) {
                        skillFailure(AgentSkillErrorCode.LOOP_DOCUMENT_ID, "provider document loop detected", path)
                    }
                    val normalized = AgentSkillPathPolicy.normalizeDirectory(path, path)
                    if (!directories.add(normalized) || normalized in paths) {
                        skillFailure(AgentSkillErrorCode.DUPLICATE_PATH, "duplicate normalized path: '$normalized'", path)
                    }
                    for (child in document.children()) visit(child, normalized, depth + 1)
                }
                AgentSkillDocumentType.FILE -> {
                    entryCount += 1
                    if (entryCount > AgentSkillLimits.MAX_ENTRIES) {
                        skillFailure(AgentSkillErrorCode.ENTRY_COUNT_EXCEEDED, "too many Skill entries")
                    }
                    if (!visited.add(document.documentId)) {
                        skillFailure(AgentSkillErrorCode.LOOP_DOCUMENT_ID, "provider document loop detected", path)
                    }
                    val normalized = AgentSkillPathPolicy.normalizeFile(path, path)
                    if (!paths.add(normalized) || normalized in directories) {
                        skillFailure(AgentSkillErrorCode.DUPLICATE_PATH, "duplicate normalized path: '$normalized'", path)
                    }
                    if (document.sizeBytes < 0L) {
                        skillFailure(AgentSkillErrorCode.INVALID_PATH, "document reports a negative size", path)
                    }
                    if (document.sizeBytes > AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES) {
                        skillFailure(
                            AgentSkillErrorCode.ENTRY_SIZE_EXCEEDED,
                            "entry exceeds ${AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES} bytes",
                            path
                        )
                    }
                    expandedBytes += document.sizeBytes
                    if (expandedBytes > AgentSkillLimits.MAX_EXPANDED_BYTES) {
                        skillFailure(
                            AgentSkillErrorCode.EXPANDED_SIZE_EXCEEDED,
                            "expanded package exceeds ${AgentSkillLimits.MAX_EXPANDED_BYTES} bytes",
                            path
                        )
                    }
                    when {
                        normalized == AgentSkillManifest.ENTRY_PATH -> files += AgentSkillSourceFile(
                            normalized,
                            document.sizeBytes,
                            { document.openStream() }
                        )
                        AgentSkillScriptPolicy.isScript(normalized) -> scripts += normalized
                        AgentSkillScriptPolicy.isInstalledResource(normalized) -> files += AgentSkillSourceFile(
                            normalized,
                            document.sizeBytes,
                            { document.openStream() }
                        )
                        else -> skillFailure(
                            AgentSkillErrorCode.UNSUPPORTED_RESOURCE,
                            "unsupported resource type (only Markdown/text/supported attachments are installed)",
                            normalized
                        )
                    }
                }
            }
        }

        for (child in root.children()) visit(child, "", 1)
        if (files.none { it.relativePath == AgentSkillManifest.ENTRY_PATH }) {
            skillFailure(
                AgentSkillErrorCode.MISSING_SKILL_MD,
                "the selected directory must contain ${AgentSkillManifest.ENTRY_PATH} at its root"
            )
        }
        val skillFile = files.first { it.relativePath == AgentSkillManifest.ENTRY_PATH }
        val skillMarkdown = skillFile.open().use { readBounded(it, AgentSkillLimits.MAX_SKILL_MD_BYTES) }
        val parsed = parser.parse(skillMarkdown)
        return AgentSkillPackageContent(parsed, files.sortedBy { it.relativePath }, scripts.sorted())
    }

    private fun zipSourceFile(
        archive: AgentSkillZipArchive,
        entry: AgentSkillZipEntry
    ): AgentSkillSourceFile = AgentSkillSourceFile(entry.path, entry.uncompressedSize) {
        RatioBoundedZipStream(
            archive.openStream(entry),
            compressedSize = entry.compressedSize,
            declaredSize = entry.uncompressedSize,
            path = entry.path
        )
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                skillFailure(
                    AgentSkillErrorCode.SKILL_MD_TOO_LARGE,
                    "SKILL.md exceeds $limit bytes"
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun deriveId(
        name: String,
        files: List<AgentSkillSourceFile>,
        scripts: List<String>
    ): String {
        val seed = buildString {
            append(name).append('\u0000')
            files.sortedBy { it.relativePath }.forEach {
                append(it.relativePath).append(':').append(it.sizeBytes).append('\n')
            }
            scripts.sorted().forEach { append("script:").append(it).append('\n') }
        }
        val suffix = AgentSkillHash.sha256Hex(seed).take(8)
        val slug = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(40)
            .ifBlank { "skill" }
        return "$slug-$suffix"
    }
}

/** Enforces the 100:1 and declared-size limits while the repository streams an installed entry. */
private class RatioBoundedZipStream(
    input: InputStream,
    private val compressedSize: Long,
    private val declaredSize: Long,
    private val path: String
) : java.io.FilterInputStream(input) {
    private var outputBytes = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) account(read)
        return read
    }

    private fun account(count: Int) {
        outputBytes += count
        if (declaredSize > 0L && outputBytes > declaredSize) {
            skillFailure(
                AgentSkillErrorCode.MALFORMED_ZIP,
                "entry expands beyond its declared size",
                path
            )
        }
        if (outputBytes > 0L) {
            if (compressedSize <= 0L) {
                skillFailure(
                    AgentSkillErrorCode.COMPRESSION_RATIO_EXCEEDED,
                    "entry expands from an empty compressed stream",
                    path
                )
            }
            if (outputBytes / compressedSize > AgentSkillLimits.MAX_COMPRESSION_RATIO) {
                skillFailure(
                    AgentSkillErrorCode.COMPRESSION_RATIO_EXCEEDED,
                    "entry exceeds the ${AgentSkillLimits.MAX_COMPRESSION_RATIO}:1 compression ratio",
                    path
                )
            }
        }
    }
}

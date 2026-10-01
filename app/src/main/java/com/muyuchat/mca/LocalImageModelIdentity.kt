package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** An observed component, including companions that can change native execution. */
data class LocalImageComponentSnapshot(
    val role: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val sha256: String
) {
    fun toJson(): JSONObject = JSONObject().put("role", role).put("path", relativePath)
        .put("sizeBytes", sizeBytes).put("modifiedAt", modifiedAt).put("sha256", sha256)

    companion object {
        fun fromJson(json: JSONObject) = LocalImageComponentSnapshot(
            role = json.optString("role"), relativePath = json.optString("path"),
            sizeBytes = json.optLong("sizeBytes"), modifiedAt = json.optLong("modifiedAt"),
            sha256 = json.optString("sha256")
        )
    }
}

/** Retained physical copies are never erased as a side effect of catalog de-duplication. */
data class LocalImageModelPhysicalCopy(
    val path: String,
    val bundleRoot: String?,
    val source: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val contentFingerprint: String,
    val components: List<LocalImageComponentSnapshot>
) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("bundleRoot", bundleRoot)
        .put("source", source).put("fileName", fileName).put("sizeBytes", sizeBytes)
        .put("sha256", sha256).put("contentFingerprint", contentFingerprint)
        .put("components", JSONArray().apply { components.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject) = LocalImageModelPhysicalCopy(
            path = json.optString("path"),
            bundleRoot = json.optString("bundleRoot").takeIf { it.isNotBlank() && it != "null" },
            source = json.optString("source", "local"), fileName = json.optString("fileName"),
            sizeBytes = json.optLong("sizeBytes"), sha256 = json.optString("sha256"),
            contentFingerprint = json.optString("contentFingerprint"),
            components = json.optJSONArray("components").imageComponentSnapshots()
        )
    }
}

internal fun JSONArray?.imageComponentSnapshots(): List<LocalImageComponentSnapshot> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.let(LocalImageComponentSnapshot::fromJson)
    }

internal fun localImagePhysicalIdentity(record: LocalImageModelRecord): String =
    localImagePhysicalIdentity(record.runtime, record.path)

internal fun localImagePhysicalIdentity(runtime: LocalImageRuntime, path: String): String =
    "${runtime.name}:${canonicalLocalImageIdentityPath(path)}"

internal fun LocalImageModelRecord.asPhysicalCopy() = LocalImageModelPhysicalCopy(
    path, bundleRoot, source, fileName, sizeBytes, sha256, contentFingerprint, componentSnapshots
)

internal fun LocalImageModelRecord.expandPhysicalRecords(): List<LocalImageModelRecord> =
    listOf(copy(physicalCopies = emptyList())) + physicalCopies.map { physical ->
        copy(
            id = UUID.nameUUIDFromBytes(
                "mca.image.copy:$id:${localImagePhysicalIdentity(runtime, physical.path)}".toByteArray(Charsets.UTF_8)
            ).toString(),
            path = physical.path, bundleRoot = physical.bundleRoot, source = physical.source, fileName = physical.fileName,
            sizeBytes = physical.sizeBytes, sha256 = physical.sha256, contentFingerprint = physical.contentFingerprint,
            componentSnapshots = physical.components, componentCount = physical.components.size.coerceAtLeast(1),
            aliases = emptyList(), physicalCopies = emptyList(), verificationStatus = LocalImageVerificationStatus.UNKNOWN,
            verificationMessage = "", verifiedAt = 0L, verifiedContentFingerprint = "", qnnVerificationStamp = ""
        )
    }

internal fun LocalImageModelRecord.allPhysicalCopies(): List<LocalImageModelPhysicalCopy> =
    (listOf(asPhysicalCopy()) + physicalCopies).distinctBy {
        localImagePhysicalIdentity(runtime, it.path)
    }

internal fun LocalImageModelRecord.catalogPhysicalKeys(): Set<String> =
    allPhysicalCopies().mapTo(linkedSetOf()) { localImagePhysicalIdentity(runtime, it.path) }

private fun LocalImageModelRecord.wasDiscovered(): Boolean =
    source == "local:discovered" || source.startsWith("app-private:")

private fun canonicalLocalImageIdentityPath(path: String): String =
    runCatching { File(path).canonicalPath }.getOrDefault(path)

private val IMAGE_COMPONENT_SHA256 = Regex("^[0-9a-fA-F]{64}$")

/** A primary-only hash is deliberately insufficient for a cross-directory identity. */
internal fun LocalImageModelRecord.hasCompleteContentIdentity(): Boolean =
    IMAGE_COMPONENT_SHA256.matches(contentFingerprint) && componentSnapshots.isNotEmpty() &&
        componentSnapshots.all { it.sizeBytes >= 0L && IMAGE_COMPONENT_SHA256.matches(it.sha256) && it.role.isNotBlank() }

internal fun localImageSameCompleteContents(first: LocalImageModelRecord, second: LocalImageModelRecord): Boolean =
    first.runtime == second.runtime && first.hasCompleteContentIdentity() && second.hasCompleteContentIdentity() &&
        first.contentFingerprint.equals(second.contentFingerprint, ignoreCase = true)

private fun compatibleImageVerification(first: LocalImageModelRecord, latest: LocalImageModelRecord): Boolean {
    if (localImagePhysicalIdentity(first) != localImagePhysicalIdentity(latest)) return false
    if (first.hasCompleteContentIdentity() || latest.hasCompleteContentIdentity()) {
        return localImageSameCompleteContents(first, latest) &&
            first.verifiedContentFingerprint == latest.contentFingerprint
    }
    // Legacy helper records retain their old path-local proof until the production store
    // captures a complete snapshot. No legacy hash can associate two different copies.
    return first.sha256.equals(latest.sha256, ignoreCase = true) &&
        canonicalLocalImageIdentityPath(first.bundleRoot ?: first.path) ==
            canonicalLocalImageIdentityPath(latest.bundleRoot ?: latest.path)
}

/** First reconcile a physical replacement, then associate only complete equal bundles. */
internal fun normalizeLocalImageModelRecords(
    records: List<LocalImageModelRecord>, preferredId: String? = null
): List<LocalImageModelRecord> {
    val expanded = records.flatMap { it.expandPhysicalRecords() }
    val physical = expanded.withIndex().groupBy { (index, record) ->
        if (record.path.isBlank()) "missing:$index" else localImagePhysicalIdentity(record)
    }.values.map { indexed ->
        val candidates = indexed.map { it.value }
        val stable = stableImageCatalogOwner(candidates, preferredId)
        val metadataOwner = stableImageCatalogOwner(candidates, null)
        val latest = candidates.maxWithOrNull(
            compareBy<LocalImageModelRecord> { it.configured }.thenBy { it.updatedAt }
                .thenBy { it.hasCompleteContentIdentity() }
        )!!
        val verification = candidates.filter { compatibleImageVerification(it, latest) }
            .maxWithOrNull(compareBy<LocalImageModelRecord> { it.verifiedAt }.thenBy { it.updatedAt })
        latest.copy(
            id = stable.id, displayName = metadataOwner.displayName, source = metadataOwner.source,
            recommendationId = latest.recommendationId ?: stable.recommendationId,
            createdAt = candidates.minOf { it.createdAt },
            aliases = candidates.flatMap { listOf(it.id) + it.aliases }.distinct().filter { it != stable.id },
            verificationStatus = verification?.verificationStatus ?: LocalImageVerificationStatus.UNKNOWN,
            verificationMessage = verification?.verificationMessage
                ?: latest.verificationMessage.takeIf { latest.verificationStatus == LocalImageVerificationStatus.UNKNOWN && latest.verifiedAt == 0L }.orEmpty(),
            verifiedAt = verification?.verifiedAt ?: 0L,
            verifiedContentFingerprint = verification?.verifiedContentFingerprint.orEmpty(),
            qnnVerificationStamp = verification?.qnnVerificationStamp.orEmpty()
        )
    }
    val merged = physical.withIndex().groupBy { (index, record) ->
        if (record.hasCompleteContentIdentity()) "${record.runtime.name}:${record.contentFingerprint.lowercase(Locale.ROOT)}"
        else "physical:$index"
    }.values.map { indexed ->
        val candidates = indexed.map { it.value }
        val stable = stableImageCatalogOwner(candidates, preferredId)
        // Preserve the selected/imported primary while it is present; a surviving exact
        // copy may take over only after that primary becomes missing/unreadable.
        val active = stable.takeIf { it.configured } ?: candidates.firstOrNull { it.configured } ?: stable
        val metadataOwner = stableImageCatalogOwner(candidates, null)
        active.copy(
            id = stable.id, displayName = metadataOwner.displayName, source = metadataOwner.source,
            recommendationId = stable.recommendationId ?: candidates.firstNotNullOfOrNull { it.recommendationId },
            createdAt = candidates.minOf { it.createdAt }, updatedAt = candidates.maxOf { it.updatedAt },
            aliases = candidates.flatMap { listOf(it.id) + it.aliases }.distinct().filter { it != stable.id },
            physicalCopies = candidates.filter { localImagePhysicalIdentity(it) != localImagePhysicalIdentity(active) }
                .map { it.asPhysicalCopy() }.distinctBy { localImagePhysicalIdentity(active.runtime, it.path) }
        )
    }
    // A legacy id reused by different artifacts remains diagnosable without ambiguity.
    val ownerById = merged.groupBy { it.id }.mapValues { (_, candidates) ->
        candidates.maxWithOrNull(compareBy<LocalImageModelRecord> { it.configured }.thenBy { it.updatedAt })
    }
    val reservedIds = records.mapTo(mutableSetOf()) { it.id }
    val assignedIds = mutableSetOf<String>()
    val unique = merged.map { record ->
        if (ownerById[record.id] === record) {
            assignedIds += record.id
            record
        } else {
            var suffix = 0
            var replacement: String
            do {
                replacement = UUID.nameUUIDFromBytes(
                    "mca.image.catalog.v3:${record.id}:${localImagePhysicalIdentity(record)}:${suffix++}"
                        .toByteArray(Charsets.UTF_8)
                ).toString()
            } while (replacement in reservedIds || replacement in assignedIds)
            assignedIds += replacement
            record.copy(id = replacement)
        }
    }
    val currentIds = unique.mapTo(mutableSetOf()) { it.id }
    return unique.map { record -> record.copy(
        aliases = record.aliases.filter { it.isNotBlank() && it !in currentIds }.distinct()
    ) }
}

private fun stableImageCatalogOwner(candidates: List<LocalImageModelRecord>, preferredId: String?): LocalImageModelRecord =
    candidates.maxWithOrNull(
        compareBy<LocalImageModelRecord> { it.matchesCatalogId(preferredId) }
            .thenBy { !it.wasDiscovered() }.thenByDescending { it.createdAt }
    )!!

/** Deleting a row must preserve every physical copy owned by another runtime/record. */
internal fun localImageRemovalSharesFiles(target: LocalImageModelRecord, remaining: List<LocalImageModelRecord>): Boolean =
    target.allPhysicalCopies().any { copy ->
        val file = copy.bundleRoot?.takeIf(String::isNotBlank)?.let(::File) ?: File(copy.path)
        localImageFileSharesCatalogOwnership(file, remaining)
    }

internal fun localImageFileSharesCatalogOwnership(candidate: File, records: List<LocalImageModelRecord>): Boolean {
    val candidatePath = runCatching { candidate.canonicalPath }.getOrElse { return true }
    val isDirectory = candidate.isDirectory
    return records.any { record -> record.allPhysicalCopies().any copyLoop@ { copy ->
        val primaryPath = runCatching { File(copy.path).canonicalPath }.getOrElse { return@copyLoop true }
        val bundlePath = copy.bundleRoot?.takeIf(String::isNotBlank)?.let { root ->
            runCatching { File(root).canonicalPath }.getOrElse { return@any true }
        }
        primaryPath == candidatePath || (isDirectory && primaryPath.startsWith(candidatePath + File.separator)) ||
            (bundlePath != null && (candidatePath == bundlePath || candidatePath.startsWith(bundlePath + File.separator) ||
                (isDirectory && bundlePath.startsWith(candidatePath + File.separator))))
    } }
}

private data class ImageFileDigestCacheEntry(val sizeBytes: Long, val modifiedAt: Long, val changeKey: String, val sha256: String)
private val imageFileDigestCache = object : LinkedHashMap<String, ImageFileDigestCacheEntry>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageFileDigestCacheEntry>?): Boolean = size > 2_048
}

/** Cache only an observation made in this process, never a persisted/source hash. */
internal fun observedLocalImageFileSha256(file: File, checkCancelled: () -> Unit = {}, force: Boolean = false): String {
    checkCancelled()
    require(file.isFile && file.canRead()) { "模型组件不存在或不可读：${file.name}" }
    val path = file.canonicalPath
    val bytes = file.length()
    val modified = file.lastModified()
    val changeKey = runCatching {
        Files.readAttributes(file.toPath(), "unix:ctime,ino").toString()
    }.getOrDefault("")
    val cached = synchronized(imageFileDigestCache) { imageFileDigestCache[path] }
    // Always re-hash small configurations/tokenizers; large immutable weights use a
    // process-local stat/ctime key, so each page refresh does not re-read gigabytes.
    if (!force && changeKey.isNotEmpty() && bytes > 4L * 1_024L * 1_024L && cached != null &&
        cached.sizeBytes == bytes && cached.modifiedAt == modified && cached.changeKey == changeKey) return cached.sha256
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1_024)
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    checkCancelled()
    val finalChangeKey = runCatching { Files.readAttributes(file.toPath(), "unix:ctime,ino").toString() }.getOrDefault("")
    require(file.length() == bytes && file.lastModified() == modified && finalChangeKey == changeKey) {
        "模型组件在校验期间发生变化，请重试：${file.name}"
    }
    val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    synchronized(imageFileDigestCache) { imageFileDigestCache[path] = ImageFileDigestCacheEntry(bytes, modified, changeKey, sha) }
    return sha
}

internal data class LocalImageContentSnapshot(val fingerprint: String, val components: List<LocalImageComponentSnapshot>)

/** Complete observed inventory: role + normalized relative path + actual SHA, independent of source/title. */
internal fun captureLocalImageContentSnapshot(
    record: LocalImageModelRecord,
    checkCancelled: () -> Unit = {},
    force: Boolean = false,
    manifestOverride: JSONObject? = null
): LocalImageContentSnapshot {
    val primary = File(record.path).canonicalFile
    require(primary.isFile && primary.length() > 0L) { "模型主文件不存在或为空：${primary.name}" }
    val root = record.bundleRoot?.takeIf(String::isNotBlank)?.let { File(it).canonicalFile }
    val components = if (root == null) {
        listOf(LocalImageComponentSnapshot("MODEL", "", primary.length(), primary.lastModified(),
            observedLocalImageFileSha256(primary, checkCancelled, force)))
    } else {
        require(root.isDirectory && primary.toPath().startsWith(root.toPath())) { "模型主文件位于组件包目录之外。" }
        val directManifest = File(root, "manifest.json").takeIf { it.isFile }
        val manifest = manifestOverride ?: directManifest?.let {
            runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull()
        }
        val roles = linkedMapOf<String, String>()
        manifest?.optJSONArray("components")?.let { declared ->
            for (index in 0 until declared.length()) {
                val component = declared.optJSONObject(index) ?: continue
                val raw = component.optString("path").ifBlank { component.optString("fileName") }
                val path = normalizedImageComponentPath(root, raw) ?: continue
                roles[path] = component.optString("role", "AUXILIARY").trim().uppercase(Locale.ROOT)
            }
        }
        val primaryRelative = primary.relativeTo(root).invariantSeparatorsPath
        roles.putIfAbsent(primaryRelative, "MODEL")
        val files = root.walkTopDown().onEnter { directory ->
            checkCancelled()
            val canonical = directory.canonicalFile
            val inRoot = canonical.toPath().startsWith(root.toPath()) && !Files.isSymbolicLink(directory.toPath())
            val nestedIndependent = directory != root && File(directory, "manifest.json").isMcaImageBundleManifest()
            val explicitlyOwned = roles.keys.any { it.startsWith(directory.relativeTo(root).invariantSeparatorsPath + "/") }
            inRoot && (directory == root || !directory.name.startsWith(".")) && (!nestedIndependent || explicitlyOwned)
        }.filter { file ->
            checkCancelled()
            file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(root.toPath()) &&
                (!file.name.startsWith(".") || file.relativeTo(root).invariantSeparatorsPath in roles) &&
                !file.name.endsWith(".part") && !file.name.endsWith(".tmp") && file != directManifest
        }.map { file ->
            val relative = file.canonicalFile.relativeTo(root).invariantSeparatorsPath
            LocalImageComponentSnapshot(roles[relative] ?: "AUXILIARY", relative, file.length(), file.lastModified(),
                observedLocalImageFileSha256(file, checkCancelled, force))
        }.toList()
        // Missing declared files also participate, so removing a companion invalidates proof.
        val missing = roles.filterKeys { path -> files.none { it.relativePath == path } }.map { (path, role) ->
            LocalImageComponentSnapshot(role, path, -1L, 0L, imageIdentityDigest("missing:$role:$path"))
        }
        val contract = manifest?.let { json ->
            val execution = JSONObject()
            listOf("schema", "runtime", "family", "task", "primary", "primaryFile", "imageSize", "size",
                "requiredRuntimeProfile", "requiresQnnRuntime", "width", "height").forEach { key ->
                if (json.has(key)) execution.put(key, json.get(key))
            }
            // The observed bundle fingerprint is derived from this contract.  A
            // persisted executionProfile.modelFingerprint is therefore a value
            // produced by the fingerprint itself and must not be included in the
            // input, otherwise writing the profile would make the fingerprint
            // oscillate on every rescan.  Keep the rest of the profile in the
            // contract so graph/tokenizer/runtime changes still invalidate it.
            json.optJSONObject("executionProfile")?.let { profile ->
                execution.put("executionProfile", JSONObject(profile.toString()).apply {
                    remove("modelFingerprint")
                })
            }
            val declared = json.optJSONArray("components")
            val required = JSONArray()
            roles.toSortedMap().forEach { (path, role) ->
                val source = declared?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject)
                    .firstOrNull { normalizedImageComponentPath(root, it.optString("path").ifBlank { it.optString("fileName") }) == path } }
                required.put(JSONObject().put("path", path).put("role", role).put("required", source?.optBoolean("required", true) ?: true))
            }
            execution.put("components", required)
            LocalImageComponentSnapshot(
                "MANIFEST_CONTRACT", "manifest.json",
                manifestOverride?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong()
                    ?: directManifest?.length() ?: 0L,
                directManifest?.lastModified() ?: 0L,
                imageIdentityDigest(canonicalImageJson(execution))
            )
        }
        files + missing + listOfNotNull(contract)
    }.sortedWith(compareBy<LocalImageComponentSnapshot> { it.relativePath }.thenBy { it.role })
    val material = JSONArray().apply { components.forEach { component ->
        put(JSONArray().put(component.role).put(component.relativePath).put(component.sha256.lowercase(Locale.ROOT)))
    } }
    return LocalImageContentSnapshot(imageIdentityDigest("mca.image.components.v1:${record.runtime.name}:$material"), components)
}

private fun normalizedImageComponentPath(root: File, raw: String): String? {
    val normalized = raw.replace('\\', '/').trim()
    if (normalized.isBlank() || normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
    val file = runCatching { File(root, normalized).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it != root && it.toPath().startsWith(root.toPath()) }?.relativeTo(root)?.invariantSeparatorsPath
}

private fun canonicalImageJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { key ->
        "${JSONObject.quote(key)}:${canonicalImageJson(value.get(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalImageJson(value.get(it)) }
    null, JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    is Number -> JSONObject.numberToString(value)
    is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
}

private fun imageIdentityDigest(material: String): String = MessageDigest.getInstance("SHA-256")
    .digest(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun LocalImageModelRecord.withObservedContent(
    checkCancelled: () -> Unit = {}, force: Boolean = false
): LocalImageModelRecord {
    val observed = captureLocalImageContentSnapshot(this, checkCancelled, force)
    val same = contentFingerprint.isNotBlank() && contentFingerprint == observed.fingerprint
    val primaryHash = observed.components.firstOrNull { component ->
        val root = bundleRoot?.takeIf(String::isNotBlank)?.let(::File)
        if (root == null) component.role == "MODEL" && component.relativePath.isEmpty()
        else runCatching { File(root, component.relativePath).canonicalPath == File(path).canonicalPath }.getOrDefault(false)
    }?.sha256 ?: sha256
    return copy(
        sha256 = primaryHash, contentFingerprint = observed.fingerprint, componentSnapshots = observed.components,
        sizeBytes = observed.components.filter { it.sizeBytes >= 0L }.sumOf { it.sizeBytes },
        componentCount = observed.components.size.coerceAtLeast(1),
        verificationStatus = if (same && verifiedContentFingerprint == observed.fingerprint) verificationStatus else LocalImageVerificationStatus.UNKNOWN,
        verificationMessage = if (same && (verifiedContentFingerprint == observed.fingerprint ||
            (verificationStatus == LocalImageVerificationStatus.UNKNOWN && verifiedAt == 0L))) verificationMessage else "",
        verifiedAt = if (same && verifiedContentFingerprint == observed.fingerprint) verifiedAt else 0L,
        qnnVerificationStamp = if (same && verifiedContentFingerprint == observed.fingerprint) qnnVerificationStamp else "",
        verifiedContentFingerprint = if (same && verifiedContentFingerprint == observed.fingerprint) verifiedContentFingerprint else "",
        updatedAt = if (same) updatedAt else System.currentTimeMillis()
    )
}

/** Parent component declarations suppress auxiliary models; a direct independent manifest wins. */
internal fun imageDirectoryIsOwnedComponent(directory: File?, primary: File): Boolean {
    val child = directory?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return false
    if (File(child, "manifest.json").isMcaImageBundleManifest()) return false
    val primaryPath = runCatching { primary.canonicalPath }.getOrNull() ?: return false
    var ancestor = child.parentFile
    while (ancestor != null) {
        val root = ancestor.canonicalFile
        val manifest = File(root, "manifest.json").takeIf { it.isFile }?.let {
            runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull()
        }
        val components = manifest?.optJSONArray("components")
        if (components != null && (0 until components.length()).any { index ->
            val declared = components.optJSONObject(index) ?: return@any false
            val relative = normalizedImageComponentPath(root,
                declared.optString("path").ifBlank { declared.optString("fileName") }) ?: return@any false
            val path = File(root, relative).canonicalPath
            path == primaryPath || path.startsWith(child.path + File.separator)
        }) return true
        ancestor = root.parentFile
    }
    return false
}

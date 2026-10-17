package com.muyuchat.mca

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import com.muyuchat.mca.TextualInversionStore.Companion.IMPORT_PART_NAME_PATTERN
import com.muyuchat.mca.TextualInversionStore.Companion.MANIFEST_NAME
import com.muyuchat.mca.TextualInversionStore.Companion.MANIFEST_TEMP_NAME_PATTERN
import com.muyuchat.mca.TextualInversionStore.Companion.MANIFEST_VERSION
import com.muyuchat.mca.TextualInversionStore.Companion.MAX_MANIFEST_BYTES
import com.muyuchat.mca.TextualInversionStore.Companion.MAX_RECORD_COUNT
import com.muyuchat.mca.TextualInversionStore.Companion.STALE_TEMPORARY_AGE_MS
import com.muyuchat.mca.TextualInversionStore.Companion.UUID_REGEX
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun TextualInversionStore.loadImpl(): List<TextualInversionArtifact> = withStoreLock(shared = false) { checkCancelled ->
        loadLocked(checkCancelled).also { records ->
            pruneOrphansLocked(records, checkCancelled)
        }
    }
internal suspend fun TextualInversionStore.clearImpl(id: String): Boolean = withCommittedMutation { checkCancelled, markCommitted ->
        val current = loadLocked(checkCancelled)
        val item = current.firstOrNull { it.id == id.trim().lowercase() }
            ?: return@withCommittedMutation false
        val next = current.filterNot { it.id == item.id }
        checkCancelled()
        commitManifest(
            records = next,
            checkCancelled = checkCancelled,
            onCommitted = { markCommitted(true) }
        )
        directChild(item.fileName).delete()
        true
    }
internal suspend fun TextualInversionStore.pruneOrphansImpl(): Int = withStoreLock(shared = false) { checkCancelled ->
        pruneOrphansLocked(loadLocked(checkCancelled), checkCancelled)
    }
private fun TextualInversionStore.pruneOrphansLocked(
        records: List<TextualInversionArtifact>,
        checkCancelled: () -> Unit
    ): Int {
        val referenced = records.map { it.fileName }.toSet()
        val staleCutoff = (System.currentTimeMillis() - STALE_TEMPORARY_AGE_MS).coerceAtLeast(0L)
        var deleted = 0
        root.listFiles()?.forEach { file ->
            checkCancelled()
            if (file == manifest || file == manifestBackup || file == lockFile) {
                return@forEach
            }
            if (file.name.startsWith(".")) {
                if (file.isStaleOwnedTemporaryFile(root, staleCutoff) && file.delete()) deleted++
                return@forEach
            }
            if (file.name !in referenced && file.isFile && !Files.isSymbolicLink(file.toPath())) {
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

private fun File.isStaleOwnedTemporaryFile(root: File, staleCutoff: Long): Boolean = try {
        val recognizedName = IMPORT_PART_NAME_PATTERN.matches(name) ||
            MANIFEST_TEMP_NAME_PATTERN.matches(name)
        val modifiedAt = lastModified()
        recognizedName &&
            isFile &&
            !Files.isSymbolicLink(toPath()) &&
            canonicalFile.parentFile == root &&
            canonicalFile.name == name &&
            modifiedAt > 0L &&
            modifiedAt <= staleCutoff
    } catch (_: Exception) {
        false
    }

internal fun TextualInversionStore.loadLocked(checkCancelled: () -> Unit): List<TextualInversionArtifact> {
        checkCancelled()
        if (!manifest.isFile) {
            if (manifestBackup.isFile) {
                throw TextualInversionStoreException(
                    "Textual inversion manifest recovery requires an exclusive store operation."
                )
            }
            return emptyList()
        }
        if (manifest.length() !in 1L..MAX_MANIFEST_BYTES) {
            throw TextualInversionStoreException("Textual inversion manifest is outside its bound.")
        }
        val parsed: List<TextualInversionArtifact> = try {
            val raw = manifest.readText(Charsets.UTF_8)
            val trimmed = raw.trimStart()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(raw)
            } else {
                val envelope = JSONObject(raw)
                val version = envelope.getInt("version")
                if (version != MANIFEST_VERSION) {
                    throw TextualInversionStoreException(
                        "Unsupported textual inversion manifest version: $version."
                    )
                }
                envelope.getJSONArray("records")
            }
            if (array.length() > MAX_RECORD_COUNT) {
                throw TextualInversionStoreException("Textual inversion manifest has too many records.")
            }
            buildList<TextualInversionArtifact> {
                for (index in 0 until array.length()) {
                    checkCancelled()
                    val artifact = TextualInversionArtifact.fromJson(array.getJSONObject(index))
                    requireRecordFileIsCurrent(artifact, checkCancelled)
                    add(artifact)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TextualInversionStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw TextualInversionStoreException(
                "Textual inversion manifest is corrupt.",
                failure
            )
        }
        if (parsed.map(TextualInversionArtifact::id).distinct().size != parsed.size ||
            parsed.map { it.trigger.lowercase() }.distinct().size != parsed.size) {
            throw TextualInversionStoreException(
                "Textual inversion manifest contains duplicate ids or triggers."
            )
        }
        requireAggregateQuota(parsed)
        verifiedFileCache.keys.retainAll(parsed.map(TextualInversionArtifact::id).toSet())
        return parsed.sortedByDescending(TextualInversionArtifact::importedAt)
    }

internal fun TextualInversionStore.commitManifest(
        records: List<TextualInversionArtifact>,
        checkCancelled: () -> Unit,
        onCommitted: () -> Unit = {}
    ) {
        ensureRoot()
        require(records.size <= MAX_RECORD_COUNT) { "Too many textual inversion records." }
        require(records.map(TextualInversionArtifact::id).distinct().size == records.size) {
            "Textual inversion record ids must be unique."
        }
        require(records.map { it.trigger.lowercase() }.distinct().size == records.size) {
            "Textual inversion triggers must be unique."
        }
        requireAggregateQuota(records)
        val temp = directChild(".$MANIFEST_NAME.${UUID.randomUUID()}.tmp")
        try {
            val array = JSONArray()
            records.forEach {
                checkCancelled()
                array.put(it.toJson())
            }
            val payload = JSONObject()
                .put("version", MANIFEST_VERSION)
                .put("records", array)
                .toString()
                .toByteArray(Charsets.UTF_8)
            require(payload.size.toLong() <= MAX_MANIFEST_BYTES) {
                "Textual inversion manifest exceeds its size bound."
            }
            FileOutputStream(temp).use { raw ->
                BufferedOutputStream(raw).use { output ->
                    output.write(payload)
                    output.flush()
                    raw.fd.sync()
                }
            }
            checkCancelled()
            try {
                Files.move(
                    temp.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                fallbackManifestCommit(temp)
            }
            onCommitted()
            afterManifestCommit()
            manifestBackup.delete()
        } finally {
            temp.delete()
        }
    }

private fun TextualInversionStore.fallbackManifestCommit(temp: File) {
        manifestBackup.delete()
        if (manifest.exists()) {
            Files.move(
                manifest.toPath(),
                manifestBackup.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        try {
            Files.move(temp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            if (!manifest.exists() && manifestBackup.exists()) {
                Files.move(
                    manifestBackup.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            throw failure
        }
    }

internal fun TextualInversionStore.recoverInterruptedManifestCommit(checkCancelled: () -> Unit) {
        checkCancelled()
        if (!manifestBackup.exists()) return
        if (manifest.exists()) {
            manifestBackup.delete()
            return
        }
        Files.move(
            manifestBackup.toPath(),
            manifest.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

internal fun TextualInversionStore.moveIntoPlace(source: File, target: File): Boolean {
        if (target.exists()) return false
        return try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            source.renameTo(target)
        }
    }

internal fun TextualInversionStore.directChild(name: String): File = File(root, name).canonicalFile.also { file ->
        require(file.parentFile == root) { "Textual inversion path escaped its private root." }
    }

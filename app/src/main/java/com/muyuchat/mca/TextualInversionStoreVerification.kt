package com.muyuchat.mca

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import com.muyuchat.mca.TextualInversionStore.Companion.COPY_BUFFER_BYTES
import kotlinx.coroutines.CancellationException


internal fun TextualInversionStore.requireRecordFileIsCurrent(
        artifact: TextualInversionArtifact,
        checkCancelled: () -> Unit,
        forceHash: Boolean = false
    ): ArtifactFileVerification {
        checkCancelled()
        return try {
            val before = describeRecordFile(artifact)
            val cached = verifiedFileCache[artifact.id]
            if (!forceHash && cached != null && cached.sameIdentity(before) &&
                cached.sha256 == artifact.sha256
            ) {
                return cached
            }
            val actualSha256 = sha256(File(before.canonicalPath), checkCancelled)
            val after = describeRecordFile(artifact).copy(sha256 = actualSha256)
            if (!before.sameIdentity(after) || actualSha256 != artifact.sha256) {
                throw TextualInversionStoreException(
                    "Textual inversion artifact ${artifact.id} differs from its manifest record."
                )
            }
            after.also { verifiedFileCache[artifact.id] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TextualInversionStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw TextualInversionStoreException(
                "Unable to verify textual inversion artifact ${artifact.id}.",
                failure
            )
        }
    }

private fun TextualInversionStore.describeRecordFile(artifact: TextualInversionArtifact): ArtifactFileVerification {
        val declared = File(artifact.path).absoluteFile
        if (Files.isSymbolicLink(declared.toPath())) {
            throw TextualInversionStoreException(
                "Textual inversion artifact ${artifact.id} differs from its manifest record."
            )
        }
        val file = declared.canonicalFile
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (file.parentFile != root || file.name != artifact.fileName ||
            !attributes.isRegularFile || attributes.isSymbolicLink ||
            attributes.size() != artifact.sizeBytes
        ) {
            throw TextualInversionStoreException(
                "Textual inversion artifact ${artifact.id} differs from its manifest record."
            )
        }
        return ArtifactFileVerification(
            canonicalPath = file.path,
            fileKey = attributes.fileKey()?.toString(),
            sizeBytes = attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
            sha256 = artifact.sha256
        )
    }

internal fun TextualInversionStore.verifyRecordDescriptor(
        artifact: TextualInversionArtifact,
        handle: RandomAccessFile,
        checkCancelled: () -> Unit
    ): ArtifactFileVerification {
        val before = describeRecordFile(artifact)
        require(handle.length() == artifact.sizeBytes) {
            "Textual inversion artifact ${artifact.id} descriptor size changed."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        handle.seek(0L)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var readBytes = 0L
        while (true) {
            checkCancelled()
            val read = handle.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            readBytes += read.toLong()
        }
        val actualSha256 = digest.digest().textualInversionToHex()
        val after = describeRecordFile(artifact).copy(sha256 = actualSha256)
        require(readBytes == artifact.sizeBytes && handle.length() == artifact.sizeBytes &&
            before.sameIdentity(after) && actualSha256 == artifact.sha256
        ) { "Textual inversion artifact ${artifact.id} changed during descriptor verification." }
        verifiedFileCache[artifact.id] = after
        return after
    }

internal fun TextualInversionStore.verifyExecutionAssetPath(
        expected: TextualInversionExecutionAssetDescriptor,
        checkCancelled: () -> Unit
    ): TextualInversionExecutionAssetDescriptor {
        checkCancelled()
        return describeExecutionAsset(expected).also { actual ->
            require(actual.sameIdentity(expected)) {
                "Prompt execution asset ${expected.label} changed after its exact snapshot."
            }
        }
    }

internal fun TextualInversionStore.verifyExecutionAssetDescriptor(
        expected: TextualInversionExecutionAssetDescriptor,
        handle: RandomAccessFile,
        checkCancelled: () -> Unit
    ): TextualInversionExecutionAssetDescriptor {
        checkCancelled()
        val actual = describeExecutionAsset(expected)
        require(actual.sameIdentity(expected) && handle.length() == expected.sizeBytes) {
            "Prompt execution asset ${expected.label} changed before descriptor verification."
        }
        return actual
    }

private fun TextualInversionStore.describeExecutionAsset(
        expected: TextualInversionExecutionAssetDescriptor
    ): TextualInversionExecutionAssetDescriptor {
        val requested = File(expected.path).absoluteFile
        require(!Files.isSymbolicLink(requested.toPath())) {
            "Prompt execution asset ${expected.label} became a symbolic link."
        }
        val file = requested.canonicalFile
        require(file.path == expected.path) {
            "Prompt execution asset ${expected.label} changed canonical path."
        }
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink &&
            attributes.size() == expected.sizeBytes
        ) { "Prompt execution asset ${expected.label} is no longer the expected regular file." }
        return expected.copy(
            fileKey = attributes.fileKey()?.toString(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis()
        )
    }

    private fun sha256(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().textualInversionToHex()
    }

internal fun ByteArray.textualInversionToHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun TextualInversionStore.requireAggregateQuota(
        records: List<TextualInversionArtifact>,
        additionalBytes: Long = 0L
    ) {
        require(additionalBytes >= 0L) { "Textual inversion quota increment must be non-negative." }
        if (additionalBytes > maxTotalBytes) {
            throw TextualInversionStoreException(
                "Textual inversion library exceeds the 512 MiB aggregate quota."
            )
        }
        val installedBytes = aggregateBytes(records)
        if (additionalBytes > maxTotalBytes - installedBytes) {
            throw TextualInversionStoreException(
                "Textual inversion library exceeds the 512 MiB aggregate quota."
            )
        }
    }

internal fun TextualInversionStore.aggregateBytes(records: List<TextualInversionArtifact>): Long {
        var total = 0L
        records.forEach { artifact ->
            if (artifact.sizeBytes > maxTotalBytes - total) {
                throw TextualInversionStoreException(
                    "Textual inversion library exceeds the 512 MiB aggregate quota."
                )
            }
            total += artifact.sizeBytes
        }
        return total
    }

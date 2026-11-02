package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared archive/manifest builders for K14 package tests. */
object AgentPackageTestFixtures {
    const val FIXED_TIME: Long = 1_000L

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun manifestFor(
        artifacts: List<Pair<String, ByteArray>>,
        displayTitle: String = "Imported task",
        displayStatus: AgentDisplayStatus = AgentDisplayStatus.SUCCEEDED,
        exportedAt: Long = FIXED_TIME
    ): ByteArray = AgentTaskPackageManifestCodec.encode(
        AgentTaskPackageManifest(
            exportedAtMillis = exportedAt,
            task = AgentTaskDisplaySummary(
                displayTitle = displayTitle,
                displayStatus = displayStatus,
                createdAtMillis = 100L,
                updatedAtMillis = 200L
            ),
            artifacts = artifacts.map { (path, bytes) ->
                AgentPackageArtifactEntry(
                    relativePath = path,
                    mediaType = "text/plain",
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256(bytes)
                )
            }
        ),
        AgentTaskPackageLimits()
    )

    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun validPackageBytes(
        artifacts: List<Pair<String, ByteArray>> = listOf("artifacts/a.txt" to "alpha".toByteArray())
    ): ByteArray = zip(
        listOf("manifest.json" to manifestFor(artifacts)) +
            artifacts.map { (path, bytes) -> path to bytes }
    )

    /** Patches a field in the FIRST central-directory record; used for header-flag fixtures. */
    fun patchFirstCentralRecord(
        zipBytes: ByteArray,
        fieldOffset: Int,
        value: ByteArray
    ): ByteArray {
        val copy = zipBytes.copyOf()
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        var index = 0
        while (index <= copy.size - 4) {
            if (copy[index] == signature[0] && copy[index + 1] == signature[1] &&
                copy[index + 2] == signature[2] && copy[index + 3] == signature[3]
            ) {
                value.copyInto(copy, index + fieldOffset)
                return copy
            }
            index += 1
        }
        error("central directory not found")
    }

    fun patchFirstCentralFlags(zipBytes: ByteArray, flags: Int): ByteArray =
        patchFirstCentralRecord(zipBytes, 8, byteArrayOf((flags and 0xff).toByte(), (flags ushr 8).toByte()))

    fun patchFirstCentralMethod(zipBytes: ByteArray, method: Int): ByteArray =
        patchFirstCentralRecord(zipBytes, 10, byteArrayOf((method and 0xff).toByte(), (method ushr 8).toByte()))

    fun patchFirstCentralExternalAttributes(zipBytes: ByteArray, attributes: Long): ByteArray {
        val bytes = ByteArray(4) { index -> ((attributes ushr (8 * index)) and 0xff).toByte() }
        return patchFirstCentralRecord(zipBytes, 38, bytes)
    }

    fun truncate(zipBytes: ByteArray, bytes: Int): ByteArray = zipBytes.copyOf(zipBytes.size - bytes)
}

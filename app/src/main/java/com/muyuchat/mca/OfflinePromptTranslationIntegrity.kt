package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * T2B21-T4（b 工位）拆片：文件完整性读取 / 摘要 / 严格 UTF-8 解码 helper 自
 * OfflinePromptTranslationBundleVerification.kt 逐字节平移；仅声明行登记。
 */

internal fun readUtf8File(file: File, maxBytes: Int): String {
        val attributes = readAttributes(file)
        if (!attributes.isRegularFile || attributes.isSymbolicLink ||
            attributes.size() <= 0L || attributes.size() > maxBytes.toLong()
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_UNREADABLE,
                "Offline translation manifest has an invalid size or type."
            )
        }
        val bytes = readBoundedBytes(file, maxBytes.toLong())
        return decodeStrictUtf8(bytes)
    }

    /** Reads and hashes one stable file snapshot. Notice contents are returned only when requested. */
internal fun verifyFileIntegrity(
        file: File,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxSizeBytes: Long,
        captureContents: Boolean
    ): ByteArray? {
        val before = readAttributes(file)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() != expectedSizeBytes ||
            before.size() <= 0L || before.size() > maxSizeBytes
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                "Offline translation file size or type does not match its manifest."
            )
        }
        val captured = if (captureContents) ByteArrayOutputStream(expectedSizeBytes.toInt()) else null
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > expectedSizeBytes || total > maxSizeBytes) {
                    fail(
                        OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                        "Offline translation file changed while it was being read."
                    )
                }
                digest.update(buffer, 0, count)
                captured?.write(buffer, 0, count)
            }
        }
        val after = readAttributes(file)
        if (!after.isRegularFile || after.isSymbolicLink || total != expectedSizeBytes ||
            !sameFileSnapshot(before, after)
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                "Offline translation file changed while it was being verified."
            )
        }
        if (digest.digest().toLowercaseHex() != expectedSha256) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                "Offline translation file SHA-256 does not match its manifest."
            )
        }
        return captured?.toByteArray()
    }

internal fun readBoundedBytes(file: File, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > maxBytes) {
                    fail(
                        OfflinePromptTranslationBundleRejectionCode.MANIFEST_UNREADABLE,
                        "Offline translation manifest exceeds its size limit."
                    )
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

internal fun readAttributes(file: File): BasicFileAttributes = Files.readAttributes(
        file.toPath(),
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )

internal fun sameFileSnapshot(
        before: BasicFileAttributes,
        after: BasicFileAttributes
    ): Boolean = before.fileKey()?.toString() == after.fileKey()?.toString() &&
        before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime()

internal fun decodeStrictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation package contains invalid UTF-8 text.",
            error
        )
    }

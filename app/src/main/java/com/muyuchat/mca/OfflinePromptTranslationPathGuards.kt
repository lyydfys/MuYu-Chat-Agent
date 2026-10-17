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
 * T2B21-T4（b 工位）拆片：provenance / 目录 / 文件 / 条目守卫自
 * OfflinePromptTranslationBundleVerification.kt 逐字节平移；仅声明行登记。
 */

internal fun requireExactProvenance(
        json: JSONObject,
        context: String,
        sourceId: String,
        sourceRevision: String,
        license: String
    ): OfflinePromptTranslationProvenance {
        val actualSourceId = json.requireString(context, "sourceId")
        val actualSourceRevision = json.requireString(context, "sourceRevision")
        val actualLicense = json.requireString(context, "license")
        if (actualSourceId != sourceId || actualSourceRevision != sourceRevision ||
            actualLicense != license
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH,
                "Offline translation $context provenance is not the approved source and revision."
            )
        }
        return OfflinePromptTranslationProvenance(
            sourceId = actualSourceId,
            sourceRevision = actualSourceRevision,
            license = actualLicense
        )
    }

internal fun requireCanonicalDirectory(input: File, description: String): File {
        val lexical = input.absoluteFile
        val lexicalAttributes = readAttributes(lexical)
        if (!lexicalAttributes.isDirectory || lexicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.ROOT_UNAVAILABLE,
                "Offline translation $description is missing, not a directory, or symbolic."
            )
        }
        val canonical = lexical.canonicalFile
        val canonicalAttributes = readAttributes(canonical)
        if (!canonicalAttributes.isDirectory || canonicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.ROOT_UNAVAILABLE,
                "Offline translation $description is not a regular directory."
            )
        }
        return canonical
    }

internal fun requireDirectDirectory(parent: File, name: String): File {
        require(OfflinePromptTranslationContract.isSafeFileName(name))
        val lexical = File(parent, name)
        val lexicalAttributes = readAttributes(lexical)
        if (!lexicalAttributes.isDirectory || lexicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation directory $name is missing or unsafe."
            )
        }
        val canonical = lexical.canonicalFile
        if (canonical.parentFile?.path != parent.path || canonical.name != name) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation directory $name escapes the package boundary."
            )
        }
        val attributes = readAttributes(canonical)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation directory $name is not a regular directory."
            )
        }
        return canonical
    }

internal fun requireDirectRegularFile(parent: File, name: String): File {
        if (!OfflinePromptTranslationContract.isSafeFileName(name)) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation filename is unsafe."
            )
        }
        val lexical = File(parent, name)
        val lexicalAttributes = readAttributes(lexical)
        if (!lexicalAttributes.isRegularFile || lexicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation file $name is missing or unsafe."
            )
        }
        val canonical = lexical.canonicalFile
        if (canonical.parentFile?.path != parent.path || canonical.name != name) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation file $name escapes the package boundary."
            )
        }
        val attributes = readAttributes(canonical)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() <= 0L) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation file $name is not a non-empty regular file."
            )
        }
        return canonical
    }

internal fun requireExactDirectoryEntries(directory: File, expectedNames: Set<String>) {
        val children = directory.listFiles() ?: fail(
            OfflinePromptTranslationBundleRejectionCode.LAYOUT_INVALID,
            "Offline translation package directory cannot be listed."
        )
        val actualNames = children.map(File::getName).toSet()
        if (children.size != expectedNames.size || actualNames != expectedNames) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.LAYOUT_INVALID,
                "Offline translation package has an unexpected file layout."
            )
        }
    }

package com.muyuchat.core.engine

import java.io.File
import java.util.Locale
import org.json.JSONObject

internal fun emitPreparedDiagnostic(
        original: ChatImageAttachment,
        prepared: ChatImageAttachment,
        preprocessing: String,
        sourceType: String,
        messageIndex: Int,
        attachmentIndex: Int,
        sink: (String, JSONObject) -> Unit
    ) {
        runCatching {
            val nativeFile = File(prepared.uriString)
            val details = baseDiagnostic(
                attachment = original,
                sourceType = sourceType,
                messageIndex = messageIndex,
                attachmentIndex = attachmentIndex
            )
                .put("status", "prepared")
                .put("declaredFormat", original.sourceDeclaredFormat(prepared, sourceType))
                .put("requestedFormat", original.normalizedMimeType())
                .put("preprocessing", preprocessing)
                .put("nativeReadablePath", nativeFile.name)
                .put("nativeReadablePathKind", "file_name")
                .put("nativeReadableBytes", nativeFile.length())
                .put("inputBytes", nativeFile.length())
            runCatching {
                inspectFile(nativeFile)
            }.onSuccess { inspection ->
                details
                    .put("inputSha256", inspection.sha256)
                    .put("detectedFormat", inspection.format)
                    .put("inputBytes", inspection.byteCount)
                    .put("originalWidth", inspection.width)
                    .put("originalHeight", inspection.height)
                    .put("dimensionsDetected", inspection.width > 0 && inspection.height > 0)
                    .put("inspectionStatus", "complete")
            }.onFailure { error ->
                details
                    .put("inputSha256", "unavailable")
                    .put("detectedFormat", "unknown")
                    .put("originalWidth", 0)
                    .put("originalHeight", 0)
                    .put("dimensionsDetected", false)
                    .put("inspectionStatus", "failed")
                    .put("inspectionErrorType", error::class.java.simpleName)
            }
            sink("local_vision_input_prepared", details)
        }
    }

internal fun emitFailureDiagnostic(
        attachment: ChatImageAttachment,
        sourceType: String,
        messageIndex: Int,
        attachmentIndex: Int,
        error: Throwable,
        sink: (String, JSONObject) -> Unit
    ) {
        runCatching {
            val details = baseDiagnostic(
                attachment = attachment,
                sourceType = sourceType,
                messageIndex = messageIndex,
                attachmentIndex = attachmentIndex
            )
                .put("status", "failed")
                .put("preprocessing", "not_started")
                .put("inputSha256", "unavailable")
                .put("detectedFormat", "unknown")
                .put("inputBytes", attachment.sizeBytes.coerceAtLeast(0L))
                .put("originalWidth", attachment.width.coerceAtLeast(0))
                .put("originalHeight", attachment.height.coerceAtLeast(0))
                .put("dimensionsDetected", false)
                .put("nativeReadablePath", "unavailable")
                .put("nativeReadablePathKind", "unavailable")
                .put("nativeReadableBytes", 0L)
                .put("inspectionStatus", "not_available")
                .put("errorType", error::class.java.simpleName)
                .put("failureCode", (error as? LocalVisionInputException)?.failureCode?.wireCode.orEmpty())
            sink("local_vision_input_prepare_failed", details)
        }
    }

internal fun baseDiagnostic(
        attachment: ChatImageAttachment,
        sourceType: String,
        messageIndex: Int,
        attachmentIndex: Int
    ): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("messageIndex", messageIndex)
        .put("attachmentIndex", attachmentIndex)
        .put("sourceType", sourceType)
        .put("declaredFormat", attachment.normalizedMimeType())
        .put("attachmentName", attachment.name.safeFileName())
        .put("sourceReferenceSha256", attachment.sourceReference().sha256())


internal fun ChatImageAttachment.sourceDeclaredFormat(
        prepared: ChatImageAttachment,
        sourceType: String
    ): String = when (sourceType) {
        "inline" -> dataBase64
            .takeIf { it.startsWith("data:", ignoreCase = true) }
            ?.substring(5)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: normalizedMimeType()
        "http" -> prepared.normalizedMimeType()
        else -> normalizedMimeType()
    }

internal fun ChatImageAttachment.normalizedMimeType(): String =
        mimeType.trim().lowercase(Locale.US).ifBlank { "unknown" }


internal fun ChatImageAttachment.sourceType(): String {
        if (hasInlineData) return "inline"
        val source = uriString.trim()
        return when {
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> "http"
            source.startsWith("content://", ignoreCase = true) -> "content"
            else -> "file"
        }
    }

internal fun ChatImageAttachment.sourceReference(): String =
        when {
            hasInlineData -> "inline:${name.safeFileName()}:${dataBase64.length}"
            else -> uriString.trim()
        }

internal fun String.safeFileName(): String =
        replace('\\', '/').substringAfterLast('/').take(160)

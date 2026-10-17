package com.muyuchat.mca

import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import com.muyuchat.mca.TextualInversionStore.Companion.COPY_BUFFER_BYTES
import com.muyuchat.mca.TextualInversionStore.Companion.MAX_SAFETENSORS_HEADER_BYTES
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal suspend fun TextualInversionStore.importFromContentUriImpl(
        uri: Uri,
        trigger: String,
        modelFingerprint: String? = null,
        tokenizerFingerprint: String? = null,
        displayNameOverride: String? = null
    ): TextualInversionImportResult {
        requireTextualInversionContentImportUri(uri.scheme, uri.authority)
        return importFromSource(
            trigger = trigger,
            modelFingerprint = modelFingerprint,
            tokenizerFingerprint = tokenizerFingerprint,
            displayNameProvider = {
                displayNameOverride?.trim().takeUnless { it.isNullOrBlank() } ?: displayName(uri)
            },
            openInput = {
                appContext?.contentResolver?.openInputStream(uri)
                    ?: throw IOException("Unable to open textual inversion source URI.")
            }
        )
    }
internal suspend fun TextualInversionStore.importFromStreamImpl(
        openInput: () -> InputStream,
        trigger: String,
        displayName: String,
        modelFingerprint: String? = null,
        tokenizerFingerprint: String? = null
    ): TextualInversionImportResult = importFromSource(
        trigger = trigger,
        modelFingerprint = modelFingerprint,
        tokenizerFingerprint = tokenizerFingerprint,
        displayNameProvider = { displayName },
        openInput = openInput
    )
private suspend fun TextualInversionStore.importFromSource(
        trigger: String,
        modelFingerprint: String?,
        tokenizerFingerprint: String?,
        displayNameProvider: () -> String,
        openInput: () -> InputStream
    ): TextualInversionImportResult = withCommittedMutation { checkCancelled, markCommitted ->
        checkCancelled()
        val normalizedTrigger = trigger.trim()
        require(TextualInversionContract.TRIGGER_PATTERN.matches(normalizedTrigger)) {
            "Textual inversion trigger is invalid."
        }
        val normalizedModel = modelFingerprint?.trim()?.lowercase()?.also {
            require(TextualInversionContract.SHA256_PATTERN.matches(it)) {
                "Textual inversion model fingerprint is invalid."
            }
        }
        val normalizedTokenizer = tokenizerFingerprint?.trim()?.lowercase()?.also {
            require(TextualInversionContract.SHA256_PATTERN.matches(it)) {
                "Textual inversion tokenizer fingerprint is invalid."
            }
        }
        val displayName = displayNameProvider().trim().takeIf(String::isNotBlank)
            ?: "textual-inversion.safetensors"
        val format = TextualInversionFormat.fromExtension(
            displayName.substringAfterLast('.', "").lowercase()
        )
        ensureRoot()
        val id = UUID.randomUUID().toString().lowercase()
        val fileName = "$id.${format.extension}"
        val target = directChild(fileName)
        val part = directChild(".$fileName.${UUID.randomUUID()}.part")
        val current = loadLocked(checkCancelled)
        val installedBytes = aggregateBytes(current)
        val remainingAggregateBytes = maxTotalBytes - installedBytes
        require(remainingAggregateBytes >= TextualInversionContract.MIN_BYTES) {
            "Textual inversion library has no remaining aggregate quota."
        }
        var published = false
        try {
            val input = openInput()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use { source ->
                FileOutputStream(part).use { rawOutput ->
                    BufferedOutputStream(rawOutput).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            checkCancelled()
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read.toLong() > remainingAggregateBytes - copied) {
                                throw TextualInversionStoreException(
                                    "Textual inversion library exceeds the 512 MiB aggregate quota."
                                )
                            }
                            val usableBytes = usableSpaceProvider(root)
                            if (usableBytes <= minFreeSpaceReserveBytes ||
                                read.toLong() > usableBytes - minFreeSpaceReserveBytes
                            ) {
                                throw TextualInversionStoreException(
                                    "Textual inversion import must leave 64 MiB of free storage."
                                )
                            }
                            copied += read.toLong()
                            if (copied > TextualInversionContract.MAX_BYTES) {
                                throw TextualInversionStoreException("Textual inversion exceeds the 100 MiB bound.")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        rawOutput.fd.sync()
                    }
                }
            }
            require(copied in TextualInversionContract.MIN_BYTES..TextualInversionContract.MAX_BYTES) {
                "Textual inversion is empty or outside the supported size bound."
            }
            validateFormat(part, format)
            val sha256 = digest.digest().textualInversionToHex()
            checkCancelled()

            val existing = current.firstOrNull {
                it.sha256.equals(sha256, ignoreCase = true) &&
                    it.sizeBytes == copied &&
                    it.trigger.equals(normalizedTrigger, ignoreCase = true)
            }
            if (existing != null) {
                part.delete()
                return@withCommittedMutation TextualInversionImportResult(
                    existing,
                    duplicate = true
                )
            }
            requireAggregateQuota(current, additionalBytes = copied)
            if (!moveIntoPlace(part, target)) {
                throw IOException("Unable to publish textual inversion artifact.")
            }
            val artifact = TextualInversionArtifact(
                id = id,
                name = displayName.substringBeforeLast('.').ifBlank { "Textual inversion" }
                    .take(TextualInversionContract.MAX_NAME_CHARS),
                trigger = normalizedTrigger,
                fileName = fileName,
                path = target.canonicalPath,
                sha256 = sha256,
                sizeBytes = copied,
                format = format,
                modelFingerprint = normalizedModel,
                tokenizerFingerprint = normalizedTokenizer,
                importedAt = System.currentTimeMillis()
            )
            checkCancelled()
            val result = TextualInversionImportResult(artifact)
            commitManifest(
                records = current + artifact,
                checkCancelled = checkCancelled,
                onCommitted = {
                    published = true
                    markCommitted(result)
                }
            )
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!published) {
                part.delete()
                target.delete()
            }
        }
    }

private fun TextualInversionStore.displayName(uri: Uri): String = try {
        appContext?.contentResolver?.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }?.trim()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "textual-inversion.safetensors"

private fun TextualInversionStore.validateFormat(file: File, format: TextualInversionFormat) {
        when (format) {
            TextualInversionFormat.SAFETENSORS -> validateSafetensors(file)
            TextualInversionFormat.PYTORCH,
            TextualInversionFormat.CHECKPOINT -> validateTorchArchive(file)
            TextualInversionFormat.BINARY -> Unit
        }
    }

private fun TextualInversionStore.validateSafetensors(file: File) {
        BufferedInputStream(file.inputStream()).use { input ->
            val lengthBytes = ByteArray(Long.SIZE_BYTES)
            require(input.readFully(lengthBytes) == lengthBytes.size) {
                "Textual inversion safetensors header is truncated."
            }
            val headerLength = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .long
            require(headerLength in 2L..MAX_SAFETENSORS_HEADER_BYTES) {
                "Textual inversion safetensors header is outside its bound."
            }
            require(headerLength <= file.length() - Long.SIZE_BYTES) {
                "Textual inversion safetensors header exceeds the file."
            }
            val header = ByteArray(headerLength.toInt())
            require(input.readFully(header) == header.size) {
                "Textual inversion safetensors header is truncated."
            }
            val headerText = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(header))
                    .toString()
            } catch (failure: CharacterCodingException) {
                throw IllegalArgumentException(
                    "Textual inversion safetensors header is not valid UTF-8.",
                    failure
                )
            }
            val tokener = JSONTokener(headerText)
            val decoded = tokener.nextValue()
            require(decoded is JSONObject && tokener.nextClean() == '\u0000') {
                "Textual inversion safetensors header must be one complete JSON object."
            }
            val json = decoded
            json.opt("__metadata__")?.let { metadataValue ->
                require(metadataValue is JSONObject) {
                    "Textual inversion safetensors metadata must be an object."
                }
                metadataValue.keys().forEach { key ->
                    require(metadataValue.opt(key) is String) {
                        "Textual inversion safetensors metadata values must be strings."
                    }
                }
            }
            val tensorNames = json.keys().asSequence().filter { it != "__metadata__" }.toList()
            require(tensorNames.isNotEmpty()) {
                "Textual inversion safetensors has no tensor entries."
            }
            val dataBytes = file.length() - Long.SIZE_BYTES - headerLength
            val spans = tensorNames.map { name ->
                val tensor = json.opt(name) as? JSONObject
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors tensor metadata must be an object."
                    )
                require(
                    tensor.keys().asSequence().toSet() ==
                        setOf("dtype", "shape", "data_offsets")
                ) { "Textual inversion safetensors tensor metadata has unknown or missing fields." }
                val dtype = tensor.opt("dtype") as? String
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors tensor dtype must be a string."
                    )
                val elementBytes = when (dtype) {
                    "F16", "BF16" -> 2L
                    "F32" -> 4L
                    "F64" -> 8L
                    else -> throw IllegalArgumentException(
                        "Textual inversion safetensors tensor dtype is unsupported."
                    )
                }
                val shape = tensor.opt("shape") as? JSONArray
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors tensor shape must be an array."
                    )
                require(shape.length() > 0) {
                    "Textual inversion safetensors tensor shape must not be empty."
                }
                var elementCount = 1L
                for (index in 0 until shape.length()) {
                    val dimension = shape.opt(index).strictNonNegativeLong()
                    require(dimension > 0L && elementCount <= Long.MAX_VALUE / dimension) {
                        "Textual inversion safetensors tensor shape is empty or overflows."
                    }
                    elementCount *= dimension
                }
                require(elementCount <= Long.MAX_VALUE / elementBytes) {
                    "Textual inversion safetensors tensor byte count overflows."
                }
                val offsets = tensor.opt("data_offsets") as? JSONArray
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors data offsets must be an array."
                    )
                require(offsets.length() == 2) {
                    "Textual inversion safetensors data offsets must contain two integers."
                }
                val start = offsets.opt(0).strictNonNegativeLong()
                val end = offsets.opt(1).strictNonNegativeLong()
                require(end > start && end - start == elementCount * elementBytes) {
                    "Textual inversion safetensors tensor byte span is invalid."
                }
                require(end <= dataBytes) {
                    "Textual inversion safetensors tensor byte span exceeds the file."
                }
                start to end
            }.sortedBy { it.first }
            var expectedStart = 0L
            spans.forEach { (start, end) ->
                require(start == expectedStart) {
                    "Textual inversion safetensors tensor spans overlap or leave holes."
                }
                expectedStart = end
            }
            require(expectedStart == dataBytes) {
                "Textual inversion safetensors data contains unreferenced trailing bytes."
            }
        }
    }

    private fun Any?.strictNonNegativeLong(): Long = when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        else -> throw IllegalArgumentException(
            "Textual inversion safetensors dimensions and offsets must be integers."
        )
    }.also { value ->
        require(value >= 0L) {
            "Textual inversion safetensors dimensions and offsets must be non-negative."
        }
    }

private fun TextualInversionStore.validateTorchArchive(file: File) {
        file.inputStream().buffered().use { input ->
            val first = input.read()
            val second = input.read()
            val zipContainer = first == 'P'.code && second == 'K'.code
            val pickleStream = first == 0x80 && second in 0x02..0x05
            require(zipContainer || pickleStream) {
                "Textual inversion PyTorch/checkpoint header is invalid."
            }
        }
    }

    private fun java.io.InputStream.readFully(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

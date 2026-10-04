package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Consumes the concrete files reported by stable-diffusion.cpp and always removes every
 * worker-private output after reading it. Older single-image runtimes may omit outputs[], but a
 * batch must prove its exact count, contiguous indices, deterministic seeds, MIME type, and files.
 */


internal fun consumeStableDiffusionOutputs(
    result: JSONObject,
    expectedCount: Int,
    expectedSeed: Long,
    legacyOutputFile: File,
    requireCommittedEvidence: Boolean = false
): List<LocalImageOutput> {
    require(expectedCount in 1..8) { "stable-diffusion.cpp output count must be between 1 and 8." }
    require(expectedSeed >= 0L) { "stable-diffusion.cpp output seed must be non-negative." }
    val outputRoot = requireNotNull(legacyOutputFile.parentFile).canonicalFile
    val cleanupCandidates = linkedSetOf(legacyOutputFile.canonicalFile)
    val hasOutputsField = result.has("outputs")
    val rawOutputs = result.opt("outputs")
    val outputArray = rawOutputs as? JSONArray

    fun rememberCleanupCandidate(path: String) {
        if (path.isBlank()) return
        runCatching { File(path).canonicalFile }
            .getOrNull()
            ?.takeIf { candidate ->
                candidate.path.startsWith(outputRoot.path + File.separator)
            }
            ?.let(cleanupCandidates::add)
    }

    (result.opt("path") as? String)?.let(::rememberCleanupCandidate)
    if (outputArray != null) {
        for (index in 0 until outputArray.length()) {
            val item = outputArray.optJSONObject(index) ?: continue
            (item.opt("path") as? String)?.let(::rememberCleanupCandidate)
        }
    } else if (rawOutputs is JSONObject) {
        (rawOutputs.opt("path") as? String)?.let(::rememberCleanupCandidate)
    }

    fun JSONObject.requiredOutputString(name: String): String {
        require(has(name) && !isNull(name)) { "stable-diffusion.cpp output is missing $name." }
        val raw = get(name)
        require(raw is String && raw.isNotBlank()) {
            "stable-diffusion.cpp output $name must be a non-blank string."
        }
        return raw
    }

    fun JSONObject.requiredOutputLong(name: String): Long {
        require(has(name) && !isNull(name)) { "stable-diffusion.cpp output is missing $name." }
        val raw = get(name)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "stable-diffusion.cpp output $name must be an integer."
        }
        return (raw as Number).toLong()
    }

    fun validatedMimeType(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized == "image/png") {
            "stable-diffusion.cpp output MIME type must be image/png."
        }
        return normalized
    }

    fun validatedOutputFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.path.startsWith(outputRoot.path + File.separator)) {
            "stable-diffusion.cpp output path escaped its output directory."
        }
        require(file.isFile && file.length() > 0L) {
            "stable-diffusion.cpp output file is missing or empty: ${file.name}"
        }
        require(file.length() in
            MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES..MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES
        ) {
            "stable-diffusion.cpp output file is outside the bounded PNG size contract."
        }
        return file
    }

    data class ValidatedOutputDescriptor(
        val file: File,
        val mimeType: String,
        val seed: Long,
        val sha256: String?,
        val sizeBytes: Long?,
        val atomicCommit: Boolean?
    )

    fun JSONObject.optionalCommittedOutputEvidence(file: File): Triple<String?, Long?, Boolean?> {
        val fields = listOf("sha256", "sizeBytes", "atomicCommit")
        if (fields.none(::has)) return Triple(null, null, null)
        require(fields.all(::has) && fields.none(::isNull)) {
            "stable-diffusion.cpp committed output evidence must be complete."
        }
        val sha256 = requiredOutputString("sha256").lowercase()
        require(QNN_OUTPUT_SHA256.matches(sha256)) {
            "stable-diffusion.cpp output sha256 must be a lowercase digest."
        }
        val sizeBytes = requiredOutputLong("sizeBytes")
        require(sizeBytes > 0L && sizeBytes == file.length()) {
            "stable-diffusion.cpp output size evidence does not match the committed file."
        }
        val atomicCommit = opt("atomicCommit") as? Boolean
            ?: error("stable-diffusion.cpp output atomicCommit must be boolean.")
        require(atomicCommit) {
            "stable-diffusion.cpp must not attach commit evidence to a non-atomic output."
        }
        return Triple(sha256, sizeBytes, atomicCommit)
    }

    return try {
        require(!hasOutputsField || outputArray != null) {
            "stable-diffusion.cpp outputs must be an array."
        }
        if (outputArray == null) {
            require(!requireCommittedEvidence) {
                "stable-diffusion.cpp committed output evidence requires outputs[]."
            }
            require(expectedCount == 1) {
                "stable-diffusion.cpp batch output is missing outputs[]."
            }
            val path = if (result.has("path")) {
                result.requiredOutputString("path")
            } else {
                legacyOutputFile.canonicalPath
            }
            val mimeType = if (result.has("mimeType")) {
                validatedMimeType(result.requiredOutputString("mimeType"))
            } else {
                "image/png"
            }
            val file = validatedOutputFile(path)
            listOf(
                LocalImageOutput(
                    bytes = file.readBytes(),
                    mimeType = mimeType,
                    seed = expectedSeed,
                    index = 0
                )
            )
        } else {
            require(outputArray.length() == expectedCount) {
                "stable-diffusion.cpp returned ${outputArray.length()} outputs; expected $expectedCount."
            }
            listOf("outputCount", "n").forEach { field ->
                if (result.has(field)) {
                    require(result.requiredOutputLong(field) == expectedCount.toLong()) {
                        "stable-diffusion.cpp $field does not match outputs[]."
                    }
                }
            }
            val descriptors = buildList {
                for (index in 0 until outputArray.length()) {
                    val item = outputArray.optJSONObject(index)
                        ?: error("stable-diffusion.cpp output item must be an object.")
                    require(item.requiredOutputLong("index") == index.toLong()) {
                        "stable-diffusion.cpp output indices must be contiguous and start at zero."
                    }
                    val expectedOutputSeed = Math.addExact(expectedSeed, index.toLong())
                    require(item.requiredOutputLong("seed") == expectedOutputSeed) {
                        "stable-diffusion.cpp output seed does not match index $index."
                    }
                    val mimeType = validatedMimeType(item.requiredOutputString("mimeType"))
                    val file = validatedOutputFile(item.requiredOutputString("path"))
                    val (sha256, sizeBytes, atomicCommit) =
                        item.optionalCommittedOutputEvidence(file)
                    if (requireCommittedEvidence) {
                        require(sha256 != null && sizeBytes != null && atomicCommit == true) {
                            "stable-diffusion.cpp UltraFix output is missing committed byte evidence."
                        }
                    }
                    add(ValidatedOutputDescriptor(
                        file = file,
                        mimeType = mimeType,
                        seed = expectedOutputSeed,
                        sha256 = sha256,
                        sizeBytes = sizeBytes,
                        atomicCommit = atomicCommit
                    ))
                }
            }
            require(descriptors.map { it.file.canonicalPath }.distinct().size == descriptors.size) {
                "stable-diffusion.cpp returned duplicate output paths."
            }
            val legacyPath = validatedOutputFile(result.requiredOutputString("path"))
            val legacyMimeType = validatedMimeType(result.requiredOutputString("mimeType"))
            require(legacyPath.canonicalPath == descriptors.first().file.canonicalPath &&
                legacyMimeType == descriptors.first().mimeType
            ) {
                "stable-diffusion.cpp legacy output fields must mirror output index zero."
            }
            descriptors.mapIndexed { index, descriptor ->
                val bytes = descriptor.file.readBytes()
                descriptor.sha256?.let { expectedSha256 ->
                    val actualSha256 = MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { byte ->
                            "%02x".format(byte.toInt() and 0xff)
                        }
                    require(actualSha256 == expectedSha256 &&
                        descriptor.sizeBytes == bytes.size.toLong() &&
                        descriptor.atomicCommit == true
                    ) {
                        "stable-diffusion.cpp committed output bytes do not match native evidence."
                    }
                }
                LocalImageOutput(
                    bytes = bytes,
                    mimeType = descriptor.mimeType,
                    seed = descriptor.seed,
                    index = index
                )
            }
        }
    } finally {
        cleanupCandidates.forEach { file -> runCatching { file.delete() } }
    }
}

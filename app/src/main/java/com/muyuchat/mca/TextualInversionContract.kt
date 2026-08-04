package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Product contract for a textual-inversion (custom-word) artifact.
 *
 * The artifact is deliberately separate from LoRA and from the model token table.  A textual
 * inversion file is loaded by the native CLIP conditioner and therefore must be bound to the
 * exact model bytes and tokenizer component that will consume it.  No device, chipset, or
 * recommendation id participates in this contract.
 */
data class TextualInversionArtifact(
    val id: String,
    val name: String,
    val trigger: String,
    val fileName: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val format: TextualInversionFormat,
    // Advisory evidence from the most recent successful native binding. These values must never
    // become an admission gate: every request creates a fresh exact binding and lets the real
    // native tensor/schema load decide compatibility with the selected model.
    val modelFingerprint: String? = null,
    val tokenizerFingerprint: String? = null,
    val importedAt: Long = 0L
) {
    init {
        require(TextualInversionContract.UUID_PATTERN.matches(id)) {
            "Textual inversion id must be a UUID."
        }
        require(name.isNotBlank() && name.length <= TextualInversionContract.MAX_NAME_CHARS) {
            "Textual inversion name is invalid."
        }
        require(TextualInversionContract.TRIGGER_PATTERN.matches(trigger)) {
            "Textual inversion trigger must be a non-empty printable token."
        }
        require(fileName == "$id.${format.extension}") {
            "Textual inversion file name does not match its id and format."
        }
        require(path.isNotBlank() && path.length <= TextualInversionContract.MAX_PATH_CHARS) {
            "Textual inversion path is invalid."
        }
        require(TextualInversionContract.SHA256_PATTERN.matches(sha256)) {
            "Textual inversion sha256 must be lowercase hexadecimal."
        }
        require(sizeBytes in TextualInversionContract.MIN_BYTES..TextualInversionContract.MAX_BYTES) {
            "Textual inversion size is outside the supported bound."
        }
        modelFingerprint?.let {
            require(TextualInversionContract.SHA256_PATTERN.matches(it)) {
                "Textual inversion model fingerprint must be SHA-256."
            }
        }
        tokenizerFingerprint?.let {
            require(TextualInversionContract.SHA256_PATTERN.matches(it)) {
                "Textual inversion tokenizer fingerprint must be SHA-256."
            }
        }
        require(importedAt >= 0L) { "Textual inversion importedAt must be non-negative." }
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("trigger", trigger)
        .put("fileName", fileName)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("format", format.wireName)
        .put("modelFingerprint", modelFingerprint ?: JSONObject.NULL)
        .put("tokenizerFingerprint", tokenizerFingerprint ?: JSONObject.NULL)
        .put("importedAt", importedAt)
        .apply { if (includePath) put("path", path) }

    fun bind(
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
    ): TextualInversionBinding = TextualInversionBinding(
        artifact = this,
        modelFingerprint = modelFingerprint,
        tokenizerFingerprint = tokenizerFingerprint,
        profileId = profileId,
        profileRevision = profileRevision,
        runtime = runtime
    )

    companion object {
        fun fromJson(json: JSONObject): TextualInversionArtifact = TextualInversionArtifact(
            id = json.getString("id").trim().lowercase(),
            name = json.getString("name").trim(),
            trigger = json.getString("trigger").trim(),
            fileName = json.getString("fileName").trim(),
            path = json.getString("path").trim(),
            sha256 = json.getString("sha256").trim().lowercase(),
            sizeBytes = json.getLong("sizeBytes"),
            format = TextualInversionFormat.fromWireName(json.getString("format")),
            modelFingerprint = json.optString("modelFingerprint")
                .trim().lowercase().takeIf { it.isNotBlank() },
            tokenizerFingerprint = json.optString("tokenizerFingerprint")
                .trim().lowercase().takeIf { it.isNotBlank() },
            importedAt = json.optLong("importedAt", 0L)
        )
    }
}

enum class TextualInversionFormat(
    val extension: String,
    val wireName: String
) {
    SAFETENSORS("safetensors", "safetensors"),
    PYTORCH("pt", "pytorch"),
    CHECKPOINT("ckpt", "checkpoint"),
    BINARY("bin", "binary");

    companion object {
        fun fromExtension(extension: String): TextualInversionFormat = when (
            extension.trim().lowercase()
        ) {
            "safetensors" -> SAFETENSORS
            "pt" -> PYTORCH
            "ckpt" -> CHECKPOINT
            "bin" -> BINARY
            else -> throw IllegalArgumentException("Unsupported textual inversion extension.")
        }

        fun fromWireName(value: String): TextualInversionFormat {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.wireName == normalized || it.extension == normalized }
                ?: throw IllegalArgumentException("Unsupported textual inversion format.")
        }
    }
}

enum class TextualInversionRuntime(
    val wireName: String,
    val nativeMode: String
) {
    STABLE_DIFFUSION_CPP("STABLE_DIFFUSION_CPP", "SDCPP_CUSTOM_WORDS"),
    QNN_HTP("QNN_HTP", "MNN_CLIP_INPUT_EMBEDDING"),
    MNN_DIFFUSION("MNN_DIFFUSION", "MNN_CLIP_INPUT_EMBEDDING")
}

internal fun compareUtf8Unsigned(left: String, right: String): Int {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    val commonLength = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until commonLength) {
        val comparison = (leftBytes[index].toInt() and 0xff)
            .compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

data class TextualInversionExecutionAssetDescriptor(
    val label: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val fileKey: String? = null,
    val lastModifiedMillis: Long = 0L
) {
    init {
        require(label.isNotBlank() && label.length <= TextualInversionContract.MAX_PATH_CHARS &&
            '\u001f' !in label && '\u0000' !in label
        ) { "Textual inversion execution asset label is invalid." }
        require(File(path).isAbsolute && path.length <= TextualInversionContract.MAX_PATH_CHARS) {
            "Textual inversion execution asset path must be absolute."
        }
        require(sizeBytes > 0L) { "Textual inversion execution asset size must be positive." }
        require(TextualInversionContract.SHA256_PATTERN.matches(sha256)) {
            "Textual inversion execution asset SHA-256 is invalid."
        }
        require(lastModifiedMillis >= 0L) {
            "Textual inversion execution asset modification time must be non-negative."
        }
    }

    internal fun sameIdentity(other: TextualInversionExecutionAssetDescriptor): Boolean =
        label == other.label && path == other.path && fileKey == other.fileKey &&
            sizeBytes == other.sizeBytes && lastModifiedMillis == other.lastModifiedMillis &&
            sha256 == other.sha256

    fun toNativeJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("path", path)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
}

data class TextualInversionExecutionAssetBinding(
    val runtime: TextualInversionRuntime,
    val bundleRoot: String,
    val profilePromptFingerprint: String,
    val assets: List<TextualInversionExecutionAssetDescriptor>
) {
    init {
        require(File(bundleRoot).isAbsolute && bundleRoot.length <= TextualInversionContract.MAX_PATH_CHARS) {
            "Textual inversion execution bundle root must be absolute."
        }
        require(TextualInversionContract.SHA256_PATTERN.matches(profilePromptFingerprint)) {
            "Textual inversion execution profile fingerprint is invalid."
        }
        require(assets.isNotEmpty()) { "Textual inversion execution assets are required." }
        require(assets == assets.sortedWith { left, right ->
            compareUtf8Unsigned(left.label, right.label)
        } &&
            assets.map(TextualInversionExecutionAssetDescriptor::label).distinct().size == assets.size &&
            assets.map(TextualInversionExecutionAssetDescriptor::path).distinct().size == assets.size
        ) { "Textual inversion execution assets must have unique, sorted labels and paths." }
    }

    val compositeSha256: String
        get() = TextualInversionContract.sha256(
            (
                listOf(
                    "textual-inversion-execution-assets-v1",
                    runtime.wireName,
                    profilePromptFingerprint
                ) + assets.flatMap { asset ->
                    listOf(asset.label, asset.sizeBytes.toString(), asset.sha256)
                }
                ).joinToString("\u001f")
        )

    fun putIntoNativeJson(target: JSONObject): JSONObject {
        val descriptors = JSONArray()
        assets.forEach { asset -> descriptors.put(asset.toNativeJson()) }
        return target
            .put("textualInversionExecutionAssets", descriptors)
            .put("textualInversionExecutionAssetsSha256", compositeSha256)
            .put("textualInversionExecutionRuntime", runtime.wireName)
            .put("textualInversionExecutionBundleRoot", bundleRoot)
            .put("textualInversionExecutionProfileFingerprint", profilePromptFingerprint)
    }
}

/** A generation-time, exact binding. It cannot be constructed for an unbound artifact. */
data class TextualInversionBinding(
    val artifact: TextualInversionArtifact,
    val modelFingerprint: String,
    val tokenizerFingerprint: String,
    val profileId: String,
    val profileRevision: Int,
    val runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
) {
    val bindingFingerprint: String
        get() = TextualInversionContract.sha256(
            listOf(
                "textual-inversion-binding-v1",
                artifact.id,
                artifact.sha256,
                artifact.trigger.lowercase(),
                modelFingerprint.lowercase(),
                tokenizerFingerprint.lowercase(),
                profileId,
                profileRevision.toString(),
                runtime.wireName
            ).joinToString("\u001f")
        )

    init {
        require(TextualInversionContract.SHA256_PATTERN.matches(modelFingerprint.lowercase())) {
            "Textual inversion model fingerprint must be SHA-256."
        }
        require(TextualInversionContract.SHA256_PATTERN.matches(tokenizerFingerprint.lowercase())) {
            "Textual inversion tokenizer fingerprint must be SHA-256."
        }
        require(profileId.isNotBlank() && profileId.length <= TextualInversionContract.MAX_PROFILE_ID_CHARS) {
            "Textual inversion profile id is invalid."
        }
        require(profileRevision > 0) { "Textual inversion profile revision must be positive." }
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .put("id", artifact.id)
        .put("name", artifact.name)
        .put("trigger", artifact.trigger)
        .put("path", if (includePath) artifact.path else JSONObject.NULL)
        .put("sha256", artifact.sha256)
        .put("sizeBytes", artifact.sizeBytes)
        .put("format", artifact.format.wireName)
        .put("modelFingerprint", modelFingerprint.lowercase())
        .put("tokenizerFingerprint", tokenizerFingerprint.lowercase())
        .put("profileId", profileId)
        .put("profileRevision", profileRevision)
        .put("runtime", runtime.wireName)
        .put("bindingFingerprint", bindingFingerprint)

    /** The shape consumed by the stable-diffusion native bridge. */
    fun toNativeJson(): JSONObject = toJson(includePath = true)
        .put("nativeMode", runtime.nativeMode)

    companion object {
        fun fromJson(json: JSONObject): TextualInversionBinding {
            val artifact = TextualInversionArtifact(
                id = json.getString("id").trim().lowercase(),
                name = json.getString("name").trim(),
                trigger = json.getString("trigger").trim(),
                fileName = json.optString("fileName").ifBlank {
                    "${json.getString("id").trim().lowercase()}.${TextualInversionFormat.fromWireName(json.getString("format")).extension}"
                },
                path = json.getString("path").trim(),
                sha256 = json.getString("sha256").trim().lowercase(),
                sizeBytes = json.getLong("sizeBytes"),
                format = TextualInversionFormat.fromWireName(json.getString("format")),
                modelFingerprint = json.optString("artifactModelFingerprint")
                    .trim().lowercase().takeIf { it.isNotBlank() },
                tokenizerFingerprint = json.optString("artifactTokenizerFingerprint")
                    .trim().lowercase().takeIf { it.isNotBlank() }
            )
            return TextualInversionBinding(
                artifact = artifact,
                modelFingerprint = json.getString("modelFingerprint").trim().lowercase(),
                tokenizerFingerprint = json.getString("tokenizerFingerprint").trim().lowercase(),
                profileId = json.getString("profileId").trim(),
                profileRevision = json.getInt("profileRevision"),
                runtime = TextualInversionRuntime.entries.first {
                    it.wireName == json.getString("runtime").trim()
                }
            )
        }
    }
}

data class TextualInversionSelection(
    val bindings: List<TextualInversionBinding>,
    val executionAssetBinding: TextualInversionExecutionAssetBinding? = null
) {
    init {
        require(bindings.size <= TextualInversionContract.MAX_COUNT) {
            "At most ${TextualInversionContract.MAX_COUNT} textual inversions may be active."
        }
        require(bindings.map { it.artifact.id }.distinct().size == bindings.size) {
            "Textual inversion ids must be unique per request."
        }
        require(bindings.map { it.artifact.trigger.lowercase() }.distinct().size == bindings.size) {
            "Textual inversion triggers must be unique per request."
        }
        var activeBytes = 0L
        bindings.forEach { binding ->
            require(binding.artifact.sizeBytes <= TextualInversionContract.MAX_ACTIVE_BYTES - activeBytes) {
                "Selected textual inversion artifacts exceed the 256 MiB active-request quota."
            }
            activeBytes += binding.artifact.sizeBytes
        }
        val context = bindings.firstOrNull()
        if (context != null) {
            require(bindings.all {
                it.modelFingerprint.equals(context.modelFingerprint, ignoreCase = true) &&
                    it.tokenizerFingerprint.equals(context.tokenizerFingerprint, ignoreCase = true) &&
                    it.profileId == context.profileId &&
                    it.profileRevision == context.profileRevision &&
                    it.runtime == context.runtime
            }) { "Textual inversion bindings must share one exact model/tokenizer profile." }
            executionAssetBinding?.let { execution ->
                require(execution.runtime == context.runtime &&
                    execution.compositeSha256 == context.tokenizerFingerprint.lowercase()
                ) {
                    "Textual inversion execution assets must match the selected runtime and consumer composite."
                }
            }
        } else {
            require(executionAssetBinding == null) {
                "Textual inversion execution assets require at least one binding."
            }
        }
    }

    val bindingFingerprint: String
        get() = TextualInversionContract.sha256(
            (
                listOf("textual-inversion-selection-v1") +
                    bindings.sortedBy { it.artifact.trigger.lowercase() }.map { it.bindingFingerprint }
                ).joinToString("\u001f")
        )

    fun toNativeJson(rootPath: String): JSONObject {
        require(rootPath.isNotBlank() && rootPath.length <= TextualInversionContract.MAX_PATH_CHARS) {
            "Textual inversion native root path is required."
        }
        val array = JSONArray()
        bindings.forEach { array.put(it.toNativeJson()) }
        val nativeMode = bindings.firstOrNull()?.runtime?.nativeMode ?: "none"
        return JSONObject()
            .put("textualInversions", array)
            .put("textualInversionCount", bindings.size)
            .put("textualInversionBindingFingerprint", bindingFingerprint)
            .put("textualInversionNativeMode", nativeMode)
            .put("textualInversionRootPath", rootPath)
            .put("textualInversionSupported", bindings.isNotEmpty())
            .also { target -> executionAssetBinding?.putIntoNativeJson(target) }
    }
}

object TextualInversionContract {
    const val MAX_COUNT = 8
    const val MIN_BYTES = 16L
    const val MAX_BYTES = 100L * 1024L * 1024L
    const val MAX_ACTIVE_BYTES = 256L * 1024L * 1024L
    const val MAX_NAME_CHARS = 128
    const val MAX_PATH_CHARS = 4_096
    const val MAX_PROFILE_ID_CHARS = 256
    val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    val UUID_PATTERN: Regex = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
    // CLIP's tokenizer treats this as one special token. Permit common <foo> triggers and plain
    // identifiers, but never whitespace, control characters, JSON delimiters, or path separators.
    val TRIGGER_PATTERN: Regex = Regex("[A-Za-z0-9_:#<>|.-]{1,64}")

    fun promptContainsTrigger(prompt: String, trigger: String): Boolean =
        triggerRegex(trigger).containsMatchIn(prompt)

    fun missingPromptTriggers(
        prompt: String,
        artifacts: List<TextualInversionArtifact>
    ): List<String> = missingPromptTriggers(listOf(prompt), artifacts)

    fun missingPromptTriggers(
        prompts: List<String>,
        artifacts: List<TextualInversionArtifact>
    ): List<String> {
        artifacts.forEach { artifact ->
            require(TRIGGER_PATTERN.matches(artifact.trigger)) {
                "Textual inversion trigger is invalid."
            }
        }
        val consumed = mutableSetOf<Int>()
        prompts.forEach { prompt ->
            var cursor = 0
            while (cursor < prompt.length) {
                var matchOffset = -1
                var matchIndex = -1
                var matchLength = 0
                for (offset in cursor until prompt.length) {
                    artifacts.forEachIndexed { index, artifact ->
                        val trigger = artifact.trigger
                        if (triggerMatchesAt(prompt, offset, trigger) &&
                            (matchOffset < 0 || offset < matchOffset ||
                                (offset == matchOffset && trigger.length > matchLength))
                        ) {
                            matchOffset = offset
                            matchIndex = index
                            matchLength = trigger.length
                        }
                    }
                    if (matchOffset >= 0) break
                }
                if (matchIndex < 0) break
                consumed += matchIndex
                cursor = matchOffset + matchLength
            }
        }
        return artifacts.mapIndexedNotNull { index, artifact ->
            artifact.trigger.takeIf { index !in consumed }
        }
    }

    private fun triggerRegex(trigger: String): Regex {
        require(TRIGGER_PATTERN.matches(trigger)) { "Textual inversion trigger is invalid." }
        val startsWithWord = trigger.first().isAsciiTriggerWordCharacter()
        val endsWithWord = trigger.last().isAsciiTriggerWordCharacter()
        return Regex(
            buildString {
                if (startsWithWord) append("(?<![A-Za-z0-9_])")
                append(Regex.escape(trigger))
                if (endsWithWord) append("(?![A-Za-z0-9_])")
            },
            RegexOption.IGNORE_CASE
        )
    }

    private fun triggerMatchesAt(text: String, offset: Int, trigger: String): Boolean {
        if (offset < 0 || offset > text.length || trigger.length > text.length - offset) return false
        trigger.indices.forEach { index ->
            if (!text[offset + index].asciiEqualsIgnoreCase(trigger[index])) return false
        }
        if (trigger.first().isAsciiTriggerWordCharacter() && offset > 0 &&
            text[offset - 1].isAsciiTriggerWordCharacter()
        ) {
            return false
        }
        val end = offset + trigger.length
        return !trigger.last().isAsciiTriggerWordCharacter() ||
            end == text.length || !text[end].isAsciiTriggerWordCharacter()
    }

    private fun Char.asciiEqualsIgnoreCase(other: Char): Boolean {
        fun Char.asciiLowercase(): Char = if (this in 'A'..'Z') this + ('a' - 'A') else this
        return asciiLowercase() == other.asciiLowercase()
    }

    private fun Char.isAsciiTriggerWordCharacter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun validateNativeCapability(
        runtime: String,
        graphSupportsTextualInversion: Boolean,
        selection: TextualInversionSelection?
    ) {
        if (selection == null || selection.bindings.isEmpty()) return
        val requestedRuntime = TextualInversionRuntime.entries.firstOrNull { it.wireName == runtime }
        require(requestedRuntime != null && selection.bindings.all { it.runtime == requestedRuntime }) {
            "Textual inversion is not executable on the selected runtime."
        }
        require(graphSupportsTextualInversion) {
            "The resolved native graph does not expose a textual-inversion input path."
        }
        if (requestedRuntime == TextualInversionRuntime.QNN_HTP ||
            requestedRuntime == TextualInversionRuntime.MNN_DIFFUSION
        ) {
            require(selection.bindings.all {
                it.artifact.format == TextualInversionFormat.SAFETENSORS
            }) {
                "QNN/MNN textual inversion currently requires safetensors artifacts."
            }
        }
    }

    fun TextualInversionSelection?.toNativeJsonOrNull(rootPath: String): JSONObject? =
        this?.takeIf { it.bindings.isNotEmpty() }?.toNativeJson(rootPath)

    fun File.isOwnedTextualInversionPath(root: File): Boolean = try {
        canonicalFile.parentFile == root.canonicalFile &&
            name == name.replace("/", "") &&
            isFile && !isSymbolicLink
    } catch (_: Exception) {
        false
    }

    private val File.isSymbolicLink: Boolean
        get() = try {
            java.nio.file.Files.isSymbolicLink(toPath())
        } catch (_: Exception) {
            true
        }
}

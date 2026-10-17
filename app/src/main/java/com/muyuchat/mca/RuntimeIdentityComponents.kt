package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.modelstore.GgufMetadataReader
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.sparseMoeInfo
import com.muyuchat.mca.RuntimeIdentityFactory.EMBEDDED_CONFIG_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.EMBEDDED_TEMPLATE_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.EMBEDDED_TOKENIZER_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.MAX_TEMPLATE_CONFIG_BYTES
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale


    internal enum class ComponentKind(val domain: String) {
        TOKENIZER("tokenizer"),
        TEMPLATE("template"),
        CONFIG("config")
    }

    internal fun componentFingerprint(
        root: File,
        artifactFingerprint: String,
        kind: ComponentKind,
        files: List<File>,
        session: HashSession
    ): String {
        if (root.isFile) {
            val embedded = session.textDigest(
                when (kind) {
                    ComponentKind.TOKENIZER -> EMBEDDED_TOKENIZER_VERSION
                    ComponentKind.TEMPLATE -> EMBEDDED_TEMPLATE_VERSION
                    ComponentKind.CONFIG -> EMBEDDED_CONFIG_VERSION
                },
                listOf(artifactFingerprint)
            )
            if (files.isEmpty()) return embedded
            return session.textDigest(
                "${kind.domain}-with-sidecars-v1",
                listOf(embedded, session.entriesDigest(kind.domain, files.map { relativeSidecarPath(it) to session.fileSha256(it) }))
            )
        }
        return if (files.isEmpty()) "" else session.entriesDigest(
            kind.domain,
            files.map { relativePath(root, it) to session.fileSha256(it) }
        )
    }

    internal fun componentFiles(root: File, kind: ComponentKind, bundleFiles: List<File>): List<File> {
        val candidates = if (root.isDirectory) {
            bundleFiles
        } else {
            val parent = root.parentFile ?: return emptyList()
            val stem = root.name.substringBeforeLast('.', root.name).lowercase(Locale.US)
            parent.listFiles().orEmpty()
                .filter { it.isFile && it != root }
                .filter { file ->
                    val lower = file.name.lowercase(Locale.US)
                    val scoped = lower.startsWith("$stem.") || lower.startsWith("${stem}_")
                    scoped || lower in GENERIC_SIDECAR_NAMES
                }
        }
        return candidates
            .filter { file -> when (kind) {
                ComponentKind.TOKENIZER -> isTokenizerFile(file)
                ComponentKind.TEMPLATE -> isTemplateFile(file)
                ComponentKind.CONFIG -> isConfigFile(file)
            } }
            .distinctBy { canonicalFile(it).path }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    private fun isTokenizerFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return "tokenizer" in name ||
            name in setOf("vocab.json", "vocab.txt", "merges.txt", "spiece.model", "sentencepiece.model") ||
            name.endsWith(".model") && ("sentence" in name || "spiece" in name)
    }

    private fun isTemplateFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        if ("template" in name || name.endsWith(".jinja") || name.endsWith(".jinja2") ||
            name.endsWith(".tmpl") || name.endsWith(".prompt")) return true
        // Standard HuggingFace/MNN config files may carry an inline
        // chat_template. Inspect only bounded metadata files so an unrelated
        // execution-config edit does not invalidate the template fingerprint.
        return name in TEMPLATE_CONFIG_NAMES &&
            file.length() <= MAX_TEMPLATE_CONFIG_BYTES &&
            hasInlineTemplateSignal(file)
    }

    private fun hasInlineTemplateSignal(file: File): Boolean = runCatching {
        val text = file.readText(StandardCharsets.UTF_8).lowercase(Locale.US)
        "chat_template" in text || "chattemplate" in text ||
            "prompt_template" in text || ("jinja" in text && "template" in text)
    }.getOrDefault(false)

    private fun isConfigFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        // Tokenizer assets are a separate identity component. In particular,
        // tokenizer_config.json must not make a tokenizer edit look like a
        // model execution-config edit.
        if (isTokenizerFile(file)) return false
        val extension = name.substringAfterLast('.', "")
        return "config" in name || "metadata" in name || "manifest" in name ||
            extension in CONFIG_EXTENSIONS
    }

    internal fun resolveProjector(root: File, rawPath: String?): File? {
        val raw = rawPath?.trim().orEmpty()
        if (raw.isBlank()) return null
        val candidate = File(raw)
        val candidates = if (candidate.isAbsolute) {
            listOf(candidate)
        } else {
            buildList {
                if (root.isDirectory) add(File(root, raw))
                root.parentFile?.let { add(File(it, raw)) }
            }
        }
        return candidates.asSequence()
            .map(::canonicalFile)
            .firstOrNull(File::isFile)
    }

    internal fun capabilities(
        model: ModelManifest,
        root: File,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        projectorFingerprint: String,
        tokenizerFingerprint: String,
        templateFingerprint: String,
        qairtAdmissionState: String,
        bundleFiles: List<File>
    ): Set<String> = buildSet {
        val sparseMoe = model.sparseMoeInfo()
        add("local_chat")
        add("text_generation")
        add("parameter_coordinator_v1")
        add("runtime:${runtime.backendId}")
        add("abi:${device.primaryAbi.normalizedOrUnknown()}")
        add("acceleration:${device.accelerationProfile.localChat.status.name.lowercase(Locale.US)}")
        if (tokenizerFingerprint.isNotBlank()) add("tokenizer")
        if (templateFingerprint.isNotBlank()) add("chat_template")
        if (projectorFingerprint.isNotBlank() || model.hasVisionProjector) add("projector")
        when (runtime) {
            LocalChatRuntime.LLAMA_CPP -> {
                add("llama_cpp_cpu")
                // GPU is a runtime candidate. Native backend registration and
                // actual non-CPU allocation evidence decide whether it is on.
                add("gpu_offload")
                // MTP is different: a filename is not evidence of an MTP
                // head. Only a positive GGUF nextn_predict_layers declaration
                // can make draft-MTP selectable; native load/smoke still owns
                // final acceptance and commit.
                if (model.declaresMtpHead()) add("draft_mtp")
                if (sparseMoe.isSparseMoe) {
                    add("sparse_moe")
                    val physicalRam = device.totalRamBytes.takeIf { it > 0L }
                        ?: device.advertisedRamBytes
                    val conservativeMemoryTier = physicalRam <= 0L ||
                        physicalRam <= MAX_SPARSE_MOE_16_GIB_TIER_BYTES
                    if (conservativeMemoryTier) {
                        add("sparse_moe_16gb_tier")
                        // This remains a narrowly scoped tuning profile, not
                        // an admission decision.  Native load still validates
                        // the actual quantization and graph.
                        if (model.sha256.equals(VERIFIED_QWEN36_MTP_SHA256, ignoreCase = true)) {
                            add("verified_q4_kv_cache")
                        }
                    }
                }
            }
            LocalChatRuntime.MNN_CPU -> {
                add("mnn_cpu")
                if (root.isDirectory && hasVisionSignal(bundleFiles)) add("mnn_vision_candidate")
            }
            LocalChatRuntime.GENIEX_LLAMA_CPP -> {
                add("geniex_llama_cpp")
                if (device.accelerationProfile.qnnRuntime.ready) add("qnn_htp_candidate")
                if (device.accelerationProfile.qnnRuntime.usableForSmoke) add("gpu_offload")
            }
            LocalChatRuntime.GENIEX_QAIRT -> {
                add("geniex_qairt")
                add("qairt_admission:$qairtAdmissionState")
                if (device.accelerationProfile.qnnRuntime.ready) add("qnn_htp_candidate")
            }
            LocalChatRuntime.LITERT_LM -> {
                add("litert_lm")
                add("litert_lm_cpu")
                // CPU/GPU/NPU are selectable runtime transports.  Actual
                // backend initialization and generation evidence remain the
                // authority for the active transport.
                add("gpu_offload")
                add("npu_candidate")
            }
        }
    }

    private fun hasVisionSignal(files: List<File>): Boolean = files.any { file ->
        val lower = file.name.lowercase(Locale.US)
        "vision" in lower || "visual" in lower || "processor" in lower || "mmproj" in lower
    }

    private fun ModelManifest.declaresMtpHead(): Boolean {
        val modelFile = File(path)
        if (!modelFile.isFile) return false
        return runCatching { GgufMetadataReader.read(modelFile) }
            .getOrNull()
            ?.nextnPredictLayers
            ?.let { it > 0 }
            ?: false
    }

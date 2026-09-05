package com.muyuchat.mca

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/** Bounded local diagnostics shared by disposable native-load workers. */
internal object LocalDiagnosticRedactor {
    private const val MAX_DETAIL_CHARS = 2_000

    private val localPathPattern = Regex(
        "(?i)(?<![A-Za-z0-9:])(?:[A-Z]:[\\\\/]|/(?:data|storage|sdcard|mnt|home|Users|var|tmp|system|vendor|product|apex)(?:/|\\b))[^\\s,;\\\"']*"
    )
    private val localUriPattern = Regex("(?i)(?:file|content)://[^\\s,;\\\"']+")
    private val bearerPattern = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secretPattern = Regex(
        "(?i)(?:sk-[A-Za-z0-9_-]{12,}|AIza[A-Za-z0-9_-]{20,}|" +
            "(?:api[_-]?key|access[_-]?token|authorization|secret|password)" +
            "\\s*[:=]\\s*[^\\s,;]+)"
    )
    private val promptAssignmentPattern = Regex(
        """(?is)\b(system[\s_-]*prompt|prompt|messages?|conversation|content)\b\s*[:=]\s*(?:\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[^\r\n;,]+)"""
    )

    fun sanitize(
        value: String?,
        redactedLiterals: Iterable<String> = emptyList()
    ): String {
        var sanitized = value.orEmpty()
        redactedLiterals
            .map(String::trim)
            .filter { it.length >= 4 }
            .distinct()
            .sortedByDescending(String::length)
            .forEach { literal -> sanitized = sanitized.replace(literal, "<prompt-redacted>") }
        sanitized = sanitized
            .replace(promptAssignmentPattern) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
            .replace(localPathPattern, "<path>")
            .replace(localUriPattern, "<uri>")
            .replace(bearerPattern, "Bearer <redacted>")
            .replace(secretPattern, "<redacted>")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return sanitized.take(MAX_DETAIL_CHARS)
    }

    fun promptFingerprint(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }
}

internal data class IsolatedNativeFailureDiagnostic(
    val code: String,
    val message: String,
    val stage: String
)

/**
 * Converts worker-local failures into stable, prompt-free Binder diagnostics.
 * Device/profile discovery never participates in this decision; only the
 * concrete preflight, native load/context, smoke, and teardown result does.
 */
internal object IsolatedNativeFailureDiagnostics {
    fun watchdog(
        stage: String,
        timeoutMs: Long,
        code: String = "worker_watchdog_timeout",
        operationLabel: String? = null
    ): IsolatedNativeFailureDiagnostic {
        val canonicalStage = canonicalStage(stage)
        return IsolatedNativeFailureDiagnostic(
            code = code.takeIf { it.matches(Regex("[a-z0-9_]{1,96}")) }
                ?: "worker_watchdog_timeout",
            message = buildString {
                operationLabel
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append(it).append(' ') }
                append("隔离 native worker 在 ")
                append(canonicalStage)
                append(" 阶段超过 ")
                append(timeoutMs.coerceAtLeast(0L))
                append("ms，已由 watchdog 回收。")
            },
            stage = canonicalStage
        )
    }

    fun timeout(stage: String): IsolatedNativeFailureDiagnostic {
        val canonicalStage = canonicalStage(stage)
        return IsolatedNativeFailureDiagnostic(
            code = when (canonicalStage) {
                "load" -> "native_load_timeout"
                "context" -> "context_creation_timeout"
                "smoke" -> "smoke_execution_timeout"
                "unload" -> "native_unload_timeout"
                else -> "native_worker_timeout"
            },
            message = "隔离 native worker 在 $canonicalStage 阶段超时，未发布任何成功证据。",
            stage = canonicalStage
        )
    }

    fun classify(
        error: Throwable,
        stage: String,
        nativeStatsJson: String = "{}"
    ): IsolatedNativeFailureDiagnostic {
        val canonicalStage = canonicalStage(stage)
        val rawDetail = throwableDetail(error)
        val detail = LocalDiagnosticRedactor.sanitize(rawDetail)
        val lower = rawDetail.lowercase(Locale.US)
        val loadFailure = LocalModelLoadFailureClassifier.classify(rawDetail, nativeStatsJson)
        val code = when {
            error is OutOfMemoryError || "out of memory" in lower || "std::bad_alloc" in lower ->
                "out_of_memory"

            listOf("sigsegv", "sigabrt", "fatal signal", "native fatal").any(lower::contains) ->
                "native_fatal_signal"

            listOf("worker crashed", "process crashed", "binder died", "process exited").any(lower::contains) ->
                "worker_process_crashed"

            canonicalStage == "smoke" -> "smoke_execution_failed"
            canonicalStage == "unload" -> "native_unload_failed"
            canonicalStage == "context" -> "context_creation_failed"
            canonicalStage == "load" || canonicalStage == "preflight" -> loadFailureCode(loadFailure.kind)
            else -> "native_worker_failed"
        }
        val summary = when (code) {
            "out_of_memory" -> "隔离 native worker 内存不足。"
            "native_fatal_signal" -> "隔离 native worker 发生 native fatal signal。"
            "worker_process_crashed" -> "隔离 native worker 在返回完整证据前退出。"
            "smoke_execution_failed" -> "模型已进入实际 smoke，但执行校验失败。"
            "native_unload_failed" -> "隔离 native worker 未能干净销毁 native handle。"
            "context_creation_failed" -> "模型文件已读取，但执行 context 创建失败。"
            else -> loadFailure.userMessage
        }
        return IsolatedNativeFailureDiagnostic(
            code = code,
            message = if (detail.isBlank()) summary else "$summary 诊断：$detail",
            stage = canonicalStage
        )
    }

    fun canonicalStage(value: String): String {
        val stage = value.trim().lowercase(Locale.US)
        return when {
            stage in setOf("journal", "request", "validation", "validate", "preflight") -> "preflight"
            "load" in stage || "npu_ready" in stage -> "load"
            "context" in stage -> "context"
            stage in setOf("minimum_text", "batch_kv", "mtp", "long_context", "smoke", "decode", "prefill") ||
                "canary" in stage || "generate" in stage -> "smoke"
            "unload" in stage || "destroy" in stage -> "unload"
            else -> "worker"
        }
    }

    private fun throwableDetail(error: Throwable): String = generateSequence(error) { it.cause }
        .take(6)
        .map { cause -> cause.message?.trim().orEmpty().ifBlank { cause::class.java.simpleName } }
        .distinct()
        .joinToString("; ")

    private fun loadFailureCode(kind: LocalModelLoadFailureKind): String = when (kind) {
        LocalModelLoadFailureKind.FILE_UNREADABLE -> "file_unreadable"
        LocalModelLoadFailureKind.FILE_INTEGRITY -> "file_integrity_failed"
        LocalModelLoadFailureKind.GGUF_METADATA_OR_ARCHITECTURE_INVALID ->
            "gguf_metadata_or_architecture_invalid"
        LocalModelLoadFailureKind.NON_AUTOREGRESSIVE_CHAT -> "non_autoregressive_chat_model"
        LocalModelLoadFailureKind.UNSUPPORTED_QUANTIZATION_OR_OPERATION ->
            "unsupported_quantization_or_operation"
        LocalModelLoadFailureKind.CONTEXT_CREATION_FAILED -> "context_creation_failed"
        LocalModelLoadFailureKind.MEMORY_PRESSURE -> "out_of_memory"
        LocalModelLoadFailureKind.SMOKE_EXECUTION_FAILED -> "smoke_execution_failed"
        LocalModelLoadFailureKind.TOKENIZER_OR_TEMPLATE_INVALID -> "tokenizer_or_template_invalid"
        LocalModelLoadFailureKind.WORKER_TIMEOUT -> "native_worker_timeout"
        LocalModelLoadFailureKind.WORKER_PROCESS_CRASH -> "worker_process_crashed"
        LocalModelLoadFailureKind.BACKEND_UNAVAILABLE -> "backend_unavailable"
        LocalModelLoadFailureKind.UNSUPPORTED_RUNTIME_CONFIG -> "runtime_config_unsupported"
        LocalModelLoadFailureKind.BUNDLE_INCOMPLETE -> "bundle_incomplete"
        LocalModelLoadFailureKind.LOAD_SIGNATURE_MISMATCH -> "load_signature_mismatch"
        LocalModelLoadFailureKind.NATIVE_LOAD_FAILURE -> "native_load_failed"
    }
}

/**
 * The last durable native-worker boundary.  This deliberately contains no
 * request text, model path, URI, error detail, or arbitrary parameter value.
 * It is shared through app-private storage rather than Binder, so it remains
 * readable after the isolated process has aborted or been killed by watchdog.
 */
internal data class LocalChatWorkerStageDiagnostic(
    val stage: String,
    val state: String,
    val runtime: String?,
    val modelFingerprint: String?,
    val parameterSummary: Map<String, Any>,
    val workerPid: Int,
    val pssKb: Long,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val elapsedMs: Long,
    val failureCode: String?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", LocalChatWorkerStageJournal.VERSION)
        .put("stage", stage)
        .put("state", state)
        .put("runtime", runtime)
        .put("modelFingerprint", modelFingerprint)
        .put("parameters", JSONObject(parameterSummary))
        .put("workerPid", workerPid)
        .put("pssKb", pssKb)
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put("elapsedMs", elapsedMs)
        .put("failureCode", failureCode)

    fun compactDescription(): String = buildString {
        append("stage=").append(stage)
        append(", state=").append(state)
        runtime?.let { append(", runtime=").append(it) }
        modelFingerprint?.let { append(", model=").append(it.take(MODEL_FINGERPRINT_PREVIEW_CHARS)) }
        append(", pssKb=").append(pssKb)
        append(", elapsedMs=").append(elapsedMs)
        failureCode?.let { append(", code=").append(it) }
    }

    private companion object {
        private const val MODEL_FINGERPRINT_PREVIEW_CHARS = 16
    }
}

/**
 * Bounded, atomically-published worker journal.  It is intentionally a single
 * latest-state file: writing a prompt-bearing trace or an unbounded history
 * would turn a crash diagnostic into an additional privacy risk.
 */
internal class LocalChatWorkerStageJournal private constructor(
    private val file: File,
    private val now: () -> Long
) {
    private val lock = Any()
    private var lastSnapshot: LocalChatWorkerStageDiagnostic? = null

    fun recordWorkerStarted(workerPid: Int, pssKb: Long) {
        record(
            stage = STAGE_WORKER,
            state = STATE_ACTIVE,
            workerPid = workerPid,
            pssKb = pssKb,
            failureCode = null,
            resetContext = true
        )
    }

    fun recordStarted(
        stage: String,
        runtime: String?,
        modelFingerprint: String?,
        parameterSummary: Map<String, Any>,
        workerPid: Int,
        pssKb: Long
    ) {
        record(
            stage = stage,
            state = STATE_ACTIVE,
            runtime = runtime,
            modelFingerprint = modelFingerprint,
            parameterSummary = parameterSummary,
            workerPid = workerPid,
            pssKb = pssKb,
            failureCode = null
        )
    }

    fun recordCompleted(
        stage: String,
        runtime: String?,
        modelFingerprint: String?,
        parameterSummary: Map<String, Any>,
        workerPid: Int,
        pssKb: Long
    ) {
        record(
            stage = stage,
            state = STATE_COMPLETED,
            runtime = runtime,
            modelFingerprint = modelFingerprint,
            parameterSummary = parameterSummary,
            workerPid = workerPid,
            pssKb = pssKb,
            failureCode = null
        )
    }

    fun recordFailure(
        stage: String,
        runtime: String?,
        modelFingerprint: String?,
        parameterSummary: Map<String, Any>,
        workerPid: Int,
        pssKb: Long,
        failureCode: String
    ) {
        record(
            stage = stage,
            state = STATE_FAILED,
            runtime = runtime,
            modelFingerprint = modelFingerprint,
            parameterSummary = parameterSummary,
            workerPid = workerPid,
            pssKb = pssKb,
            failureCode = failureCode
        )
    }

    fun read(): LocalChatWorkerStageDiagnostic? = synchronized(lock) {
        // The main process holds a separate journal instance from the worker;
        // refresh from disk so a later worker crash cannot report an old stage.
        readFromDisk()?.also { lastSnapshot = it } ?: lastSnapshot
    }

    private fun record(
        stage: String,
        state: String,
        runtime: String? = null,
        modelFingerprint: String? = null,
        parameterSummary: Map<String, Any> = emptyMap(),
        workerPid: Int,
        pssKb: Long,
        failureCode: String?,
        resetContext: Boolean = false
    ) = synchronized(lock) {
        val previous = lastSnapshot ?: readFromDisk()
        val timestamp = now().coerceAtLeast(0L)
        val canonicalStage = canonicalStage(stage)
        val startedAt = if (!resetContext &&
            previous?.stage == canonicalStage && previous.state == STATE_ACTIVE
        ) {
            previous.startedAtEpochMs
        } else {
            timestamp
        }
        val snapshot = LocalChatWorkerStageDiagnostic(
            stage = canonicalStage,
            state = canonicalState(state),
            runtime = if (resetContext) null else {
                runtime?.takeIf(::isSafeRuntime) ?: previous?.runtime
            },
            modelFingerprint = if (resetContext) null else {
                modelFingerprint?.takeIf(::isFingerprint) ?: previous?.modelFingerprint
            },
            parameterSummary = if (resetContext) emptyMap() else {
                sanitizeParameterSummary(parameterSummary.ifEmpty { previous?.parameterSummary.orEmpty() })
            },
            workerPid = workerPid.coerceAtLeast(0),
            pssKb = pssKb.coerceAtLeast(0L),
            startedAtEpochMs = startedAt,
            updatedAtEpochMs = timestamp,
            elapsedMs = (timestamp - startedAt).coerceAtLeast(0L),
            failureCode = failureCode?.takeIf(::isFailureCode)
        )
        if (writeToDisk(snapshot)) lastSnapshot = snapshot
    }

    private fun readFromDisk(): LocalChatWorkerStageDiagnostic? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_JOURNAL_BYTES) return@runCatching null
        val payload = FileInputStream(file).use(::readBoundedUtf8) ?: return@runCatching null
        fromJson(JSONObject(payload))
    }.getOrNull()

    private fun writeToDisk(snapshot: LocalChatWorkerStageDiagnostic): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        if (!parent.isDirectory) return false
        val temporary = File(parent, "${file.name}.${Thread.currentThread().id}.tmp")
        return runCatching {
            FileOutputStream(temporary).use { output ->
                output.write(strictJsonForPersistence(snapshot.toJson()).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Throwable) {
                // Android's private ext4 storage supports an atomic rename.
                // Some host filesystems reject atomic replacement of an
                // existing file, so keep a replace-only fallback for tests and
                // older vendor filesystems.
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        }.getOrElse {
            runCatching { temporary.delete() }
            false
        }
    }

    private fun fromJson(root: JSONObject): LocalChatWorkerStageDiagnostic? {
        if (root.optInt("version", -1) != VERSION) return null
        val stage = root.optString("stage")
        val state = root.optString("state")
        val runtime = root.optString("runtime").takeIf(String::isNotBlank)
        val modelFingerprint = root.optString("modelFingerprint").takeIf(String::isNotBlank)
        val workerPid = root.optInt("workerPid", -1)
        val pssKb = root.optLong("pssKb", -1L)
        val startedAt = root.optLong("startedAtEpochMs", -1L)
        val updatedAt = root.optLong("updatedAtEpochMs", -1L)
        val elapsed = root.optLong("elapsedMs", -1L)
        val failureCode = root.optString("failureCode").takeIf(String::isNotBlank)
        if (stage !in STAGES || state !in STATES ||
            runtime != null && !isSafeRuntime(runtime) ||
            modelFingerprint != null && !isFingerprint(modelFingerprint) ||
            workerPid < 0 || pssKb < 0L || startedAt < 0L || updatedAt < startedAt ||
            elapsed < 0L || failureCode != null && !isFailureCode(failureCode)
        ) {
            return null
        }
        val parameters = root.optJSONObject("parameters") ?: JSONObject()
        return LocalChatWorkerStageDiagnostic(
            stage = stage,
            state = state,
            runtime = runtime,
            modelFingerprint = modelFingerprint,
            parameterSummary = readParameterSummary(parameters) ?: return null,
            workerPid = workerPid,
            pssKb = pssKb,
            startedAtEpochMs = startedAt,
            updatedAtEpochMs = updatedAt,
            elapsedMs = elapsed,
            failureCode = failureCode
        )
    }

    private fun readBoundedUtf8(input: FileInputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) return null
            total += count
            if (total > MAX_JOURNAL_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    companion object {
        const val VERSION = 1
        private const val JOURNAL_DIRECTORY = "local_chat_worker_diagnostics"
        private const val JOURNAL_FILE = "worker-stage.json"
        private const val MAX_JOURNAL_BYTES = 32L * 1024L
        private val STAGES = setOf(
            STAGE_WORKER,
            "preflight",
            "load",
            "context",
            "prefill",
            "decode",
            "unload"
        )
        private val STATES = setOf(STATE_ACTIVE, STATE_COMPLETED, STATE_FAILED)
        private val SAFE_PARAMETER_KEYS = linkedSetOf(
            "n_ctx",
            "n_batch",
            "n_ubatch",
            "n_threads",
            "n_threads_batch",
            "n_gpu_layers",
            "main_gpu",
            "n_predict",
            "max_tokens",
            "cache_reuse",
            "temperature",
            "top_k",
            "top_p",
            "min_p",
            "repeat_penalty",
            "repetition_penalty",
            "presence_penalty",
            "frequency_penalty",
            "seed",
            "mmap",
            "mlock",
            "offload_kqv",
            "op_offload"
        )
        private val FAILURE_CODE_PATTERN = Regex("[a-z0-9_]{1,96}")
        private val RUNTIME_PATTERN = Regex("[A-Z_]{1,64}")
        private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
        private const val STAGE_WORKER = "worker"
        private const val STATE_ACTIVE = "active"
        private const val STATE_COMPLETED = "completed"
        private const val STATE_FAILED = "failed"

        fun forContext(context: Context): LocalChatWorkerStageJournal = LocalChatWorkerStageJournal(
            file = File(File(context.filesDir, JOURNAL_DIRECTORY), JOURNAL_FILE),
            now = System::currentTimeMillis
        )

        internal fun forFile(
            file: File,
            now: () -> Long = System::currentTimeMillis
        ): LocalChatWorkerStageJournal = LocalChatWorkerStageJournal(file, now)

        /**
         * Stable-enough local artifact identity without streaming a multi-GB
         * model during a failure path. The raw path is hash input only and is
         * never put in the journal or Binder diagnostic.
         */
        fun modelFingerprint(modelPath: String): String {
            val model = File(modelPath)
            val identity = runCatching {
                "${model.canonicalPath}|${model.length().coerceAtLeast(0L)}|${model.lastModified().coerceAtLeast(0L)}"
            }.getOrElse { "unknown|0|0" }
            return LocalDiagnosticRedactor.promptFingerprint(identity)
        }

        fun parameterSummary(paramsJson: String): Map<String, Any> = runCatching {
            val root = JSONObject(paramsJson)
            SAFE_PARAMETER_KEYS.mapNotNull { key ->
                val value = root.opt(key)
                when (value) {
                    is Boolean -> key to value
                    is Number -> value.takeIf { it.toDouble().isFinite() }?.let { key to it }
                    else -> null
                }
            }.toMap(linkedMapOf())
        }.getOrDefault(emptyMap())

        fun canonicalStage(stage: String): String = when {
            stage.contains("context", ignoreCase = true) -> "context"
            stage.contains("prefill", ignoreCase = true) || stage.startsWith("begin", ignoreCase = true) ->
                "prefill"
            stage.contains("decode", ignoreCase = true) || stage.contains("generate", ignoreCase = true) ->
                "decode"
            stage.contains("unload", ignoreCase = true) || stage.contains("shutdown", ignoreCase = true) ->
                "unload"
            stage.contains("load", ignoreCase = true) || stage.contains("init", ignoreCase = true) -> "load"
            stage.contains("preflight", ignoreCase = true) || stage.contains("request", ignoreCase = true) ->
                "preflight"
            else -> STAGE_WORKER
        }

        private fun canonicalState(state: String): String = state.takeIf { it in STATES } ?: STATE_ACTIVE

        private fun sanitizeParameterSummary(value: Map<String, Any>): Map<String, Any> =
            value.entries
                .asSequence()
                .filter { (key, candidate) -> key in SAFE_PARAMETER_KEYS && isSafeParameterValue(candidate) }
                .sortedBy { it.key }
                .associateTo(linkedMapOf()) { it.key to it.value }

        private fun readParameterSummary(root: JSONObject): Map<String, Any>? {
            val result = linkedMapOf<String, Any>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in SAFE_PARAMETER_KEYS) return null
                val value = root.opt(key)
                if (!isSafeParameterValue(value)) return null
                result[key] = value
            }
            return sanitizeParameterSummary(result)
        }

        private fun isSafeParameterValue(value: Any): Boolean = when (value) {
            is Boolean -> true
            is Number -> value.toDouble().isFinite()
            else -> false
        }

        private fun isSafeRuntime(value: String): Boolean = RUNTIME_PATTERN.matches(value)

        private fun isFingerprint(value: String): Boolean = FINGERPRINT_PATTERN.matches(value)

        private fun isFailureCode(value: String): Boolean = FAILURE_CODE_PATTERN.matches(value)
    }
}

package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.maintenance.DshHomeLayout
import com.muyuchat.core.dsh.maintenance.DshMaintenanceIo
import com.muyuchat.core.dsh.maintenance.DshNodeHelperResult
import com.muyuchat.core.dsh.maintenance.DshNodeHelperRunner
import com.muyuchat.core.dsh.maintenance.DshNodeSessionCodec
import com.muyuchat.core.dsh.maintenance.DshSessionCodec
import com.muyuchat.core.dsh.maintenance.DshSessionHeaderProof
import com.muyuchat.core.dsh.maintenance.DshSessionInventory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App-side construction of the official session codec (D21).
 *
 * The helper script ships as a plain (non-executable) asset and is copied into the app-private
 * maintenance tree; it is executed by the payload's Node from `nativeLibraryDir`, the same
 * PackageManager-managed location the runtime host already executes (direct exec first, then the
 * system linker, exactly like [com.muyuchat.core.dsh.runtime.process.DshNodeExecStrategy]).
 *
 * The bridge returns a codec only after it decoded a **real** session header in the managed home.
 * Until then it keeps returning null, so the maintenance owner reports "repair unavailable" and
 * the self-check keeps SESSION_READER red instead of advertising a decoder it cannot prove.
 */
internal class DshSessionV4NodeBridge(
    private val dataRoot: File,
    private val nodeExecutable: () -> File?,
    private val helperSource: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val workDir = File(dataRoot, "maintenance/codec/work")
    private val helperFile = File(dataRoot, "maintenance/codec/session-v4-codec.mjs")

    @Volatile
    private var cached: DshSessionCodec? = null

    @Volatile
    private var lastNegativeAtMillis = 0L

    @Volatile
    private var lastProof: DshSessionHeaderProof = DshSessionHeaderProof.NOT_VERIFIED

    /** Latest proof for diagnostics; never treated as success without [DshSessionHeaderProof.verified]. */
    fun lastProof(): DshSessionHeaderProof = lastProof

    /** The proven codec, or null while it cannot decode a real header. */
    fun codec(homeDir: File): DshSessionCodec? {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val now = clock()
            if (lastNegativeAtMillis != 0L && now - lastNegativeAtMillis < NEGATIVE_CACHE_MILLIS) return null
            val built = build(homeDir)
            if (built == null) lastNegativeAtMillis = now
            cached = built
            return built
        }
    }

    private fun build(homeDir: File): DshSessionCodec? {
        val node = nodeExecutable()?.takeIf { it.isFile } ?: run {
            lastProof = DshSessionHeaderProof(false, "the packaged Node executable is unavailable")
            return null
        }
        val source = helperSource()?.takeIf { it.contains("session.v4.jsonl.zstd") } ?: run {
            lastProof = DshSessionHeaderProof(false, "the session codec helper asset is unavailable")
            return null
        }
        val helperReady = runCatching {
            if (!helperFile.isFile || DshMaintenanceIo.readText(helperFile) != source) {
                DshMaintenanceIo.writeDurably(helperFile, source)
            }
            helperFile.isFile
        }.getOrDefault(false)
        if (!helperReady) {
            lastProof = DshSessionHeaderProof(false, "the session codec helper could not be staged")
            return null
        }
        val runtimeLib = File(dataRoot, "payload/runtime/lib")
        val runner = DshNodeHelperRunner { helper, args, workDir, timeoutMillis ->
            runHelper(node, helper, args, workDir, timeoutMillis, runtimeLib)
        }
        val codec = DshNodeSessionCodec(
            runner = runner,
            helper = helperFile,
            workDirectory = workDir,
            timeoutMillis = HELPER_TIMEOUT_MILLIS
        )
        val proof = proveOnRealSession(codec, homeDir)
        lastProof = proof
        return if (proof.verified) codec else null
    }

    /**
     * A codec without a real header decode is not wired. The inventory is format-agnostic, so a
     * legacy or future session name is still attempted; nothing is inferred from the directory.
     */
    private fun proveOnRealSession(codec: DshSessionCodec, homeDir: File): DshSessionHeaderProof {
        val sessions = runCatching { DshSessionInventory(DshHomeLayout(homeDir)).list() }.getOrNull()
            ?: return DshSessionHeaderProof(false, "the managed session tree is unreadable")
        if (sessions.isEmpty()) return DshSessionHeaderProof.NO_SESSIONS
        var last = DshSessionHeaderProof.NO_SESSIONS
        for (session in sessions.take(MAX_HEADER_PROBES)) {
            if (session.bytes <= 0L) continue
            val proof = codec.inspectHeader(session.file)
            if (proof.verified) return proof
            last = proof
        }
        return last
    }

    private fun runHelper(
        node: File,
        helper: File,
        args: List<String>,
        workDir: File,
        timeoutMillis: Long,
        runtimeLib: File
    ): DshNodeHelperResult {
        if (!workDir.isDirectory && !workDir.mkdirs() && !workDir.isDirectory) {
            return DshNodeHelperResult(-1, "codec work directory is unavailable")
        }
        val environment = linkedMapOf(
            "HOME" to File(dataRoot, "home").absolutePath,
            "TMPDIR" to workDir.absolutePath,
            "PATH" to "/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to listOf(runtimeLib.absolutePath, node.parentFile?.absolutePath.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(":"),
            "NODE_NO_WARNINGS" to "1"
        )
        val direct = execute(listOf(node.absolutePath) + listOf(helper.absolutePath) + args, environment, workDir, timeoutMillis)
        if (direct.exitCode == 0 || direct.exitCode !in LINKER_FALLBACK_EXIT_CODES) return direct
        // Some devices deny direct exec of the native-library location at targetSdk 36; the system
        // linker is the documented workaround the runtime host already proves at startup.
        return execute(
            listOf(LINKER64, node.absolutePath, helper.absolutePath) + args,
            environment,
            workDir,
            timeoutMillis
        )
    }

    private fun execute(
        command: List<String>,
        environment: Map<String, String>,
        workDir: File,
        timeoutMillis: Long
    ): DshNodeHelperResult {
        val stdout = File(workDir, "helper.stdout")
        val stderr = File(workDir, "helper.stderr")
        return runCatching {
            val builder = ProcessBuilder(command)
            builder.directory(workDir)
            builder.environment().apply {
                clear()
                putAll(environment)
            }
            builder.redirectOutput(stdout)
            builder.redirectError(stderr)
            val process = builder.start()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                return DshNodeHelperResult(-1, "codec helper timed out")
            }
            val errorText = runCatching {
                if (stderr.isFile && stderr.length() in 1..MAX_STDERR_BYTES) {
                    stderr.readText(Charsets.UTF_8).take(MAX_STDERR_CHARS)
                } else {
                    ""
                }
            }.getOrDefault("")
            DshNodeHelperResult(process.exitValue(), errorText)
        }.getOrElse { failure ->
            DshNodeHelperResult(-1, "codec helper could not be started: ${failure.javaClass.simpleName}")
        }
    }

    private companion object {
        const val NEGATIVE_CACHE_MILLIS = 60_000L
        const val HELPER_TIMEOUT_MILLIS = 120_000L
        const val MAX_HEADER_PROBES = 5
        const val MAX_STDERR_BYTES = 64L * 1024L
        const val MAX_STDERR_CHARS = 500
        const val LINKER64 = "/system/bin/linker64"
        val LINKER_FALLBACK_EXIT_CODES = setOf(126, 127)
    }
}

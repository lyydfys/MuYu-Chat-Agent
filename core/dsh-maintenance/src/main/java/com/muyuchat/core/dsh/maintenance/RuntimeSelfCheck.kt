package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.json.JSONObject

/**
 * Real runtime observations for the D22b self-check (OPS-11).
 *
 * The probes parse the artifacts the managed host actually depends on and compare them with the
 * build's own manifest; they never answer "file exists". Values that cannot be observed while the
 * host is stopped (live gateway/bridge binding) are omitted instead of being reported as healthy,
 * so a stopped-host diagnostic never claims a live check it did not perform.
 *
 * This class is deliberately free of Android types: the app supplies the environment (payload
 * marker, packaged manifest values, native-library path, installed ABI, home marker, session
 * directory, live-state lambdas) and the probes stay JVM-testable.
 */
data class DshSelfCheckEnvironment(
    val payloadMarkerFile: File,
    val expectedArchiveSha256: String?,
    val expectedFileCount: Long?,
    val nativeLibrary: File?,
    val installedAbi: String?,
    val homeMarkerFile: File,
    val sessionsDir: File,
    val sessionCodecAvailable: Boolean,
    val runtimePhase: String,
    val gatewayReady: Boolean = false,
    val bridgeReady: Boolean = false,
    /**
     * Positive proof that the official session reader decoded a real header. A readable directory
     * is not that proof, so the default is an explicit refusal.
     */
    val sessionHeaderProof: DshSessionHeaderProof = DshSessionHeaderProof.NOT_VERIFIED
)

class DshRuntimeSelfCheckPort(
    private val environment: () -> DshSelfCheckEnvironment
) : DshSelfCheckPort {

    override fun run(): List<DshSelfCheckProbe> {
        val env = environment()
        val probes = ArrayList<DshSelfCheckProbe>(7)
        probes += payloadIntegrity(env)
        probes += nodeExecutable(env)
        probes += nativeAbi(env)
        probes += homeMarker(env)
        probes += sessionReader(env)
        // Live observations only when the host is actually running; a stopped host must not be
        // reported as a healthy binding.
        if (env.runtimePhase == "READY") {
            probes += DshSelfCheckProbe(
                DshSelfCheckCheck.GATEWAY_BINDING,
                env.gatewayReady,
                if (env.gatewayReady) "gateway listening" else "gateway endpoint is not running"
            )
            probes += DshSelfCheckProbe(
                DshSelfCheckCheck.BRIDGE,
                env.bridgeReady,
                if (env.bridgeReady) "capability bridge listening" else "capability bridge is not running"
            )
        }
        return probes
    }

    private fun payloadIntegrity(env: DshSelfCheckEnvironment): DshSelfCheckProbe {
        if (!env.payloadMarkerFile.isFile) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.PAYLOAD_INTEGRITY,
                false,
                "payload marker is missing"
            )
        }
        val marker = runCatching {
            JSONObject(env.payloadMarkerFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return DshSelfCheckProbe(
            DshSelfCheckCheck.PAYLOAD_INTEGRITY,
            false,
            "payload marker is not valid JSON"
        )
        val sha = marker.optString("archiveSha256", "")
        if (sha.length != 64 || sha.any { it !in "0123456789abcdefABCDEF" }) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.PAYLOAD_INTEGRITY,
                false,
                "payload marker has no archive hash"
            )
        }
        val fileCount = marker.optLong("fileCount", 0L)
        if (fileCount <= 0L) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.PAYLOAD_INTEGRITY,
                false,
                "payload marker has no file count"
            )
        }
        val expectedSha = env.expectedArchiveSha256
        if (expectedSha == null) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.PAYLOAD_INTEGRITY,
                false,
                "packaged payload manifest is unavailable; integrity could not be verified"
            )
        }
        if (!expectedSha.equals(sha, ignoreCase = true)) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.PAYLOAD_INTEGRITY,
                false,
                "payload archive hash does not match the packaged manifest"
            )
        }
        val expectedCount = env.expectedFileCount
        if (expectedCount != null && expectedCount > 0L && expectedCount != fileCount) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.PAYLOAD_INTEGRITY,
                false,
                "payload file count $fileCount does not match the packaged manifest"
            )
        }
        return DshSelfCheckProbe(
            DshSelfCheckCheck.PAYLOAD_INTEGRITY,
            true,
            "files=$fileCount hash=" + sha.take(12)
        )
    }

    private fun nodeExecutable(env: DshSelfCheckEnvironment): DshSelfCheckProbe {
        val node = env.nativeLibrary
            ?: return DshSelfCheckProbe(
                DshSelfCheckCheck.NODE_EXECUTABLE,
                false,
                "native library directory is unavailable"
            )
        if (!node.isFile || node.length() < MIN_NODE_BYTES) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.NODE_EXECUTABLE,
                false,
                "node executable is missing or too small"
            )
        }
        val header = runCatching {
            node.inputStream().use { input ->
                val head = ByteArray(20)
                if (input.read(head) != head.size) return@use null
                head
            }
        }.getOrNull()
        if (header == null || !isElf64Aarch64(header)) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.NODE_EXECUTABLE,
                false,
                "node file is not an AArch64 ELF executable"
            )
        }
        return DshSelfCheckProbe(DshSelfCheckCheck.NODE_EXECUTABLE, true, "node ELF bytes=${node.length()}")
    }

    private fun nativeAbi(env: DshSelfCheckEnvironment): DshSelfCheckProbe {
        val abi = env.installedAbi
        if (abi != "arm64-v8a") {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.NATIVE_ABI,
                false,
                "installed app ABI is " + (abi ?: "unknown") + ", expected arm64-v8a"
            )
        }
        val node = env.nativeLibrary
        if (node == null || !node.isFile) {
            return DshSelfCheckProbe(DshSelfCheckCheck.NATIVE_ABI, false, "arm64-v8a node library is absent")
        }
        return DshSelfCheckProbe(DshSelfCheckCheck.NATIVE_ABI, true, "abi=arm64-v8a")
    }

    private fun homeMarker(env: DshSelfCheckEnvironment): DshSelfCheckProbe {
        val marker = env.homeMarkerFile
        if (!marker.isFile) {
            return DshSelfCheckProbe(DshSelfCheckCheck.HOME_MARKER, false, "home seed marker is missing")
        }
        val json = runCatching { JSONObject(marker.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return DshSelfCheckProbe(DshSelfCheckCheck.HOME_MARKER, false, "home seed marker is not valid JSON")
        val markerVersion = json.optInt("markerVersion", 0)
        val phase = json.optString("phase", "")
        val templateVersion = json.optString("templateVersion", "")
        if (markerVersion <= 0 || phase != "complete" || templateVersion.isBlank()) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.HOME_MARKER,
                false,
                "home seed marker is incomplete (markerVersion/phase/templateVersion)"
            )
        }
        return DshSelfCheckProbe(DshSelfCheckCheck.HOME_MARKER, true, "home seed marker present")
    }

    private fun sessionReader(env: DshSelfCheckEnvironment): DshSelfCheckProbe {
        if (!env.sessionCodecAvailable) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.SESSION_READER,
                false,
                "official session codec is not wired in this build"
            )
        }
        if (!env.sessionsDir.isDirectory || !env.sessionsDir.canRead()) {
            return DshSelfCheckProbe(DshSelfCheckCheck.SESSION_READER, false, "session directory is unreadable")
        }
        if (!env.sessionHeaderProof.verified) {
            return DshSelfCheckProbe(
                DshSelfCheckCheck.SESSION_READER,
                false,
                "no real session header was decoded: " + env.sessionHeaderProof.detail.take(120)
            )
        }
        return DshSelfCheckProbe(
            DshSelfCheckCheck.SESSION_READER,
            true,
            "official session header decoded (" + env.sessionHeaderProof.detail.take(120) + ")"
        )
    }

    private fun isElf64Aarch64(header: ByteArray): Boolean {
        if (header.size < 20) return false
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return false
        }
        if (header[4] != 2.toByte()) return false // ELFCLASS64
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return machine == 0xB7 // EM_AARCH64
    }

    private companion object {
        const val MIN_NODE_BYTES = 1_000_000L
    }
}

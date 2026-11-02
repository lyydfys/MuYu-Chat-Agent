package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import java.nio.file.Files

/**
 * Test fixtures for the D24 trial generation.
 *
 * They materialise the *real* inputs the owner reads: the packaged Node library, the verified
 * payload tree (kernel entry + official profile template), the production home (optionally with a
 * committed session log), the staged closure candidate and the three isolated trial roots.
 */
internal object DshTrialTestFiles {

    /** A syntactically valid sha256 token: 64 lowercase hex characters. */
    val FINGERPRINT: String = "0123456789abcdef".repeat(4)

    data class Fixtures(
        val root: File,
        val productionHome: File,
        val trialHome: File,
        val trialWorkspace: File,
        val trialTmp: File,
        val candidate: File,
        val fingerprint: String,
        val payloadKernelEntry: File,
        /** The candidate's own executable payload; a trial must launch exactly this tree. */
        val candidatePayloadKernelEntry: File
    )

    fun newRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().absoluteFile

    fun materialize(root: File): Fixtures {
        val native = File(root, "libdshnode.so")
        native.writeBytes(byteArrayOf(1, 2, 3, 4))

        val payload = File(root, "payload")
        val kernel = File(payload, "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        kernel.parentFile.mkdirs()
        kernel.writeText("// official kernel entry\n")
        File(payload, "runtime/lib").mkdirs()
        File(payload, "runtime/bin").mkdirs()
        File(payload, "bin").mkdirs()
        val templateProfile = File(payload, "dshhome/profiles/web")
        templateProfile.mkdirs()
        File(templateProfile, "cordis.yml").writeText("plugins: []\n")
        File(templateProfile, "package.json").writeText("{}\n")
        File(templateProfile, "pnpm-workspace.yaml").writeText("packages: []\n")
        File(payload, "dshhome/settings.yaml").writeText("settings: {}\n")

        val productionHome = File(root, "home")
        productionHome.mkdirs()

        val closure = File(root, "closure/incoming/candidate-20261021-01")
        closure.mkdirs()
        File(closure, "candidate.json").writeText(
            """{"candidateId":"candidate-20261021-01","targetAbi":"arm64-v8a","dataSchemaVersion":5}"""
        )
        // The candidate's own payload tree, with different bytes than the installed payload so a
        // test can prove which tree the trial actually launched.
        val candidatePayload = File(closure, "payload")
        val candidateKernel = File(
            candidatePayload,
            "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
        )
        candidateKernel.parentFile.mkdirs()
        candidateKernel.writeText("// candidate kernel entry\n")
        File(candidatePayload, "runtime/lib").mkdirs()
        File(candidatePayload, "runtime/bin").mkdirs()
        File(candidatePayload, "bin").mkdirs()
        val candidateProfile = File(candidatePayload, "dshhome/profiles/web")
        candidateProfile.mkdirs()
        File(candidateProfile, "cordis.yml").writeText("plugins: []\n")
        File(candidateProfile, "package.json").writeText("{}\n")
        File(candidateProfile, "pnpm-workspace.yaml").writeText("packages: []\n")
        File(candidatePayload, "dshhome/settings.yaml").writeText("settings: {}\n")

        val trialRoot = File(root, "trial")
        trialRoot.mkdirs()
        val trialHome = File(trialRoot, "home")
        val trialWorkspace = File(trialRoot, "workspace")
        val trialTmp = File(trialRoot, "tmp")

        return Fixtures(
            root = root,
            productionHome = productionHome,
            trialHome = trialHome,
            trialWorkspace = trialWorkspace,
            trialTmp = trialTmp,
            candidate = closure,
            fingerprint = FINGERPRINT,
            payloadKernelEntry = kernel,
            candidatePayloadKernelEntry = candidateKernel
        )
    }

    /** One committed official session log with a structurally complete Zstandard frame. */
    fun writeSession(home: File, slug: String, sessionId: String, text: String = "{\"type\":\"session\",\"version\":4}\n") {
        val file = sessionFile(home, slug, sessionId)
        file.parentFile.mkdirs()
        file.writeBytes(zstdRawFrame(text))
    }

    fun sessionFile(home: File, slug: String, sessionId: String): File =
        File(home, "sessions/$slug/$sessionId/session.v4.jsonl.zstd")

    /**
     * A minimal, structurally valid Zstandard frame: magic, a frame-header descriptor without
     * content size/checksum, a window descriptor and one last raw block. The official scanner (and
     * the port in this module) accepts it; no zstd encoder is needed in the JVM test.
     */
    fun zstdRawFrame(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(4 + 1 + 1 + 3 + body.size)
        frame[0] = 0x28
        frame[1] = 0xB5.toByte()
        frame[2] = 0x2F
        frame[3] = 0xFD.toByte()
        frame[4] = 0x00 // descriptor: no content-size field, not single-segment, no checksum
        frame[5] = 0x00 // window descriptor
        val blockHeader = 1L or (body.size.toLong() shl 3) // last block, raw type, block size
        frame[6] = (blockHeader and 0xff).toByte()
        frame[7] = ((blockHeader ushr 8) and 0xff).toByte()
        frame[8] = ((blockHeader ushr 16) and 0xff).toByte()
        body.copyInto(frame, 9)
        return frame
    }

    /** A truncated frame: no complete frame remains, so no positive proof is possible. */
    fun tornFrame(text: String): ByteArray = zstdRawFrame(text).copyOf(5)

    /** The durable `trial/active.json` the app-side maintenance transaction owns. */
    fun writeJournal(
        dataRoot: File,
        phase: String,
        pid: Long,
        generation: Long,
        candidateRoot: File,
        homeRoot: File = File(dataRoot, "trial/home"),
        workspaceRoot: File = File(dataRoot, "trial/workspace"),
        tmpRoot: File = File(dataRoot, "trial/tmp")
    ) {
        val file = File(File(dataRoot, "trial"), "active.json")
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                append("{")
                append("\"phase\":\"").append(phase).append("\",")
                append("\"candidateId\":\"candidate-20261021-01\",")
                append("\"candidateFingerprint\":\"").append(FINGERPRINT).append("\",")
                append("\"candidateRoot\":\"").append(candidateRoot.absolutePath.replace("\\", "\\\\")).append("\",")
                append("\"homeRoot\":\"").append(homeRoot.absolutePath.replace("\\", "\\\\")).append("\",")
                append("\"workspaceRoot\":\"").append(workspaceRoot.absolutePath.replace("\\", "\\\\")).append("\",")
                append("\"tmpRoot\":\"").append(tmpRoot.absolutePath.replace("\\", "\\\\")).append("\",")
                append("\"pid\":").append(pid).append(",")
                append("\"generation\":").append(generation)
                append("}")
            }
        )
    }
}

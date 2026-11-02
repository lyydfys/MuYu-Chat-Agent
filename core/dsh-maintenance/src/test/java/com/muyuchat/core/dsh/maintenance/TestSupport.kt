package com.muyuchat.core.dsh.maintenance

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

/** Shared JVM-only fixtures. No Android stub is asserted anywhere in this module's tests. */
object TestSupport {

    fun tempRoot(prefix: String = "dsh-maintenance"): File =
        Files.createTempDirectory(prefix).toFile().canonicalFile

    fun deleteTree(file: File) {
        if (!file.exists()) return
        val children = file.listFiles()
        if (children != null) for (child in children) deleteTree(child)
        file.delete()
    }

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun requester(id: String = "op-1", owner: String = "test-owner") =
        DshMaintenanceRequester(owner, id, "unit test")

    fun stopped() = DshQuiescenceEvidence.hostStopped()

    fun healthyProbe() = DshMaintenanceRecoveryProbe { _, _, _ -> DshMaintenanceProbeResult.HEALTHY }

    fun unhealthyProbe() = DshMaintenanceRecoveryProbe { _, _, _ ->
        DshMaintenanceProbeResult.unhealthy("official reader rejected the data")
    }

    class FakeClock(var value: Long = 1_700_000_000_000L) : () -> Long {
        override fun invoke(): Long = value
    }

    class FakeAdmission : DshMaintenanceAdmissionPort {
        var busy = false
        var reject = false
        var grants = 0
        var releases = 0
        val holders = listOf("other-owner")

        override fun acquire(request: DshMaintenanceAdmissionRequest): DshMaintenanceAdmissionOutcome {
            if (reject) return DshMaintenanceAdmissionOutcome.Rejected("policy refused")
            if (busy) return DshMaintenanceAdmissionOutcome.Busy(holders, 250L)
            grants++
            return DshMaintenanceAdmissionOutcome.Granted(
                object : DshMaintenanceLease {
                    override val leaseId: String = "lease-$grants"
                    override val owner: String = request.requester.owner
                    override fun release() {
                        releases++
                    }
                }
            )
        }
    }

    /** Throws at the first matching durability boundary, exactly like a process death. */
    class CrashAt(val boundaryName: String, val stepIndex: Int = -1) : DshMaintenanceCrashHook {
        var fired = false
        override fun onBoundary(boundary: DshMaintenanceBoundary) {
            if (boundary.name != boundaryName) return
            if (stepIndex >= 0 && boundary.stepIndex != stepIndex) return
            fired = true
            throw DshSimulatedCrash("crash at ${boundary.name}/${boundary.stepIndex}")
        }
    }

    /**
     * Minimal framed codec for tests: each frame is a 4-byte big-endian length followed by UTF-8
     * text. Encoding a changed frame reproduces the same frame shape, so concatenating untouched
     * raw frames and re-encoded frames is a faithful stand-in for the zstd frame container.
     */
    class FramedSessionCodec : DshSessionCodec {
        override fun decode(bytes: ByteArray): DshSessionContent {
            val frames = ArrayList<DshSessionFrame>()
            var offset = 0
            var index = 0
            while (offset < bytes.size) {
                if (offset + 4 > bytes.size) {
                    frames.add(DshSessionFrame(index, "", false, bytes.copyOfRange(offset, bytes.size), "torn header"))
                    break
                }
                val length = ((bytes[offset].toInt() and 0xff) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                    (bytes[offset + 3].toInt() and 0xff)
                offset += 4
                if (length == CORRUPT_LENGTH) {
                    frames.add(DshSessionFrame(index, "", false, byteArrayOf(), "corrupt frame"))
                    break
                }
                if (length < 0 || offset + length > bytes.size) {
                    frames.add(DshSessionFrame(index, "", false, bytes.copyOfRange(offset, bytes.size), "bad length"))
                    break
                }
                val text = String(bytes, offset, length, Charsets.UTF_8)
                frames.add(
                    DshSessionFrame(
                        index = index,
                        text = text,
                        decodable = true,
                        raw = bytes.copyOfRange(offset - 4, offset + length)
                    )
                )
                offset += length
                index++
            }
            return DshSessionContent("fake-framed-v1", frames)
        }

        override fun encodeFrame(text: String): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(text.toByteArray(Charsets.UTF_8).size)
                output.write(text.toByteArray(Charsets.UTF_8))
            }
            return buffer.toByteArray()
        }

        fun encodeFrames(texts: List<String>): ByteArray {
            val buffer = ByteArrayOutputStream()
            for (text in texts) buffer.write(encodeFrame(text))
            return buffer.toByteArray()
        }

        fun corruptFrame(): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { it.writeInt(CORRUPT_LENGTH) }
            return buffer.toByteArray()
        }

        private companion object {
            const val CORRUPT_LENGTH = -1
        }
    }

    /** Fake host lifecycle that records the exact serial order and enforces one host. */
    class FakeHost(
        var startState: DshHostState = DshHostState.STOPPED,
        var productionRuns: Boolean = true,
        var productionHealth: DshHostHealth = DshHostHealth(
            hostReady = true,
            homeReadable = true,
            sessionReaderReady = true,
            providerConfigured = true,
            authReady = true
        )
    ) : DshHostLifecyclePort {
        val calls = ArrayList<String>()
        var state: DshHostState = DshHostState.STOPPED
        var trialHealth: DshHostHealth = DshHostHealth(hostReady = true, homeReadable = true, sessionReaderReady = true)
        var trialStarts = 0
        var productionStarts = 0
        var stopRefuses = false
        var trialStopRefuses = false

        override fun status(): DshHostStatus = DshHostStatus(state, generation = if (state == DshHostState.RUNNING) 7L else 0L)

        override fun stopAndConfirm(timeoutMillis: Long): DshHostStatus {
            calls.add("stop")
            if (!stopRefuses) state = DshHostState.STOPPED
            return DshHostStatus(state)
        }

        override fun startTrial(candidateRoot: File, candidateFingerprint: String): DshHostStatus {
            calls.add("startTrial")
            if (state != DshHostState.STOPPED) return DshHostStatus(state, detail = "another host is running")
            trialStarts++
            state = if (startState == DshHostState.FAILED) DshHostState.FAILED else DshHostState.RUNNING
            return DshHostStatus(state)
        }

        override fun probeTrialHealth(): DshHostHealth = trialHealth

        override fun stopTrial(timeoutMillis: Long): DshHostStatus {
            calls.add("stopTrial")
            if (!trialStopRefuses) state = DshHostState.STOPPED
            return DshHostStatus(state)
        }

        override fun startProduction(activeRoot: File, signature: String): DshHostStatus {
            calls.add("startProduction")
            if (state != DshHostState.STOPPED) return DshHostStatus(state, detail = "another host is running")
            productionStarts++
            if (!productionRuns) return DshHostStatus(DshHostState.FAILED)
            state = DshHostState.RUNNING
            return DshHostStatus(state)
        }

        /**
         * Per-production-start health observations: entry N answers the (N+1)-th start, falling
         * back to [productionHealth]. It lets one fixture model "candidate unhealthy, restored
         * previous healthy" without a second fake.
         */
        var productionHealthPerStart: List<DshHostHealth> = emptyList()

        override fun probeReadOnlyHealth(): DshHostHealth =
            productionHealthPerStart.getOrNull(productionStarts - 1) ?: productionHealth
    }
}

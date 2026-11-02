package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPS-11 probes: each check observes a real artifact or live state and never degrades to "the file
 * exists". Fixtures use the marker shapes production actually writes (payload marker from the
 * installer, home marker from the gateway seeder). A stopped host omits the live gateway/bridge
 * checks instead of claiming them healthy.
 */
class RuntimeSelfCheckTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-self-check")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private val hash = "975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab"
    private val fileCount = 31_021L

    private fun environment(root: File, phase: String = "READY"): DshSelfCheckEnvironment {
        val marker = File(root, "payload.marker.json")
        TestSupport.write(
            marker,
            "{\"manifestVersion\":1,\"payloadVersion\":\"dsh\",\"archiveSha256\":\"$hash\"," +
                "\"fileCount\":$fileCount,\"installedAtMillis\":1}"
        )
        val node = File(root, "libdshnode.so")
        val bytes = ByteArray(1_000_010)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[18] = 0xB7.toByte() // EM_AARCH64 low byte
        bytes[19] = 0x00
        TestSupport.writeBytes(node, bytes)
        val homeMarker = File(root, ".mca-home-seeded.json")
        TestSupport.write(
            homeMarker,
            "{\"markerVersion\":1,\"templateVersion\":\"dsh-home-v1\",\"phase\":\"complete\"," +
                "\"updatedAtEpochMillis\":1,\"source\":\"seed\"}"
        )
        val sessions = File(root, "home/sessions")
        sessions.mkdirs()
        return DshSelfCheckEnvironment(
            payloadMarkerFile = marker,
            expectedArchiveSha256 = hash,
            expectedFileCount = fileCount,
            nativeLibrary = node,
            installedAbi = "arm64-v8a",
            homeMarkerFile = homeMarker,
            sessionsDir = sessions,
            sessionCodecAvailable = true,
            sessionHeaderProof = DshSessionHeaderProof(true, "header version=4", 4),
            runtimePhase = phase,
            gatewayReady = true,
            bridgeReady = true
        )
    }

    private fun probes(env: DshSelfCheckEnvironment): List<DshSelfCheckProbe> =
        DshRuntimeSelfCheckPort { env }.run()

    @Test
    fun healthyEnvironmentReportsAllLiveChecks() {
        val report = probes(environment(newRoot()))

        assertEquals(7, report.size)
        assertTrue(report.all { it.ok })
        assertTrue(report.any { it.check == DshSelfCheckCheck.PAYLOAD_INTEGRITY })
        assertTrue(report.any { it.check == DshSelfCheckCheck.NODE_EXECUTABLE })
        assertTrue(report.any { it.check == DshSelfCheckCheck.HOME_MARKER })
        assertTrue(report.any { it.check == DshSelfCheckCheck.GATEWAY_BINDING })
        assertTrue(report.any { it.check == DshSelfCheckCheck.BRIDGE })
    }

    @Test
    fun aTamperedMarkerFailsThePayloadProbe() {
        val root = newRoot()
        val env = environment(root)
        TestSupport.write(env.payloadMarkerFile, "{\"archiveSha256\":\"${"b".repeat(64)}\",\"fileCount\":$fileCount}")

        val probe = probes(env).first { it.check == DshSelfCheckCheck.PAYLOAD_INTEGRITY }

        assertFalse(probe.ok)
        assertTrue(probe.detail.contains("hash"))
    }

    @Test
    fun aMissingPackagedManifestIsNotReportedAsHealthy() {
        val env = environment(newRoot()).copy(expectedArchiveSha256 = null)

        val probe = probes(env).first { it.check == DshSelfCheckCheck.PAYLOAD_INTEGRITY }

        assertFalse(probe.ok)
        assertTrue(probe.detail.contains("unavailable"))
    }

    @Test
    fun aFileCountMismatchFailsThePayloadProbe() {
        val env = environment(newRoot()).copy(expectedFileCount = fileCount + 1)

        val probe = probes(env).first { it.check == DshSelfCheckCheck.PAYLOAD_INTEGRITY }

        assertFalse(probe.ok)
        assertTrue(probe.detail.contains("file count"))
    }

    @Test
    fun aNonAarch64ElfIsNotAccepted() {
        val root = newRoot()
        val env = environment(root)
        val bytes = env.nativeLibrary!!.readBytes()
        bytes[18] = 0x3E // EM_X86_64
        env.nativeLibrary.writeBytes(bytes)

        val probe = probes(env).first { it.check == DshSelfCheckCheck.NODE_EXECUTABLE }

        assertFalse(probe.ok)
        assertTrue(probe.detail.contains("AArch64"))
    }

    @Test
    fun aWrongInstalledAbiIsReported() {
        val env = environment(newRoot()).copy(installedAbi = "armeabi-v7a")

        val probe = probes(env).first { it.check == DshSelfCheckCheck.NATIVE_ABI }

        assertFalse(probe.ok)
        assertTrue(probe.detail.contains("armeabi-v7a"))
    }

    @Test
    fun anIncompleteHomeMarkerIsReported() {
        val root = newRoot()
        val env = environment(root)
        TestSupport.write(env.homeMarkerFile, "{\"payloadVersion\":\"dsh\"}")

        val probe = probes(env).first { it.check == DshSelfCheckCheck.HOME_MARKER }

        assertFalse(probe.ok)
    }

    @Test
    fun aReadableSessionDirectoryIsNotProofOfASessionReader() {
        val env = environment(newRoot()).copy(sessionHeaderProof = DshSessionHeaderProof.NOT_VERIFIED)

        val probe = probes(env).first { it.check == DshSelfCheckCheck.SESSION_READER }

        assertFalse(probe.ok)
        assertTrue(probe.detail.contains("header"))
    }

    @Test
    fun aMissingSessionCodecIsReportedRatherThanHidden() {
        val env = environment(newRoot()).copy(sessionCodecAvailable = false)

        val probe = probes(env).first { it.check == DshSelfCheckCheck.SESSION_READER }

        assertFalse(probe.ok)
        assertTrue(probe.detail.contains("codec"))
    }

    @Test
    fun aStoppedHostOmitsLiveBindingChecks() {
        val report = probes(environment(newRoot(), phase = "STOPPED"))

        assertEquals(5, report.size)
        assertFalse(report.any { it.check == DshSelfCheckCheck.GATEWAY_BINDING })
        assertFalse(report.any { it.check == DshSelfCheckCheck.BRIDGE })
        assertTrue(report.all { it.ok })
    }
}

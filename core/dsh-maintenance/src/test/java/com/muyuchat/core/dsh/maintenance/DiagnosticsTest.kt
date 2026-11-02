package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPS-22: the diagnostic bundle is an allowlisted engineering fact set, never a transcript.
 * These tests prove the exporter writes a real file, strips secret-looking values and absolute
 * private paths, enforces its size budget and refuses to run without quiescence.
 */
class DiagnosticsTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-diagnostics")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun exporter(root: File): DshDiagnosticsExporter {
        val engine = DshMaintenanceEngine(
            root,
            TestSupport.FakeAdmission(),
            TestSupport.healthyProbe(),
            TestSupport.FakeClock()
        )
        return DshDiagnosticsExporter(engine, TestSupport.FakeClock())
    }

    private fun input(): DshDiagnosticInput = DshDiagnosticInput(
        appVersion = "0.2.4",
        runtimePhase = "STOPPED",
        hostGeneration = 3,
        closureFingerprint = "closure-abc",
        selfCheck = listOf(
            DshSelfCheckProbe(DshSelfCheckCheck.NODE_EXECUTABLE, true, "node v26.4.0"),
            DshSelfCheckProbe(DshSelfCheckCheck.BRIDGE, false, "port closed")
        ),
        maintenancePhases = listOf("PREPARED", "COMMITTED"),
        toolAvailability = mapOf("android" to "registered"),
        sessionCount = 2,
        recycleBinCount = 1,
        extra = mapOf(
            "note" to "apiKey=sk-abcdefghijklmnop at /data/user/0/com.muyuchat.mca/files/x"
        )
    )

    @Test
    fun exportWritesAnAllowlistedBundleAndRedactsSecretsAndPaths() {
        val root = newRoot()
        val output = File(root, "diagnostics.json")

        val result = exporter(root).export(input(), output, TestSupport.requester(), TestSupport.stopped())

        assertTrue(result is DshDiagnosticsResult.Exported)
        val exported = result as DshDiagnosticsResult.Exported
        assertTrue(exported.file.isFile)
        assertTrue(exported.bytes > 0)
        assertEquals(64, exported.sha256.length)

        val text = output.readText(Charsets.UTF_8)
        assertFalse("a raw key must never reach the bundle", text.contains("sk-abcdefghijklmnop"))
        assertFalse("an absolute private path must never reach the bundle", text.contains("/data/user/0"))
        assertTrue("the allowlisted facts are present", text.contains("\"format\":\"mca.dsh.diagnostics\""))
        assertTrue(text.contains("\"appVersion\":\"0.2.4\""))

        val json = JSONObject(text)
        assertEquals(2, json.getJSONArray("selfCheck").length())
        assertEquals(2, json.getInt("sessionCount"))
        assertEquals(1, json.getInt("recycleBinCount"))
    }

    @Test
    fun anOversizedBundleIsRejectedInsteadOfTruncated() {
        val root = newRoot()
        val oversized = input().copy(extra = (1..3000).associate { "k" + it to "x".repeat(120) })

        val result = exporter(root).export(
            oversized,
            File(root, "oversized.json"),
            TestSupport.requester(),
            TestSupport.stopped()
        )

        assertTrue(result is DshDiagnosticsResult.Rejected)
        assertEquals(
            DshMaintenanceError.SPACE_INSUFFICIENT,
            (result as DshDiagnosticsResult.Rejected).failure.code
        )
        assertFalse(File(root, "oversized.json").exists())
    }

    @Test
    fun exportWithoutQuiescenceIsRejected() {
        val root = newRoot()
        val running = DshQuiescenceEvidence(
            hostGenerationStopped = false,
            onlineBarrierCovered = false,
            allMutatorsDrained = true,
            detail = "host still running"
        )

        val result = exporter(root).export(
            input(),
            File(root, "running.json"),
            TestSupport.requester(),
            running
        )

        assertTrue(result is DshDiagnosticsResult.Rejected)
        assertFalse(File(root, "running.json").exists())
    }
}

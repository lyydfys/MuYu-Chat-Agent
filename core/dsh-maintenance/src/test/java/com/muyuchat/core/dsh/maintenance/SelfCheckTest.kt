package com.muyuchat.core.dsh.maintenance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelfCheckTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-selfcheck")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun report(vararg probes: DshSelfCheckProbe) =
        DshSelfCheckReport(probes.toList(), 1_700_000_000_000L)

    private val goodProbes = listOf(
        DshSelfCheckProbe(DshSelfCheckCheck.PAYLOAD_INTEGRITY, true),
        DshSelfCheckProbe(DshSelfCheckCheck.NODE_EXECUTABLE, true),
        DshSelfCheckProbe(DshSelfCheckCheck.NATIVE_ABI, true),
        DshSelfCheckProbe(DshSelfCheckCheck.HOME_MARKER, true),
        DshSelfCheckProbe(DshSelfCheckCheck.SESSION_READER, true),
        DshSelfCheckProbe(DshSelfCheckCheck.GATEWAY_BINDING, true),
        DshSelfCheckProbe(DshSelfCheckCheck.BRIDGE, true)
    )

    @Test
    fun runtimeProbeFailureRequiresAttentionAndIsNotFileRepaired() {
        val root = newRoot()
        val home = File(root, "home")
        val policy = DshSelfCheckPolicy(
            managedFiles = listOf(DshManagedFile("profile/base.json", "a".repeat(64)))
        )
        val outcome = policy.evaluate(
            report(*(goodProbes.dropLast(1) + DshSelfCheckProbe(DshSelfCheckCheck.BRIDGE, false, "port closed")).toTypedArray()),
            home
        )
        assertTrue(outcome is DshSelfCheckOutcome.RequiresAttention)
        assertTrue((outcome as DshSelfCheckOutcome.RequiresAttention).reasons.any { it.contains("BRIDGE") })
    }

    @Test
    fun repairsOnlyTheManagedFileWithAMatchingSource() {
        val root = newRoot()
        val home = File(root, "home")
        val source = File(root, "managed")
        val expected = "managed-content"
        val managedHash = DshMaintenanceIo.sha256(expected.toByteArray())
        TestSupport.write(File(source, "profile/base.json"), expected)
        TestSupport.write(File(home, "profile/base.json"), "corrupted")
        TestSupport.write(File(home, "plugins-user/tool/index.js"), "user-plugin")
        TestSupport.write(File(home, "sessions/main/session-a/session.v4.jsonl.zstd"), "session")
        val policy = DshSelfCheckPolicy(
            managedFiles = listOf(DshManagedFile("profile/base.json", managedHash))
        )
        val evaluated = policy.evaluate(report(*goodProbes.toTypedArray()), home)
        assertTrue(evaluated is DshSelfCheckOutcome.NeedsRepair)
        val plan = policy.planRepairs(home, source, (evaluated as DshSelfCheckOutcome.NeedsRepair).mismatched)
        assertEquals(1, plan.repairs.size)
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val outcome = policy.applyRepairs(engine, plan, TestSupport.requester(), TestSupport.stopped())
        assertTrue(outcome is DshMaintenanceOutcome.Committed)
        assertEquals(expected, File(home, "profile/base.json").readText())
        assertEquals("user-plugin", File(home, "plugins-user/tool/index.js").readText())
        assertEquals("session", File(home, "sessions/main/session-a/session.v4.jsonl.zstd").readText())
    }

    @Test
    fun refusesToRepairWhenTheManagedSourceHashDoesNotMatch() {
        val root = newRoot()
        val home = File(root, "home")
        val source = File(root, "managed")
        TestSupport.write(File(source, "profile/base.json"), "tampered-source")
        TestSupport.write(File(home, "profile/base.json"), "corrupted")
        val policy = DshSelfCheckPolicy(
            managedFiles = listOf(DshManagedFile("profile/base.json", DshMaintenanceIo.sha256("expected".toByteArray())))
        )
        val plan = policy.planRepairs(home, source, listOf("profile/base.json"))
        assertFalse(plan.hasWork)
        assertTrue(plan.blocked.single().contains("hash"))
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val outcome = policy.applyRepairs(engine, plan, TestSupport.requester(), TestSupport.stopped())
        assertEquals(
            DshMaintenanceError.SELF_CHECK_FAILED,
            (outcome as DshMaintenanceOutcome.Rejected).failure.code
        )
        assertEquals("corrupted", File(home, "profile/base.json").readText())
    }

    @Test
    fun protectedUserDataIsNeverAManagedRepairTarget() {
        val policy = DshSelfCheckPolicy(
            managedFiles = listOf(
                DshManagedFile("sessions/main/session-a/session.v4.jsonl.zstd", "a".repeat(64)),
                DshManagedFile("sessions-deleted/x/session.v4.jsonl.zstd", "a".repeat(64)),
                DshManagedFile("attachments/v1/objects/aa/aa", "a".repeat(64)),
                DshManagedFile(".credentials.yaml", "a".repeat(64))
            )
        )
        assertTrue(policy.isProtected("sessions/main/session-a/session.v4.jsonl.zstd"))
        assertTrue(policy.isProtected("sessions-deleted/x/session.v4.jsonl.zstd"))
        assertTrue(policy.isProtected("attachments/v1/objects/aa/aa"))
        assertTrue(policy.isProtected(".credentials.yaml"))
        assertFalse(policy.isProtected("payload/dshroot/index.js"))
        val root = newRoot()
        val home = File(root, "home")
        assertTrue(policy.findMismatched(home).isEmpty())
    }

    @Test
    fun payloadScopedManagedFilesAreCheckedAndRepairedAgainstThePayloadRoot() {
        val root = newRoot()
        val home = File(root, "home")
        val payload = File(root, "payload")
        val expected = "runtime-library"
        val expectedHash = DshMaintenanceIo.sha256(expected.toByteArray())
        val declaration = DshManagedFile(
            relativePath = "runtime/lib/libz.so.1",
            expectedSha256 = expectedHash,
            root = DshManagedFileRoot.PAYLOAD
        )
        val policy = DshSelfCheckPolicy(listOf(declaration), payloadDir = payload)
        TestSupport.write(File(payload, "runtime/lib/libz.so.1"), expected)
        // A same-named file under the home is not the managed target and must not be consulted.
        TestSupport.write(File(home, "runtime/lib/libz.so.1"), "home copy")
        assertTrue(policy.findMismatched(home).isEmpty())

        TestSupport.write(File(payload, "runtime/lib/libz.so.1"), "tampered")
        assertEquals(listOf("runtime/lib/libz.so.1"), policy.findMismatched(home))

        val source = File(root, "verified-source")
        TestSupport.write(File(source, "runtime/lib/libz.so.1"), expected)
        val plan = policy.planRepairs(home, source, listOf("runtime/lib/libz.so.1"))
        assertEquals(1, plan.repairs.size)
        assertEquals(File(payload, "runtime/lib/libz.so.1"), plan.repairs.single().target)

        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val outcome = policy.applyRepairs(engine, plan, TestSupport.requester(), TestSupport.stopped())
        assertTrue(outcome is DshMaintenanceOutcome.Committed)
        assertEquals(expected, File(payload, "runtime/lib/libz.so.1").readText())
        // The home copy is untouched: repair is scoped to the managed root.
        assertEquals("home copy", File(home, "runtime/lib/libz.so.1").readText())
    }

    @Test
    fun aPayloadScopedFileWithoutAPayloadRootIsUnverifiableNotMismatched() {
        val root = newRoot()
        val home = File(root, "home")
        val policy = DshSelfCheckPolicy(
            managedFiles = listOf(
                DshManagedFile(
                    relativePath = "runtime/lib/libz.so.1",
                    expectedSha256 = "a".repeat(64),
                    root = DshManagedFileRoot.PAYLOAD
                )
            )
        )
        // No payload root in this build: skipping is honest, reporting a mismatch would be a lie.
        assertTrue(policy.findMismatched(home).isEmpty())
    }
}

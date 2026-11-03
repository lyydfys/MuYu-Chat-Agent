package com.muyuchat.feature.dsh.workspace.device

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Root-based one-tap provisioning.
 *
 * No `su` runs: a fake launcher answers the fixed scripts, so the test drives the real step order,
 * the append-only key handling, the SELinux distinction and the uid=2000 verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshAdbProvisioningTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    // region fixed scripts and key validation

    @Test
    fun `the fixed scripts name the real property and restart adbd`() {
        assertTrue(DSH_ADB_READ_KEYS_SCRIPT.contains("/data/misc/adb/adb_keys"))
        assertTrue(DSH_ADB_ENABLE_TCP_SCRIPT.contains("setprop service.adb.tcp.port 5555"))
        assertTrue(DSH_ADB_ENABLE_TCP_SCRIPT.contains("stop adbd"))
        assertTrue(DSH_ADB_ENABLE_TCP_SCRIPT.contains("start adbd"))
        assertEquals(DshAdbEndpoint("127.0.0.1", 5555), dshAdbLoopbackEndpoint())
    }

    @Test
    fun `the append script only appends and quotes the key`() {
        val key = identity().publicKeyBase64()
        val script = dshAdbAppendKeyScript(key)
        assertTrue("append script must exist", script != null)
        assertTrue("must append, never truncate", script!!.contains(">> \"\$K\""))
        assertTrue(script.contains("/data/misc/adb/adb_keys"))
        assertTrue("the key must be single quoted", script.contains("'$key'"))
    }

    @Test
    fun `a key with shell metacharacters is refused`() {
        val forbidden = listOf(
            "abc\ndef",
            "abc;id",
            "abc`id",
            "abc\$id",
            "abc'def",
            "abc\"def",
            "abc def",
            "abc\\def",
            "abc&&id"
        )
        forbidden.forEach { key ->
            assertFalse("must reject <$key>", dshAdbKeyBase64Valid(key))
            assertNull("must not build a script for <$key>", dshAdbAppendKeyScript(key))
        }
    }

    @Test
    fun `a real adb key is valid base64 and an over long one is refused`() {
        assertTrue(dshAdbKeyBase64Valid(identity().publicKeyBase64()))
        assertFalse(dshAdbKeyBase64Valid("A".repeat(DSH_ADB_KEY_MAX_BASE64_LENGTH + 1)))
        assertFalse(dshAdbKeyBase64Valid(""))
    }

    // endregion

    // region step flow

    @Test
    fun `an already registered key is not appended again`() = runTest(dispatcher) {
        val identity = identity()
        val fake = FakeRoot(initialKeys = identity.publicKeyBase64() + "\n")
        val transport = FakeVerifyTransport()

        val outcome = provisioner(fake, identity, transport, portReady = true).provision()

        assertTrue(outcome.connected)
        assertNull(fake.appended)
        assertEquals(
            listOf(
                DshAdbProvisioningStep.ROOT_PROBE,
                DshAdbProvisioningStep.READ_ADB_KEYS,
                DshAdbProvisioningStep.WRITE_ADB_KEYS,
                DshAdbProvisioningStep.ENABLE_TCP_ADB,
                DshAdbProvisioningStep.VERIFY_CONNECTION
            ),
            outcome.steps.map { it.step }
        )
        assertTrue(outcome.steps[2].detail.contains("未重复写入"))
        assertFalse(fake.invocations.any { it[2].contains("printf") })
        assertTrue("the verification transport must be closed", transport.closed)
    }

    @Test
    fun `a missing key is appended once and confirmed by re-reading`() = runTest(dispatcher) {
        val identity = identity()
        val fake = FakeRoot(initialKeys = "")
        val transport = FakeVerifyTransport()

        val outcome = provisioner(fake, identity, transport, portReady = true).provision()

        assertTrue(outcome.connected)
        assertEquals(identity.publicKeyBase64(), fake.appended)
        val scripts = fake.invocations.map { it[2] }
        assertEquals("id", scripts.first())
        assertEquals("the append must be followed by a confirming read", 2, scripts.count { it.contains("cat /data/misc/adb/adb_keys") })
        assertTrue(outcome.steps.all { it.succeeded })
        assertTrue(outcome.steps.last().detail.contains("uid=2000"))
    }

    @Test
    fun `root without a grant stops at the probe`() = runTest(dispatcher) {
        val fake = FakeRoot(initialKeys = "", probeExitCode = 1)
        val outcome = provisioner(fake, identity(), FakeVerifyTransport(), portReady = true).provision()

        assertFalse(outcome.connected)
        assertEquals(DshDeviceCommandFailure.NOT_GRANTED, outcome.failure)
        assertEquals(1, outcome.steps.size)
        assertEquals(DshAdbProvisioningStep.ROOT_PROBE, outcome.steps.single().step)
        assertEquals("only the probe may run", 1, fake.invocations.size)
    }

    @Test
    fun `a selinux refusal on setprop is reported separately`() = runTest(dispatcher) {
        val identity = identity()
        val fake = FakeRoot(
            initialKeys = identity.publicKeyBase64() + "\n",
            enableExitCode = 1,
            enableStderr = "avc: denied { set } for property=service.adb.tcp.port scontext=u:r:magisk:s0"
        )

        val outcome = provisioner(fake, identity, FakeVerifyTransport(), portReady = true).provision()

        assertFalse(outcome.connected)
        assertEquals(DshDeviceCommandFailure.SELINUX_DENIED, outcome.failure)
        val enableStep = outcome.steps.single { it.step == DshAdbProvisioningStep.ENABLE_TCP_ADB }
        assertFalse(enableStep.succeeded)
        assertEquals(DshDeviceCommandFailure.SELINUX_DENIED, enableStep.failure)
        assertTrue(enableStep.detail.contains("SELinux"))
    }

    @Test
    fun `a plain setprop failure is an exec failure`() = runTest(dispatcher) {
        val identity = identity()
        val fake = FakeRoot(
            initialKeys = identity.publicKeyBase64() + "\n",
            enableExitCode = 1,
            enableStderr = "setprop: failed to set property"
        )

        val outcome = provisioner(fake, identity, FakeVerifyTransport(), portReady = true).provision()

        assertEquals(DshDeviceCommandFailure.EXEC_FAILED, outcome.failure)
    }

    @Test
    fun `a port that never opens fails the verification step with a timeout`() = runTest(dispatcher) {
        val identity = identity()
        val fake = FakeRoot(initialKeys = identity.publicKeyBase64() + "\n")
        val transport = FakeVerifyTransport()

        val outcome = provisioner(fake, identity, transport, portReady = false).provision()

        assertFalse(outcome.connected)
        assertEquals(DshDeviceCommandFailure.TIMEOUT, outcome.failure)
        assertEquals(
            DshAdbProvisioningStep.VERIFY_CONNECTION,
            outcome.steps.last().step
        )
        assertFalse(outcome.steps.last().succeeded)
        assertFalse("no socket is opened when the port never came up", transport.connectedCalled)
    }

    @Test
    fun `a connected shell that is not uid 2000 fails verification`() = runTest(dispatcher) {
        val identity = identity()
        val fake = FakeRoot(initialKeys = identity.publicKeyBase64() + "\n")
        val transport = FakeVerifyTransport(idOutput = "uid=0(root) gid=0(root)\n")

        val outcome = provisioner(fake, identity, transport, portReady = true).provision()

        assertFalse(outcome.connected)
        assertEquals(DshDeviceCommandFailure.EXEC_FAILED, outcome.failure)
        assertTrue(outcome.steps.last().detail.contains("uid=2000"))
    }

    @Test
    fun `a key that cannot be produced fails before any root command`() = runTest(dispatcher) {
        val fake = FakeRoot(initialKeys = "")
        val provisioner = DshAdbProvisioner(
            root = fake.channel(dispatcher),
            identity = { error("identity unavailable") },
            transport = { FakeVerifyTransport() },
            portReady = { true },
            io = dispatcher
        )

        val outcome = provisioner.provision()

        assertFalse(outcome.connected)
        assertEquals(DshDeviceCommandFailure.POLICY_BLOCKED, outcome.failure)
        assertEquals(DshAdbProvisioningStep.WRITE_ADB_KEYS, outcome.steps.single().step)
        assertTrue("no root command may run", fake.invocations.isEmpty())
    }

    @Test
    fun `the step failure mapping separates selinux from plain failures`() {
        assertEquals(
            DshDeviceCommandFailure.SELINUX_DENIED,
            dshAdbStepFailure(DshDeviceCommandResult(1, "", "avc: denied { write }"))
        )
        assertEquals(
            DshDeviceCommandFailure.EXEC_FAILED,
            dshAdbStepFailure(DshDeviceCommandResult(1, "", "sh: start: not found"))
        )
        assertEquals(null, dshAdbStepFailure(DshDeviceCommandResult(0, "ok", "")))
        assertEquals(
            DshDeviceCommandFailure.TIMEOUT,
            dshAdbStepFailure(
                DshDeviceCommandResult.failure(DshDeviceCommandFailure.TIMEOUT, "late")
            )
        )
    }

    // endregion

    private fun provisioner(
        fake: FakeRoot,
        identity: AdbIdentity,
        transport: AdbTransport,
        portReady: Boolean
    ): DshAdbProvisioner = DshAdbProvisioner(
        root = fake.channel(dispatcher),
        identity = { identity },
        transport = { transport },
        portReady = { portReady },
        io = dispatcher
    )

    private fun identity(): AdbIdentity =
        AdbIdentity.loadOrCreate(File(temporaryFolder.newFolder(), "adbkey.pkcs8"))

    /** Answers the fixed scripts of the provisioner; records every argv. */
    private class FakeRoot(
        initialKeys: String,
        private val probeExitCode: Int = 0,
        private val enableExitCode: Int = 0,
        private val enableStderr: String = ""
    ) {
        val invocations: MutableList<List<String>> = mutableListOf()
        var appended: String? = null
            private set
        private var keys: String = initialKeys

        fun channel(io: CoroutineDispatcher): DshRootChannel = DshRootChannel(
            RecordingLauncher { _, argv ->
                invocations += argv
                val script = argv[2]
                when {
                    script == "id" -> FakeDshProcess(
                        stdoutText = if (probeExitCode == 0) "uid=0(root) gid=0(root)\n" else "",
                        stderrText = if (probeExitCode == 0) "" else "su: permission denied",
                        exitCode = probeExitCode
                    )

                    script.contains("cat /data/misc/adb/adb_keys") ->
                        FakeDshProcess(stdoutText = keys)

                    script.contains("printf '%s\\n'") -> {
                        val key = script.substringAfter("printf '%s\\n' '").substringBefore("'")
                        appended = key
                        keys = if (keys.isBlank()) "$key\n" else keys.trimEnd() + "\n" + key + "\n"
                        FakeDshProcess()
                    }

                    script.contains("setprop service.adb.tcp.port") -> FakeDshProcess(
                        stderrText = enableStderr,
                        exitCode = enableExitCode
                    )

                    else -> FakeDshProcess(exitCode = 1)
                }
            },
            probeTimeoutMs = 50L,
            io = io
        )
    }

    /** Verifies the last step without opening a real socket. */
    private class FakeVerifyTransport(
        private val idOutput: String = "uid=2000(shell) gid=2000(shell)\n"
    ) : AdbTransport {
        var connectedCalled: Boolean = false
            private set
        var closed: Boolean = false
            private set

        override fun state(): DshAdbTransportState = DshAdbTransportState.CONNECTED

        override suspend fun connect(endpoint: DshAdbEndpoint): Boolean {
            connectedCalled = true
            return true
        }

        override suspend fun shell(command: String): DshAdbShellResult =
            DshAdbShellResult(exitCode = 0, stdout = idOutput, stderr = "")

        override fun close() {
            closed = true
        }
    }
}

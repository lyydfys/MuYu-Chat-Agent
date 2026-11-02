package com.muyuchat.core.dsh.runtime.process

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity facts are what makes a later generation's signal safe: task id, command hash,
 * generation environment token and the kernel start time.
 */
class DshJobIdentityTest {

    @Test
    fun taskIdBindsGenerationPidAndStartTime() {
        assertEquals("mca-job-13-7164-9000", DshJobIdentity.taskId(13L, 7164, 9_000L))
    }

    @Test
    fun cmdlineHashIsTheSha256OfTheRawBytes() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DshJobIdentity.cmdlineHash("abc".toByteArray(Charsets.UTF_8))
        )
        val original = "sh -c sleep 300".toByteArray(Charsets.UTF_8)
        assertEquals(
            DshJobIdentity.cmdlineHash(original),
            DshJobIdentity.cmdlineHash(original.copyOf())
        )
        assertNotEquals(
            DshJobIdentity.cmdlineHash(original),
            DshJobIdentity.cmdlineHash("sh -c sleep 1".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun readableCommandIsBoundedAndRedacted() {
        val cmdline = "curl -H \"Authorization: Bearer abcdef123456\" https://example.invalid"
            .toByteArray(Charsets.UTF_8)

        val command = DshJobIdentity.readableCommand(cmdline)!!

        assertTrue(command.startsWith("curl"))
        assertFalse("credential shapes must not reach the ledger", command.contains("abcdef123456"))

        val long = DshJobIdentity.readableCommand(ByteArray(4_000) { 'x'.code.toByte() })!!
        assertTrue(long.length <= DshJobIdentity.MAX_COMMAND_CHARS + 1)
    }

    @Test
    fun theGenerationEnvironmentReachesAProductionChildButNotATrial() {
        val root = File("/tmp/dsh-job-identity")
        fun environment(generation: Long?): Map<String, String> = DshNodeCommand.environment(
            homeDir = File(root, "home"),
            configDir = File(root, "home/config"),
            workspaceDir = File(root, "workspace"),
            tmpDir = File(root, "tmp"),
            payloadDir = File(root, "payload"),
            runtimeLibraryDir = File(root, "payload/runtime/lib"),
            payloadBinDir = File(root, "payload/bin"),
            generation = generation
        )

        assertEquals("13", environment(13L)[DshJobIdentity.GENERATION_ENV])
        assertFalse(environment(null).containsKey(DshJobIdentity.GENERATION_ENV))
        assertFalse(environment(0L).containsKey(DshJobIdentity.GENERATION_ENV))
    }
}

class DshProcIdentityFactsTest {

    @Test
    fun startTimeIsField22AfterTheLastParenthesis() {
        val stat = "1234 (my proc) S 42 1 1 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 987654321 123 456"
        assertEquals(987_654_321L, parseProcStartTime(stat))
    }

    @Test
    fun aShortOrMalformedStatIsUnreadable() {
        assertNull(parseProcStartTime("1 (x) S 0"))
        assertNull(parseProcStartTime("garbage"))
        assertNull(parseProcStartTime(""))
    }

    @Test
    fun environmentLookupRequiresTheExactName() {
        val bytes = "HOME=/tmp\u0000MCA_DSH_GENERATION=13\u0000EMPTY=\u0000"
            .toByteArray(Charsets.UTF_8)

        assertEquals("13", parseProcEnvironmentValue(bytes, DshJobIdentity.GENERATION_ENV))
        assertEquals("/tmp", parseProcEnvironmentValue(bytes, "HOME"))
        assertEquals("", parseProcEnvironmentValue(bytes, "EMPTY"))
        assertNull(parseProcEnvironmentValue(bytes, "MCA_DSH_GENERATION_EXTRA"))
        assertNull(parseProcEnvironmentValue(bytes, "DSH_HOME"))
    }
}

package com.muyuchat.mca.agent.data.export

import com.muyuchat.mca.agent.data.AgentTestFixtures
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentTaskPackageHeaderSecurityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var importStaging: File

    @Before
    fun setUp() {
        importStaging = temporary.newFolder("import-staging")
    }

    @Test
    fun `encrypted entries are rejected`() {
        val bytes = AgentPackageTestFixtures.patchFirstCentralFlags(
            AgentPackageTestFixtures.validPackageBytes(),
            0x0001
        )
        expectFailure(bytes, AgentPackageErrorCode.ENCRYPTED_ENTRY)
    }

    @Test
    fun `unknown compression method is rejected`() {
        val bytes = AgentPackageTestFixtures.patchFirstCentralMethod(
            AgentPackageTestFixtures.validPackageBytes(),
            99
        )
        expectFailure(bytes, AgentPackageErrorCode.UNSUPPORTED_COMPRESSION)
    }

    @Test
    fun `symlink external attributes are rejected`() {
        val bytes = AgentPackageTestFixtures.patchFirstCentralExternalAttributes(
            AgentPackageTestFixtures.validPackageBytes(),
            0xA1FFL shl 16
        )
        expectFailure(bytes, AgentPackageErrorCode.SYMLINK_ENTRY)
    }

    @Test
    fun `directory entries are rejected`() {
        val bytes = AgentPackageTestFixtures.zip(
            listOf(
                "manifest.json" to AgentPackageTestFixtures.manifestFor(emptyList()),
                "artifacts/" to byteArrayOf()
            )
        )
        expectFailure(bytes, AgentPackageErrorCode.INVALID_ARCHIVE)
    }

    @Test
    fun `zip slip target file is never created`() {
        val sentinel = File(temporary.root, "escape.txt")
        val bytes = AgentPackageTestFixtures.zip(
            listOf(
                "manifest.json" to AgentPackageTestFixtures.manifestFor(emptyList()),
                "../escape.txt" to "escaped".toByteArray()
            )
        )
        expectFailure(bytes, AgentPackageErrorCode.UNSAFE_PATH)
        assertTrue(!sentinel.exists())
    }

    private fun expectFailure(bytes: ByteArray, expected: AgentPackageErrorCode) {
        val result = runBlocking {
            AgentTaskPackageImporter(
                stagingRoot = importStaging,
                idGenerator = AgentTestFixtures.SequentialIds(900),
                limits = AgentTaskPackageLimits()
            ).import(ByteArrayInputStream(bytes))
        }
        assertTrue("expected failure ${expected.name} but got $result", result is AgentPackageResult.Failure)
        assertEquals(expected, (result as AgentPackageResult.Failure).code)
        assertTrue(importStaging.listFiles().orEmpty().isEmpty())
    }
}

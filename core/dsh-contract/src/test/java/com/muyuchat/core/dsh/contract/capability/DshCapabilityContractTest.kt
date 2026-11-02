package com.muyuchat.core.dsh.contract.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract-level invariants for the additive DSH capability bridge. No Android code is run. */
class DshCapabilityContractTest {

    @Test
    fun capabilityTokenIsASeparateDshcCredentialAndRedactsItsValue() {
        val token = DshCapabilityToken.mint()
        assertTrue(token.value.startsWith("dshc_"))
        assertEquals(DshCapabilityToken.LENGTH, token.value.length)
        assertFalse(token.toString().contains(token.value))
        assertNotEquals(token.value, DshCapabilityToken.mint().value)

        assertNull(DshCapabilityToken.parse("dshg_" + "A".repeat(43)))
        assertNull(DshCapabilityToken.parse("dshc_" + "A".repeat(42)))
        assertNull(DshCapabilityToken.parse("dshc_" + "!".repeat(43)))
        val fixed = requireNotNull(DshCapabilityToken.parse("dshc_" + "A".repeat(43)))
        assertTrue(fixed.matches("dshc_" + "A".repeat(43)))
        assertFalse(fixed.matches("dshc_" + "B".repeat(43)))
    }

    @Test
    fun authorityBindsTokenGenerationAndGrantedCapability() {
        val authority = DshCapabilityAuthority()
        val grant = authority.startGeneration(
            generation = 7L,
            capabilities = setOf(DshCapability.FILE_PICK, DshCapability.NOTIFICATION)
        )
        val token = grant.token.value

        assertTrue(
            authority.authorize(token, 7L, DshCapability.FILE_PICK) is DshCapabilityAccess.Authorized
        )
        assertEquals(
            DshCapabilityErrorCode.UNAUTHORIZED,
            deniedCode(authority.authorize("dshc_" + "B".repeat(43), 7L, DshCapability.FILE_PICK))
        )
        assertEquals(
            DshCapabilityErrorCode.STALE_GENERATION,
            deniedCode(authority.authorize(token, 6L, DshCapability.FILE_PICK))
        )
        assertEquals(
            DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
            deniedCode(authority.authorize(token, 7L, DshCapability.DEVICE_ACTION))
        )

        val denial = authority.authorize(token, 7L, DshCapability.DEVICE_ACTION)
        assertTrue(denial is DshCapabilityAccess.Denied)
        assertFalse((denial as DshCapabilityAccess.Denied).failure.message.contains(token))
    }

    @Test
    fun revocationKillsTheGenerationToken() {
        val authority = DshCapabilityAuthority()
        val grant = authority.startGeneration(7L, setOf(DshCapability.FILE_PICK))

        assertTrue(authority.revoke())
        assertNull(authority.currentGrant())
        assertEquals(
            DshCapabilityErrorCode.STALE_GENERATION,
            deniedCode(authority.authorize(grant.token.value, 7L, DshCapability.FILE_PICK))
        )
        assertFalse(authority.revoke())
    }

    @Test
    fun authorityRejectsReusedOrBackwardGeneration() {
        val authority = DshCapabilityAuthority()
        authority.startGeneration(7L, emptySet())
        assertThrows(IllegalArgumentException::class.java) {
            authority.startGeneration(7L, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            authority.startGeneration(6L, emptySet())
        }
    }

    @Test
    fun endpointRequiresLoopbackAndRedactsTheToken() {
        val token = requireNotNull(DshCapabilityToken.parse("dshc_" + "A".repeat(43)))
        val endpoint = DshCapabilityEndpoint("http://127.0.0.1:4181", token, 7L)
        assertFalse(endpoint.toString().contains(token.value))

        assertThrows(IllegalArgumentException::class.java) {
            DshCapabilityEndpoint("http://example.com:4181", token, 7L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DshCapabilityEndpoint("http://127.0.0.1:0", token, 7L)
        }
    }

    @Test
    fun workspacePathsRejectTraversalAbsoluteAndEncodedEscapes() {
        listOf(
            "../secret",
            "../../etc/passwd",
            "inbox/../../secret",
            "inbox/%2e%2e/secret",
            "inbox/%2Fsecret",
            "/data/data/com.muyuchat.mca/files/secret",
            "C:\\Windows\\system.ini",
            "inbox\\..\\secret",
            "inbox//secret",
            "inbox/./secret",
            "inbox/secret/",
            "inbox/secret\u0000.txt",
            "a".repeat(DshCapabilityLimits.MAX_WORKSPACE_RELATIVE_PATH_CHARS + 1)
        ).forEach { raw ->
            assertNull("must reject path: $raw", DshWorkspaceRelativePath.parse(raw))
        }

        assertNotNull(
            DshWorkspaceRelativePath.parse("inbox/7/pick-request-0001/report.txt")
        )
        assertThrows(IllegalArgumentException::class.java) {
            DshWorkspaceRelativePath.of("../secret")
        }
    }

    @Test
    fun stagingPathsStayUnderInboxAndStagedFileEnforcesIt() {
        val requestId = DshCapabilityRequestId("pick-request-0001")
        val path = DshWorkspacePaths.stagingPath(7L, requestId, "../../Report 2026.txt")
        assertEquals("inbox/7/pick-request-0001/Report 2026.txt", path.value)
        assertTrue(DshWorkspacePaths.isStagingPath(path))
        assertEquals("Report_2026.txt", DshWorkspacePaths.sanitizeFileName("../../Report:2026.txt"))

        val staged = DshStagedFile(
            document = DshDocumentRef("doc_0123456789abcdef"),
            workspaceRelativePath = path,
            displayName = "Report 2026.txt",
            mimeType = "text/plain",
            sizeBytes = 12L
        )
        assertEquals(path, staged.workspaceRelativePath)

        assertThrows(IllegalArgumentException::class.java) {
            DshStagedFile(
                document = DshDocumentRef("doc_0123456789abcdef"),
                workspaceRelativePath = DshWorkspaceRelativePath.of("documents/report.txt"),
                displayName = "report.txt",
                mimeType = "text/plain",
                sizeBytes = 12L
            )
        }
    }

    @Test
    fun contractRejectsOversizedInlinePayloadAndInvalidMimeFilter() {
        assertThrows(IllegalArgumentException::class.java) {
            DshSaveSource.InlineBytes(
                ByteArray((DshCapabilityLimits.MAX_INLINE_BYTES + 1L).toInt())
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DshPickFilesRequest(
                requestId = DshCapabilityRequestId("pick-request-0002"),
                generation = 1L,
                reason = "read a document",
                acceptMimeTypes = listOf("text")
            )
        }
    }

    private fun deniedCode(access: DshCapabilityAccess): DshCapabilityErrorCode =
        (access as? DshCapabilityAccess.Denied)?.failure?.code
            ?: error("expected a denied capability access")
}

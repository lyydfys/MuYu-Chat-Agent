package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.maintenance.DshConsolePatchState
import com.muyuchat.core.dsh.maintenance.DshMaintenanceIo
import com.muyuchat.core.dsh.maintenance.DshPatchScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict decoding of the user-selected console patch document.
 *
 * The owner (not this decoder) decides whether a patch is safe, but the decoder must never hand it
 * something it cannot validate: unsafe paths, oversized documents and content whose hash does not
 * derive from the supplied bytes are rejected here, with fixed copy and no document content in the
 * message.
 */
class DshConsolePatchPayloadTest {

    private fun document(
        vararg files: Pair<String, String>,
        patchId: String = "user-console-1",
        scope: String = "DSH_CONSOLE"
    ): String {
        val array = JSONArray()
        files.forEach { (path, content) ->
            array.put(JSONObject().put("path", path).put("content", content))
        }
        return JSONObject()
            .put("patchId", patchId)
            .put("scope", scope)
            .put("files", array)
            .toString()
    }

    private val state = DshConsolePatchState(
        targetVersion = "dsh-test",
        factoryBaseHash = "f".repeat(64),
        managedPatchHash = "m".repeat(64)
    )

    @Test
    fun aValidDocumentBecomesAProposalWhoseHashesMatchItsBytes() {
        val decoded = DshConsolePatchPayload.decode(
            document("index.js" to "console.log('hi')\n", "style.css" to "body{}"),
            state
        )
        assertTrue(decoded is DshConsolePatchPayload.Decoded.Valid)
        val proposal = (decoded as DshConsolePatchPayload.Decoded.Valid).proposal
        assertEquals("user-console-1", proposal.patchId)
        assertEquals(DshPatchScope.DSH_CONSOLE, proposal.scope)
        assertEquals(2, proposal.files.size)
        assertEquals("dsh-test", proposal.targetVersion)
        assertEquals("f".repeat(64), proposal.factoryBaseHash)
        assertEquals("m".repeat(64), proposal.managedPatchHash)
        val first = proposal.files.first()
        assertEquals("index.js", first.relativePath)
        assertNull(first.baseSha256)
        assertEquals(DshMaintenanceIo.sha256("console.log('hi')\n".toByteArray()), first.newSha256)
    }

    @Test
    fun aDeclaredBaseHashIsKeptAndMustBeAFullHash() {
        val withBase = JSONObject()
            .put("patchId", "user-2")
            .put(
                "files",
                JSONArray().put(
                    JSONObject()
                        .put("path", "index.js")
                        .put("content", "x")
                        .put("base", "a".repeat(64))
                )
            )
            .toString()
        val decoded = DshConsolePatchPayload.decode(withBase, DshConsolePatchState())
        assertTrue(decoded is DshConsolePatchPayload.Decoded.Valid)
        assertEquals(
            "a".repeat(64),
            (decoded as DshConsolePatchPayload.Decoded.Valid).proposal.files.first().baseSha256
        )

        val shortBase = withBase.replace("a".repeat(64), "abc")
        val rejected = DshConsolePatchPayload.decode(shortBase, DshConsolePatchState())
        assertTrue(rejected is DshConsolePatchPayload.Decoded.Invalid)
    }

    @Test
    fun missingOrEmptyPayloadIsRejectedInsteadOfApplyingAnImplicitPatch() {
        assertTrue(DshConsolePatchPayload.decode(null, state) is DshConsolePatchPayload.Decoded.Invalid)
        assertTrue(DshConsolePatchPayload.decode("   ", state) is DshConsolePatchPayload.Decoded.Invalid)
        assertTrue(
            DshConsolePatchPayload.decode("""{"patchId":"x","files":[]}""", state)
                is DshConsolePatchPayload.Decoded.Invalid
        )
    }

    @Test
    fun unsafeScopePathsAndMalformedEntriesAreRejected() {
        val invalidDocuments = listOf(
            document("index.js" to "x", scope = "OTHER"),
            document("../escape.js" to "x"),
            document("/absolute.js" to "x"),
            document("index.js" to ""),
            """{"patchId":"x","files":"nope"}""",
            """{not json"""
        )
        invalidDocuments.forEach { text ->
            val decoded = DshConsolePatchPayload.decode(text, state)
            assertTrue(
                "document should be rejected: " + text.take(40),
                decoded is DshConsolePatchPayload.Decoded.Invalid
            )
        }
    }

    @Test
    fun anOversizedDocumentOrFileListIsRejectedBeforeTheOwnerSeesIt() {
        val many = (0 until 64).map { "file-$it.js" to "x" }.toTypedArray()
        assertTrue(
            DshConsolePatchPayload.decode(document(*many), state)
                is DshConsolePatchPayload.Decoded.Invalid
        )
        val huge = "x".repeat(600 * 1024)
        assertTrue(
            DshConsolePatchPayload.decode(document("big.js" to huge), state)
                is DshConsolePatchPayload.Decoded.Invalid
        )
    }

    @Test
    fun aPatchIdIsDerivedDeterministicallyFromTheContentWhenAbsent() {
        val text = JSONObject()
            .put("files", JSONArray().put(JSONObject().put("path", "index.js").put("content", "x")))
            .toString()
        val first = DshConsolePatchPayload.decode(text, state)
        val second = DshConsolePatchPayload.decode(text, state)
        val firstId = (first as DshConsolePatchPayload.Decoded.Valid).proposal.patchId
        assertEquals(firstId, (second as DshConsolePatchPayload.Decoded.Valid).proposal.patchId)
        assertTrue(firstId.startsWith("user-"))
    }
}

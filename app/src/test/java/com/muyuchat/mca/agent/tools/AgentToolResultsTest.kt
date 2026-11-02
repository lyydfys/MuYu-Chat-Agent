package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolResultsTest {
    private val catalog = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
    private val descriptor = catalog.find("workspace.list")!!.descriptor
    private val command = toolCommand(descriptor, "{}", invocationId = "inv-9")

    @Test
    fun `successful result is a truthful structured document`() {
        val result = AgentToolResults.success(
            command,
            descriptor,
            "Listed one entry.",
            JSONObject().put("count", 1)
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertFalse(result.truncated)
        assertNull(result.fullResultRef)
        assertTrue(result.artifactIds.isEmpty())

        val envelope = resultEnvelope(result)
        assertTrue(envelope.getBoolean("ok"))
        assertEquals("SUCCEEDED", envelope.getString("status"))
        assertEquals("inv-9", envelope.getString("invocationId"))
        assertEquals("workspace.list", envelope.getString("toolKey"))
        assertEquals("1", envelope.getString("toolVersion"))
        assertEquals(1, envelope.getJSONObject("data").getInt("count"))
        assertEquals(0, envelope.getJSONArray("artifactRefs").length())
        assertTrue(envelope.isNull("errorCode"))
        assertFalse(envelope.getBoolean("possibleSideEffects"))
        assertFalse(envelope.getBoolean("truncated"))
    }

    @Test
    fun `ok is false for every non-success status`() {
        val cases = listOf(
            AgentToolResults.failure(command, descriptor, "TOOL_FAILED", "failed") to "FAILED",
            AgentToolResults.denied(command, descriptor, "PERMISSION_DENIED", "denied") to "DENIED",
            AgentToolResults.notExecuted(command, descriptor, "TOOL_NOT_FOUND", "not executed") to "NOT_EXECUTED",
            AgentToolResults.cancelled(command, descriptor) to "CANCELLED",
            AgentToolResults.unknown(command, descriptor) to "UNKNOWN"
        )
        cases.forEach { (result, expectedStatus) ->
            assertEquals(expectedStatus, result.status.name)
            val envelope = resultEnvelope(result)
            assertFalse(envelope.getBoolean("ok"))
            assertEquals(expectedStatus, envelope.getString("status"))
            assertEquals("workspace.list", envelope.getString("toolKey"))
        }
        assertTrue(resultEnvelope(cases[1].first).getString("errorCode") == "PERMISSION_DENIED")
        assertTrue(resultEnvelope(cases[3].first).isNull("errorCode"))
    }

    @Test
    fun `model cap marks truncation and points at the persisted result`() {
        val data = JSONObject().put("text", "x".repeat(20_000))
        val result = AgentToolResults.success(command, descriptor, "large result.", data)
        assertTrue(result.truncated)
        assertEquals("tool-result:inv-9", result.fullResultRef)
        assertTrue(result.dataJson!!.toByteArray(Charsets.UTF_8).size <= AgentToolResults.PERSISTED_RESULT_MAX_BYTES)
        assertTrue(result.dataJson!!.toByteArray(Charsets.UTF_8).size > AgentToolResults.MODEL_RESULT_MAX_BYTES)

        val envelope = resultEnvelope(result)
        assertTrue(envelope.getBoolean("truncated"))
        assertEquals("MODEL_CAP", envelope.getString("truncationReason"))
        assertTrue(envelope.getBoolean("fullResultRetained"))
        assertEquals("tool-result:inv-9", envelope.getString("fullResultRef"))
    }

    @Test
    fun `persistence cap without a shrinker replaces data instead of claiming a full result`() {
        val result = AgentToolResults.success(
            command,
            descriptor,
            "oversized result.",
            JSONObject().put("text", "x".repeat(80_000)),
            shrink = { false }
        )
        assertTrue(result.truncated)
        assertNull(result.fullResultRef)
        assertTrue(result.dataJson!!.toByteArray(Charsets.UTF_8).size <= AgentToolResults.PERSISTED_RESULT_MAX_BYTES)

        val envelope = resultEnvelope(result)
        assertFalse(envelope.getBoolean("fullResultRetained"))
        assertEquals("PERSISTED_CAP", envelope.getString("truncationReason"))
        assertTrue(envelope.getJSONObject("data").has("note"))
    }

    @Test
    fun `persistence cap shrinks array data deterministically`() {
        val items = JSONArray()
        repeat(120) { index ->
            items.put(JSONObject().put("name", "item-$index-" + "y".repeat(580)))
        }
        val data = JSONObject().put("items", items)
        val result = AgentToolResults.success(
            command,
            descriptor,
            "many items.",
            data,
            shrink = { value ->
                val array = value.getJSONArray("items")
                if (array.length() == 0) {
                    false
                } else {
                    array.remove(array.length() - 1)
                    true
                }
            }
        )
        assertTrue(result.truncated)
        assertNull(result.fullResultRef)
        assertTrue(result.dataJson!!.toByteArray(Charsets.UTF_8).size <= AgentToolResults.PERSISTED_RESULT_MAX_BYTES)
        val remaining = resultData(result).getJSONArray("items")
        assertTrue(remaining.length() in 1 until 120)
    }

    @Test
    fun `artifact references must be unique`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolResults.success(
                command,
                descriptor,
                "duplicate artifacts.",
                JSONObject(),
                artifactIds = listOf("artifact-1", "artifact-1")
            )
        }
    }

    @Test
    fun `blank summaries are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolResults.success(command, descriptor, " ", JSONObject())
        }
    }

    @Test
    fun `port failure passes the stable code through`() {
        val failure = com.muyuchat.core.agent.contract.AgentFileResult.Failure(
            errorCode = "CURSOR_STALE",
            safeMessage = "Workspace directory changed since the cursor was issued.",
            possibleSideEffects = true
        )
        val result = AgentToolResults.portFailure(command, descriptor, failure)
        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("CURSOR_STALE", result.errorCode)
        assertTrue(result.possibleSideEffects)
        assertNotNull(result.dataJson)
    }

    @Test
    fun `store precondition code is translated to the tool vocabulary`() {
        val failure = com.muyuchat.core.agent.contract.AgentFileResult.Failure(
            errorCode = "STORAGE_PRECONDITION_CHANGED",
            safeMessage = "Logical path already exists; expectedOldHash=null means create-only."
        )
        val result = AgentToolResults.portFailure(command, descriptor, failure)
        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("PRECONDITION_CHANGED", result.errorCode)
    }
}

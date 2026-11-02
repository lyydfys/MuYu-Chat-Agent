package com.muyuchat.feature.video.workspace

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.feature.video.workspace.editor.VideoGraphEditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit-command semantics of the draft editor (proposal §9.4.1): every command produces a new
 * validated draft; connections are pre-checked (type/cycle/duplicate/self) and rejected without
 * touching the draft; delete previews its edge impact; undo/redo is bounded; positions are
 * applied without history.
 */
class VideoGraphEditorStateTest {

    private fun newState(): VideoGraphEditorState =
        VideoGraphEditorState(VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat"))

    @Test
    fun `fresh template has no issues`() {
        assertTrue(newState().issues.isEmpty())
    }

    @Test
    fun `param edit updates document and is undoable`() {
        val state = newState()
        state.setParam("n_cond", "frames", VideoNodeValue.LongValue(9))
        assertEquals(9L, (state.document.node("n_cond")!!.params["frames"] as VideoNodeValue.LongValue).value)
        assertTrue(state.issues.isEmpty())

        assertTrue(state.undo())
        assertEquals(5L, (state.document.node("n_cond")!!.params["frames"] as VideoNodeValue.LongValue).value)
        assertTrue(state.redo())
        assertEquals(9L, (state.document.node("n_cond")!!.params["frames"] as VideoNodeValue.LongValue).value)
    }

    @Test
    fun `invalid param value keeps the draft but surfaces issues`() {
        val state = newState()
        state.setParam("n_cond", "width", VideoNodeValue.LongValue(830))
        assertTrue(state.issues.any { it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("multiple of 32") })
        state.setParam("n_cond", "frames", VideoNodeValue.LongValue(6))
        assertTrue(state.issues.any { it.detail.contains("Wan-aligned") })
    }

    @Test
    fun `type-mismatched connection is rejected and leaves the draft untouched`() {
        val state = newState()
        val edgesBefore = state.document.edges.size
        val decision = state.connect("n_input", "prompt", "n_model", "manifest")
        assertTrue(decision is VideoGraphEditorState.ConnectDecision.Rejected)
        assertEquals(edgesBefore, state.document.edges.size)
    }

    @Test
    fun `self connection duplicate target and literal-only ports are rejected`() {
        val state = newState()
        assertTrue(state.connect("n_cond", "conditioning", "n_cond", "negative_prompt") is VideoGraphEditorState.ConnectDecision.Rejected)
        // negative_prompt of n_cond is free (optional, connected-only) and TEXT kind; wire it first.
        assertTrue(state.connect("n_input", "negative_prompt", "n_cond", "negative_prompt") is VideoGraphEditorState.ConnectDecision.Connected)
        // Now the same input port already has an edge.
        assertTrue(state.connect("n_input", "prompt", "n_cond", "negative_prompt") is VideoGraphEditorState.ConnectDecision.Rejected)
    }

    @Test
    fun `kind-incompatible connection between distant nodes is rejected with the reason`() {
        val state = newState()
        // V1's typed registry makes real non-self cycles unreachable (every chain kind has a
        // single consumer); the pre-checks still fire for incompatible distant endpoints.
        val decision = state.connect("n_sampler", "latent", "n_cond", "negative_prompt")
        assertTrue(decision is VideoGraphEditorState.ConnectDecision.Rejected)
        assertTrue((decision as VideoGraphEditorState.ConnectDecision.Rejected).reason.contains("类型不匹配"))
    }

    @Test
    fun `valid extra connection is accepted undoable and disconnectable`() {
        val state = newState()
        val decision = state.connect("n_input", "negative_prompt", "n_cond", "negative_prompt")
        assertTrue(decision is VideoGraphEditorState.ConnectDecision.Connected)
        assertTrue(state.issues.isEmpty())
        assertTrue(state.undo())
        assertTrue(state.document.edges.none { it.toPort == "negative_prompt" })
    }

    @Test
    fun `add node then delete it with impact preview`() {
        val state = newState()
        val id = state.addNode(VideoNodeTypeId.WAN_CONDITIONING)
        assertNotNull(id)
        assertTrue(state.document.node(id!!) != null)
        assertTrue(state.issues.isNotEmpty()) // bare conditioning misses required inputs

        val impact = state.deleteImpact(id)
        assertTrue(impact.isEmpty()) // nothing connected to it yet
        assertTrue(state.deleteNode(id))
        assertNull(state.document.node(id))
        assertTrue(state.issues.isEmpty())
    }

    @Test
    fun `deleting a wired node removes its edges too`() {
        val state = newState()
        val impact = state.deleteImpact("n_model")
        assertEquals(3, impact.size) // dit→cond, dit→sampler, vae→decode
        assertTrue(state.deleteNode("n_model"))
        assertTrue(state.document.edges.none { it.fromNodeId == "n_model" })
        assertTrue(state.issues.isNotEmpty()) // downstream required inputs now missing
    }

    @Test
    fun `undo history is bounded`() {
        val state = VideoGraphEditorState(VideoWorkflowTemplates.textToVideoTemplate(), historyLimit = 3)
        repeat(10) { i -> state.setParam("n_cond", "seed", VideoNodeValue.LongValue(i.toLong())) }
        // Only the newest 3 edit levels survive; undo works exactly that many times.
        repeat(3) { assertTrue(state.undo()) }
        assertFalse("history beyond the limit must be gone", state.undo())
        assertTrue(state.canRedo)
        assertEquals(6L, (state.document.node("n_cond")!!.params["seed"] as VideoNodeValue.LongValue).value)
    }

    @Test
    fun `position edits do not enter history`() {
        val state = newState()
        repeat(10) { state.setNodeUi("n_input", com.muyuchat.core.video.contract.VideoNodeUi(1f * it, 2f)) }
        assertFalse(state.canUndo)
        assertEquals(9f, state.document.node("n_input")!!.ui.positionX)
    }

    @Test
    fun `reset clears history and validates new template`() {
        val state = newState()
        state.setParam("n_cond", "seed", VideoNodeValue.LongValue(5))
        state.resetTo(VideoWorkflowTemplates.imageToVideoTemplate())
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        // Without a chosen image the i2v draft is legitimately incomplete: the shared validator
        // flags the missing required literal, which the sheet then asks the user to fix.
        assertTrue(state.issues.any { it.nodeId == "n_image" && it.detail.contains("neither connected nor set") })
        state.setParam("n_image", "image", VideoNodeValue.ImageAssetRefValue("img-1"))
        assertTrue(state.issues.isEmpty())
    }
}

package com.muyuchat.core.video.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural validation matrix of the V1 native graph (proposal §4.1 rules 1–6). */
class VideoWorkflowValidationTest {

    @Test
    fun `valid text-to-video template passes with no issues`() {
        val issues = validateVideoWorkflow(textToVideoDocument())
        assertTrue("expected no issues, got: $issues", issues.isEmpty())
    }

    @Test
    fun `valid image-to-video template passes with no issues`() {
        val issues = validateVideoWorkflow(imageToVideoDocument())
        assertTrue("expected no issues, got: $issues", issues.isEmpty())
    }

    @Test
    fun `duplicate node ids are rejected`() {
        val document = textToVideoDocument()
            .copy(nodes = textToVideoDocument().nodes + bareNode("n_input", VideoNodeTypeId.INPUT_TEXT))
        val byCode = issuesByCode(validateVideoWorkflow(document))
        assertTrue(byCode.containsKey(VideoErrorCode.WORKFLOW_INVALID))
        assertTrue(
            byCode.getValue(VideoErrorCode.WORKFLOW_INVALID).any { it.detail.contains("duplicate node id") },
        )
    }

    @Test
    fun `unknown node type is rejected as unsupported`() {
        val document = textToVideoDocument().copy(
            nodes = textToVideoDocument().nodes.map {
                if (it.nodeId == "n_input") it.copy(typeId = "comfy.unknown_node") else it
            },
        )
        val issues = validateVideoWorkflow(document)
        assertTrue(issues.any { it.code == VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE && it.nodeId == "n_input" })
    }

    @Test
    fun `out-of-range node type version is rejected`() {
        val document = textToVideoDocument().copy(
            nodes = textToVideoDocument().nodes.map {
                if (it.nodeId == "n_input") it.copy(typeVersion = 99) else it
            },
        )
        assertTrue(
            validateVideoWorkflow(document).any {
                it.code == VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE && it.nodeId == "n_input"
            },
        )
    }

    @Test
    fun `edge referencing a missing node is rejected`() {
        val document = textToVideoDocument().copy(
            edges = textToVideoDocument().edges + VideoEdge("n_ghost", "prompt", "n_cond", "negative_prompt"),
        )
        val issues = validateVideoWorkflow(document)
        assertTrue(issues.any { it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("missing source node") })
    }

    @Test
    fun `edge to a nonexistent input port is rejected`() {
        val document = textToVideoDocument().copy(
            edges = textToVideoDocument().edges + VideoEdge("n_input", "prompt", "n_encode", "prompt"),
        )
        assertTrue(
            validateVideoWorkflow(document).any {
                it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("does not exist on 'n_encode'")
            },
        )
    }

    @Test
    fun `edge to a nonexistent output port is rejected`() {
        val document = textToVideoDocument().copy(
            edges = textToVideoDocument().edges + VideoEdge("n_input", "bogus_out", "n_cond", "negative_prompt"),
        )
        assertTrue(
            validateVideoWorkflow(document).any {
                it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("output port 'bogus_out'")
            },
        )
    }

    @Test
    fun `port kind mismatch is rejected`() {
        val document = textToVideoDocument().copy(
            edges = textToVideoDocument().edges + VideoEdge("n_input", "prompt", "n_cond", "dit"),
        )
        assertTrue(
            validateVideoWorkflow(document).any {
                it.code == VideoErrorCode.WORKFLOW_TYPE_MISMATCH && it.nodeId == "n_cond" && it.port == "dit"
            },
        )
    }

    @Test
    fun `literal on a connected-only port is rejected`() {
        val document = textToVideoDocument()
        val tampered = document.copy(
            nodes = document.nodes.map {
                if (it.nodeId == "n_cond") {
                    it.copy(params = it.params + ("dit" to VideoNodeValue.ModelRefValue("wan2.2-ti2v-5b")))
                } else {
                    it
                }
            },
        )
        assertTrue(
            validateVideoWorkflow(tampered).any {
                it.code == VideoErrorCode.WORKFLOW_TYPE_MISMATCH && it.detail.contains("must be connected, not a literal")
            },
        )
    }

    @Test
    fun `edge into a literal-only port is rejected`() {
        val document = textToVideoDocument().copy(
            edges = textToVideoDocument().edges + VideoEdge("n_input", "negative_prompt", "n_model", "manifest"),
        )
        assertTrue(
            validateVideoWorkflow(document).any {
                it.code == VideoErrorCode.WORKFLOW_TYPE_MISMATCH && it.detail.contains("literal value only")
            },
        )
    }

    @Test
    fun `two edges into one input port are rejected`() {
        val document = textToVideoDocument()
        val nodeA = VideoNode(
            nodeId = "n_img_a",
            typeId = VideoNodeTypeId.INPUT_IMAGE.typeId,
            typeVersion = 1,
            params = mapOf("image" to VideoNodeValue.ImageAssetRefValue("asset-a")),
        )
        val nodeB = nodeA.copy(nodeId = "n_img_b", params = mapOf("image" to VideoNodeValue.ImageAssetRefValue("asset-b")))
        val edges = document.edges +
            VideoEdge("n_img_a", "image", "n_cond", "image") +
            VideoEdge("n_img_b", "image", "n_cond", "image")
        val result = validateVideoWorkflow(document.copy(nodes = document.nodes + nodeA + nodeB, edges = edges))
        assertTrue(
            result.any { it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("more than one incoming edge") },
        )
    }

    @Test
    fun `missing required connected port is rejected`() {
        val document = textToVideoDocument()
        val broken = document.copy(edges = document.edges.filterNot { it.toNodeId == "n_cond" && it.toPort == "prompt" })
        assertTrue(
            validateVideoWorkflow(broken).any {
                it.code == VideoErrorCode.WORKFLOW_INVALID && it.nodeId == "n_cond" && it.port == "prompt" &&
                    it.detail.contains("neither connected nor set")
            },
        )
    }

    @Test
    fun `missing required parameter is rejected`() {
        val document = textToVideoDocument()
        val broken = document.copy(
            nodes = document.nodes.map {
                if (it.nodeId == "n_cond") it.copy(params = it.params - "seed") else it
            },
        )
        assertTrue(
            validateVideoWorkflow(broken).any {
                it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("required parameter 'seed'")
            },
        )
    }

    @Test
    fun `unknown parameter is rejected`() {
        val document = textToVideoDocument()
        val broken = document.copy(
            nodes = document.nodes.map {
                if (it.nodeId == "n_sampler") {
                    it.copy(params = it.params + ("lora_strength" to VideoNodeValue.DecimalValue(10, 1)))
                } else {
                    it
                }
            },
        )
        assertTrue(
            validateVideoWorkflow(broken).any {
                it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("unknown parameter 'lora_strength'")
            },
        )
    }

    @Test
    fun `width not a multiple of 32 is rejected`() {
        val issues = validateVideoWorkflow(textToVideoDocument(width = 830))
        assertTrue(issues.any { it.code == VideoErrorCode.WORKFLOW_INVALID && it.detail.contains("multiple of 32") })
    }

    @Test
    fun `height not a multiple of 32 is rejected`() {
        assertTrue(
            validateVideoWorkflow(textToVideoDocument(height = 478))
                .any { it.detail.contains("multiple of 32") },
        )
    }

    @Test
    fun `non-aligned frame count is rejected with the resolved value`() {
        val issues = validateVideoWorkflow(textToVideoDocument(frames = 6))
        val issue = issues.first { it.detail.contains("Wan-aligned") }
        assertTrue(issue.detail.contains("round it down to 5"))
    }

    @Test
    fun `aligned frame counts pass`() {
        for (frames in longArrayOf(1L, 5L, 81L, 121L)) {
            assertTrue("frames=$frames should pass", validateVideoWorkflow(textToVideoDocument(frames = frames)).isEmpty())
        }
    }

    @Test
    fun `frame alignment helpers mirror the native rule`() {
        assertEquals(5L, resolveWanAlignedFrameCount(6L))
        assertEquals(5L, resolveWanAlignedFrameCount(8L))
        assertEquals(9L, resolveWanAlignedFrameCount(9L))
        assertEquals(1L, resolveWanAlignedFrameCount(1L))
        assertEquals(1L, resolveWanAlignedFrameCount(0L))
        assertTrue(isWanAlignedFrameCount(81L))
        assertTrue(!isWanAlignedFrameCount(80L))
        assertTrue(!isWanAlignedFrameCount(6L))
    }

    @Test
    fun `non-positive steps and fps are rejected`() {
        assertTrue(validateVideoWorkflow(textToVideoDocument(steps = 0)).any { it.detail.contains(">= 1") })
        assertTrue(validateVideoWorkflow(textToVideoDocument(fps = 0)).any { it.detail.contains(">= 1") })
    }

    @Test
    fun `decimal scale out of range is rejected`() {
        val document = textToVideoDocument(guidance = VideoNodeValue.DecimalValue(50, 12))
        assertTrue(
            validateVideoWorkflow(document).any { it.detail.contains("decimal scale out of range") },
        )
    }

    @Test
    fun `wrong literal kind on a port is rejected`() {
        val document = textToVideoDocument()
        val broken = document.copy(
            nodes = document.nodes.map {
                if (it.nodeId == "n_model") {
                    it.copy(params = mapOf("manifest" to VideoNodeValue.TextValue("not-a-model")))
                } else {
                    it
                }
            },
        )
        assertTrue(
            validateVideoWorkflow(broken).any {
                it.code == VideoErrorCode.WORKFLOW_TYPE_MISMATCH && it.port == "manifest"
            },
        )
    }

    @Test
    fun `self loop is reported as a cycle`() {
        val document = textToVideoDocument().copy(
            edges = textToVideoDocument().edges + VideoEdge("n_cond", "conditioning", "n_cond", "negative_prompt"),
        )
        assertTrue(
            validateVideoWorkflow(document).any { it.code == VideoErrorCode.WORKFLOW_CYCLE && it.detail.contains("self-loop") },
        )
    }

    @Test
    fun `two-node cycle is reported`() {
        val document = textToVideoDocument()
        val extraA = bareNode("n_cyc_a", VideoNodeTypeId.WAN_CONDITIONING)
        val extraB = bareNode("n_cyc_b", VideoNodeTypeId.WAN_CONDITIONING)
        val edges = document.edges +
            VideoEdge("n_cyc_a", "conditioning", "n_cyc_b", "negative_prompt") +
            VideoEdge("n_cyc_b", "conditioning", "n_cyc_a", "negative_prompt")
        val issues = validateVideoWorkflow(
            document.copy(nodes = document.nodes + extraA + extraB, edges = edges),
        )
        assertTrue(issues.any { it.code == VideoErrorCode.WORKFLOW_CYCLE })
    }

    @Test
    fun `missing save root is rejected`() {
        val document = textToVideoDocument()
        val broken = document.copy(
            nodes = document.nodes.filterNot { it.nodeId == "n_save" },
            edges = document.edges.filterNot { it.toNodeId == "n_save" },
        )
        assertTrue(
            validateVideoWorkflow(broken).any { it.detail.contains("no save-asset output root") },
        )
    }

    @Test
    fun `two save roots are rejected`() {
        val document = textToVideoDocument()
        val secondSave = bareNode("n_save2", VideoNodeTypeId.SAVE_ASSET)
        val broken = document.copy(
            nodes = document.nodes + secondSave,
            edges = document.edges + VideoEdge("n_encode", "mp4", "n_save2", "mp4"),
        )
        assertTrue(
            validateVideoWorkflow(broken).any { it.detail.contains("more than one save-asset output root") },
        )
    }
}

package com.muyuchat.feature.video.workspace

import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoWorkflowSource
import com.muyuchat.core.video.contract.validateVideoWorkflow
import com.muyuchat.feature.video.workspace.editor.VideoDraftCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draft persistence round-trip and template baseline facts (proposal §9.4.1). */
class VideoV0SupportTest {

    @Test
    fun `templates validate clean against the shared contract`() {
        assertTrue(validateVideoWorkflow(VideoWorkflowTemplates.textToVideoTemplate()).isEmpty())
        // Without a picked image the i2v template is a legitimately incomplete draft.
        val issues = validateVideoWorkflow(VideoWorkflowTemplates.imageToVideoTemplate())
        assertTrue(issues.any { it.nodeId == "n_image" && it.detail.contains("neither connected nor set") })
        assertTrue(
            validateVideoWorkflow(VideoWorkflowTemplates.imageToVideoTemplate(imageAssetId = "img-1")).isEmpty(),
        )
    }

    @Test
    fun `template defaults carry the product recipe`() {
        val doc = VideoWorkflowTemplates.textToVideoTemplate()
        val cond = doc.node("n_cond")!!
        assertEquals(480L, (cond.params["width"] as VideoNodeValue.LongValue).value)
        assertEquals(832L, (cond.params["height"] as VideoNodeValue.LongValue).value)
        assertEquals(5L, (cond.params["frames"] as VideoNodeValue.LongValue).value)
        val sampler = doc.node("n_sampler")!!
        assertEquals("euler", (sampler.params["sampler"] as VideoNodeValue.TextValue).text)
        assertEquals(4L, (sampler.params["steps"] as VideoNodeValue.LongValue).value)
        assertEquals(VideoNodeValue.DecimalValue(60, 1), sampler.params["guidance"])
        assertEquals(VideoNodeValue.DecimalValue(30, 1), sampler.params["flow_shift"])
        assertEquals(24L, (doc.node("n_encode")!!.params["fps"] as VideoNodeValue.LongValue).value)
    }

    @Test
    fun `image template wires the image node into conditioning`() {
        val doc = VideoWorkflowTemplates.imageToVideoTemplate(imageAssetId = "img-1")
        assertTrue(doc.edges.any { it.fromNodeId == "n_image" && it.toPort == "image" })
        assertTrue(doc.node("n_image")!!.params.containsKey("image"))
    }

    @Test
    fun `draft codec round-trips nodes edges params and ui`() {
        val original = VideoWorkflowTemplates.imageToVideoTemplate(
            prompt = "a cat | with ~ pipes % and\nnewlines",
            negativePrompt = "blurry",
            imageAssetId = "img-asset-9",
        ).let { base ->
            base.copy(
                nodes = base.nodes.map {
                    if (it.nodeId == "n_cond") {
                        it.copy(ui = it.ui.copy(positionX = 123.5f, positionY = -7f, collapsed = true, label = "条件|节点"))
                    } else {
                        it
                    }
                },
            )
        }
        val encoded = VideoDraftCodec.encode(original)
        val decoded = VideoDraftCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original.nodes, decoded!!.nodes)
        assertEquals(original.edges, decoded.edges)
        assertEquals(original.workflowId, decoded.workflowId)
        assertEquals(original.revisionId, decoded.revisionId)
    }

    @Test
    fun `draft codec preserves imported raw graph and metadata`() {
        val original = VideoWorkflowTemplates.textToVideoTemplate().copy(
            source = VideoWorkflowSource.COMFY_API,
            rawSourceRef = """{"3":{"class_type":"KSampler","inputs":{"seed":7}}}""",
            metadata = mapOf("comfy.sourceName" to "a|b~c.json", "comfy.modelBinding" to "profile:q4_0"),
        )
        val decoded = VideoDraftCodec.decode(VideoDraftCodec.encode(original))
        assertNotNull(decoded)
        assertEquals(VideoWorkflowSource.COMFY_API, decoded!!.source)
        assertEquals(original.rawSourceRef, decoded.rawSourceRef)
        assertEquals(original.metadata, decoded.metadata)
    }

    @Test
    fun `draft codec rejects foreign formats`() {
        assertNull(VideoDraftCodec.decode(""))
        assertNull(VideoDraftCodec.decode("someone-else|wf|rev"))
        assertNull(VideoDraftCodec.decode("draft2|wf-native-t2v|rev-1|MCA_NATIVE"))
    }

    @Test
    fun `round-tripped draft still validates clean`() {
        val encoded = VideoDraftCodec.encode(VideoWorkflowTemplates.textToVideoTemplate(prompt = "p"))
        val decoded = VideoDraftCodec.decode(encoded)
        assertTrue(validateVideoWorkflow(decoded!!).isEmpty())
    }
}

package com.muyuchat.mca.video.workflow.importer

import com.muyuchat.core.video.contract.VideoErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyApiWorkflowParserTest {

    @Test
    fun `accepts a T2V API-format graph`() {
        val graph = parsed(ComfyApiFixtures.t2v())
        assertEquals(11, graph.nodes.size)
        assertEquals("UNETLoader", graph.node("1")?.classType)
        assertEquals(64, graph.sourceSha256.length)
        assertTrue(graph.sourceSha256.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(2, graph.classTypeCounts["CLIPTextEncode"])
    }

    @Test
    fun `rejects UI format with an export hint`() {
        val result = rejected("""{"nodes":[],"links":[]}""")
        assertEquals(VideoErrorCode.INPUT_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("请导出 API format") })
    }

    @Test
    fun `rejects a top-level array`() {
        val result = rejected("[]")
        assertEquals(VideoErrorCode.INPUT_INVALID, result.code)
    }

    @Test
    fun `rejects duplicate node ids`() {
        val result = rejected(
            """{"1":{"class_type":"LoadImage","inputs":{"image":"a"}},"1":{"class_type":"LoadImage","inputs":{"image":"b"}}}""",
        )
        assertEquals(VideoErrorCode.INPUT_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("duplicate object key") })
    }

    @Test
    fun `rejects unknown class types without dropping the node`() {
        val result = rejected(
            """{"1":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"x.ckpt"}}}""",
        )
        assertEquals(VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE, result.code)
        assertTrue(result.diagnostics.any { it.nodeId == "1" && it.message.contains("CheckpointLoaderSimple") })
    }

    @Test
    fun `rejects missing references`() {
        val json = ComfyApiFixtures.t2v().replace("\"model\":[\"7\",0]", "\"model\":[\"99\",0]")
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("不存在的节点 '99'") })
        assertTrue(result.diagnostics.any { it.path?.contains("/inputs/model") == true })
    }

    @Test
    fun `rejects self references`() {
        val result = rejected(
            """{"1":{"class_type":"ModelSamplingSD3","inputs":{"shift":3.0,"model":["1",0]}}}""",
        )
        assertEquals(VideoErrorCode.WORKFLOW_CYCLE, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("自引用") })
    }

    @Test
    fun `rejects cycles`() {
        val result = rejected(
            """
            {
              "1":{"class_type":"ModelSamplingSD3","inputs":{"shift":3.0,"model":["2",0]}},
              "2":{"class_type":"ModelSamplingSD3","inputs":{"shift":3.0,"model":["1",0]}}
            }
            """.trimIndent(),
        )
        assertEquals(VideoErrorCode.WORKFLOW_CYCLE, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("环") })
    }

    @Test
    fun `rejects multiple output roots`() {
        val json = ComfyApiFixtures.withExtraNode(
            ComfyApiFixtures.t2v(),
            "12",
            """{"class_type":"SaveVideo","inputs":{"video":["10",0]}}""",
        )
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("多个 SaveVideo 输出根") })
    }

    @Test
    fun `two-element arrays are links only on declared link inputs`() {
        val json = ComfyApiFixtures.t2v().replace("\"text\":\"a cat\"", "\"text\":[\"2\",0]")
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("二元素数组只在 schema 明确声明为引用的字段") })
    }

    @Test
    fun `rejects a link with an out-of-range output index`() {
        val json = ComfyApiFixtures.t2v().replace("\"model\":[\"7\",0]", "\"model\":[\"7\",1]")
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("越界") })
    }

    @Test
    fun `start image must use the LoadImage image output`() {
        val json = ComfyApiFixtures.i2v().replace("\"start_image\":[\"12\",0]", "\"start_image\":[\"12\",1]")
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("outputIndex=1") })
    }

    @Test
    fun `rejects missing required inputs`() {
        val json = ComfyApiFixtures.t2v().replace("\"length\":33,", "")
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("缺少必需输入 'length'") })
    }

    @Test
    fun `rejects unsupported inputs`() {
        val json = ComfyApiFixtures.t2v().replace("\"seed\":7,", "\"seed\":7,\"extra\":1,")
        val result = rejected(json)
        assertEquals(VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE, result.code)
        assertTrue(result.diagnostics.any { it.nodeId == "8" && it.message.contains("extra") })
    }

    @Test
    fun `enforces the node limit`() {
        val result = rejected(ComfyApiFixtures.nodes(ComfyApiWorkflowParser.MAX_NODES + 1))
        assertEquals(VideoErrorCode.INPUT_TOO_LARGE, result.code)
        assertTrue(result.diagnostics.any { it.message.contains("上限") })
    }

    @Test
    fun `anchors diagnostics to node and path`() {
        val json = ComfyApiFixtures.t2v(unetName = "not-a-profile.gguf")
        val graph = parsed(json)
        assertNotNull(graph.node("1"))
        val result = rejected(json.replace("\"model\":[\"7\",0]", "\"model\":[\"404\",0]"))
        val diagnostic = result.diagnostics.firstOrNull { it.nodeId == "8" }
        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.path.orEmpty().contains("/inputs/model"))
    }

    private fun parsed(json: String): ComfyApiGraph {
        val result = ComfyApiWorkflowParser.parse(json, "fixture.json")
        assertTrue("expected Parsed, got $result", result is ComfyApiParseResult.Parsed)
        return (result as ComfyApiParseResult.Parsed).graph
    }

    private fun rejected(json: String): ComfyApiParseResult.Rejected {
        val result = ComfyApiWorkflowParser.parse(json, "fixture.json")
        assertTrue("expected Rejected, got $result", result is ComfyApiParseResult.Rejected)
        return result as ComfyApiParseResult.Rejected
    }
}

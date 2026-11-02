package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoErrorCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteComfyWorkflowTest {

    @Test
    fun acceptsApiFormatAndBuildsPromptBody() {
        val workflow = RemoteComfyWorkflow.parse(
            """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"hello"}},"2":{"class_type":"KSampler","inputs":{}}}""",
        )
        assertEquals(2, workflow.nodeClasses.size)
        assertEquals("CLIPTextEncode", workflow.nodeClasses["1"])

        val body = RemoteComfyWorkflow.promptBody(workflow, "client-abc")
        val root = JSONObject(String(body, Charsets.UTF_8))
        assertEquals("client-abc", root.getString("client_id"))
        assertEquals(
            "hello",
            root.getJSONObject("prompt")
                .getJSONObject("1")
                .getJSONObject("inputs")
                .getString("text"),
        )
    }

    @Test
    fun rejectsUiFormatAndEmptyGraph() {
        val ui = expectError("""{"nodes":[],"links":[]}""")
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, ui.code)

        val empty = expectError("{}")
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, empty.code)
    }

    @Test
    fun rejectsOversizedNodeCount() {
        val graph = JSONObject()
        for (index in 0 until RemoteComfyWorkflow.MAX_NODES + 1) {
            graph.put(index.toString(), JSONObject().put("class_type", "KSampler"))
        }
        val error = expectError(graph.toString())
        assertEquals(VideoErrorCode.INPUT_TOO_LARGE, error.code)
    }

    @Test
    fun rejectsMalformedNodes() {
        assertEquals(VideoErrorCode.WORKFLOW_INVALID, expectError("""{"1":"not-an-object"}""").code)
        assertEquals(
            VideoErrorCode.WORKFLOW_INVALID,
            expectError("""{"1":{"inputs":{}}}""").code,
        )
        assertEquals(
            VideoErrorCode.WORKFLOW_INVALID,
            expectError("""{"1":{"class_type":42,"inputs":{}}}""").code,
        )
    }

    @Test
    fun rejectsLoadImageWithoutDeletingTheNode() {
        val error = expectError("""{"1":{"class_type":"LoadImage","inputs":{}}}""")
        assertEquals(VideoErrorCode.INPUT_INVALID, error.code)
        assertEquals(RemoteComfyWorkflow.UPLOAD_UNSUPPORTED_MESSAGE, error.message)
    }

    @Test
    fun rejectsUploadClassesCaseInsensitively() {
        assertTrue(
            expectError("""{"1":{"class_type":"VHS_UploadImage","inputs":{}}}""").message
                .orEmpty()
                .contains("暂不支持远端图生视频上传"),
        )
        assertTrue(
            expectError("""{"1":{"class_type":"ETN_LoadImageBase64","inputs":{}}}""").message
                .orEmpty()
                .contains("暂不支持远端图生视频上传"),
        )
        assertTrue(
            expectError("""{"1":{"class_type":"Load_Image","inputs":{}}}""").message
                .orEmpty()
                .contains("暂不支持远端图生视频上传"),
        )
    }

    @Test
    fun promptIdMustBeNonEmptyBoundedString() {
        assertEquals("abc", RemoteComfyWorkflow.readPromptId(JSONObject("""{"prompt_id":"abc"}""")))
        assertThrows(RemoteComfyProtocolException::class.java) {
            RemoteComfyWorkflow.readPromptId(null)
        }
        assertThrows(RemoteComfyProtocolException::class.java) {
            RemoteComfyWorkflow.readPromptId(JSONObject("""{"prompt_id":""}"""))
        }
        assertThrows(RemoteComfyProtocolException::class.java) {
            RemoteComfyWorkflow.readPromptId(JSONObject("""{"prompt_id":42}"""))
        }
        assertThrows(RemoteComfyProtocolException::class.java) {
            RemoteComfyWorkflow.readPromptId(JSONObject("""{"prompt_id":".."}"""))
        }
        assertThrows(RemoteComfyProtocolException::class.java) {
            RemoteComfyWorkflow.readPromptId(JSONObject("""{"prompt_id":"a/b"}"""))
        }
    }

    private fun expectError(raw: String): RemoteComfyWorkflowException {
        try {
            RemoteComfyWorkflow.parse(raw)
        } catch (error: RemoteComfyWorkflowException) {
            return error
        }
        throw AssertionError("expected a workflow error")
    }
}

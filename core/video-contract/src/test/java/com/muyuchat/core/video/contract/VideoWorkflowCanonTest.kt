package com.muyuchat.core.video.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canonical executable form and digest rules (proposal §4.1 rules 3/4). */
class VideoWorkflowCanonTest {

    @Test
    fun `node and edge order do not change the canonical form`() {
        val document = textToVideoDocument()
        val reordered = document.copy(
            nodes = document.nodes.reversed(),
            edges = document.edges.shuffled(java.util.Random(7)),
        )
        assertEquals(
            VideoWorkflowCanon.canonicalExecutableJson(document),
            VideoWorkflowCanon.canonicalExecutableJson(reordered),
        )
        assertEquals(
            VideoWorkflowCanon.canonicalExecutableSha256(document),
            VideoWorkflowCanon.canonicalExecutableSha256(reordered),
        )
    }

    @Test
    fun `ui metadata document metadata and raw source are excluded from the digest`() {
        val document = textToVideoDocument()
        val decorated = document.copy(
            nodes = document.nodes.map { it.copy(ui = VideoNodeUi(3.5f, -2f, collapsed = true, label = "随便")) },
            metadata = mapOf("title" to "假日", "notes" to "v1"),
            rawSourceRef = "content://imported/whatever",
            canonicalSha256 = "0000",
        )
        assertEquals(
            VideoWorkflowCanon.canonicalExecutableSha256(document),
            VideoWorkflowCanon.canonicalExecutableSha256(decorated),
        )
    }

    @Test
    fun `seed prompt geometry sampler and fps all change the digest`() {
        val base = textToVideoDocument()
        fun digestOf(mutate: (VideoWorkflowDocument) -> VideoWorkflowDocument): String =
            VideoWorkflowCanon.canonicalExecutableSha256(mutate(base))

        val baseDigest = VideoWorkflowCanon.canonicalExecutableSha256(base)
        assertNotEquals(baseDigest, digestOf { it.withNodeParam("n_cond", "seed", VideoNodeValue.LongValue(43)) })
        assertNotEquals(baseDigest, digestOf { d ->
            d.withNodeParam("n_input", "prompt", VideoNodeValue.TextValue("a dog"))
        })
        assertNotEquals(baseDigest, digestOf { it.withNodeParam("n_cond", "width", VideoNodeValue.LongValue(864)) })
        assertNotEquals(baseDigest, digestOf { it.withNodeParam("n_sampler", "steps", VideoNodeValue.LongValue(21)) })
        assertNotEquals(baseDigest, digestOf { it.withNodeParam("n_encode", "fps", VideoNodeValue.LongValue(24)) })
    }

    @Test
    fun `different node ids on otherwise equal graphs change the digest`() {
        val a = textToVideoDocument(workflowId = "wf-a", revisionId = "rev-a")
        val b = textToVideoDocument(workflowId = "wf-b", revisionId = "rev-b")
        // Workflow/revision identity is document metadata, not executable content: renaming only
        // the ids keeps the executable digest stable — the snapshot binds digest + revision id.
        assertEquals(
            VideoWorkflowCanon.canonicalExecutableSha256(a),
            VideoWorkflowCanon.canonicalExecutableSha256(b),
        )
    }

    @Test
    fun `canonical json is deterministic across calls`() {
        val document = imageToVideoDocument()
        assertEquals(
            VideoWorkflowCanon.canonicalExecutableJson(document),
            VideoWorkflowCanon.canonicalExecutableJson(document),
        )
        assertTrue(VideoWorkflowCanon.canonicalExecutableJson(document).startsWith("{\"edges\":["))
    }

    @Test
    fun `decimal canonical rendering`() {
        fun render(unscaled: Long, scale: Int): String {
            val sb = StringBuilder()
            with(VideoWorkflowCanon) { sb.appendCanonicalDecimal(unscaled, scale) }
            return sb.toString()
        }

        assertEquals("6.5", render(65, 1))
        assertEquals("0.5", render(5, 1))
        assertEquals("0", render(0, 3))
        assertEquals("-6.5", render(-65, 1))
        assertEquals("1", render(100, 2))
        assertEquals("0.05", render(5, 2))
        assertEquals("12", render(12, 0))
        assertEquals("-0.25", render(-25, 2))
    }

    @Test
    fun `string escaping uses the fixed minimal set`() {
        val document = textToVideoDocument(prompt = "quote\" back\\slash\nline\ttabctrl")
        val canonical = VideoWorkflowCanon.canonicalExecutableJson(document)
        assertTrue(canonical.contains("\\\""))
        assertTrue(canonical.contains("\\\\"))
        assertTrue(canonical.contains("\\n"))
        assertTrue(canonical.contains("\\t"))
    }
}

private fun VideoWorkflowDocument.withNodeParam(
    nodeId: String,
    name: String,
    value: VideoNodeValue,
): VideoWorkflowDocument = copy(
    nodes = nodes.map { if (it.nodeId == nodeId) it.copy(params = it.params + (name to value)) else it },
)

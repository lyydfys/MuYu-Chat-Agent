package com.muyuchat.mca.agent.tools

import com.muyuchat.core.dsh.a11y.policy.A11yCommand
import com.muyuchat.core.dsh.a11y.policy.A11yOutcome
import com.muyuchat.core.dsh.a11y.policy.A11yScreenshot
import com.muyuchat.core.dsh.a11y.policy.A11yTreeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `device.screen` visual fallback: `read` may return a screenshot, and the screenshot half
 * is allowed to fail *without* failing the observation.
 *
 * The rule these tests protect is one-directional: an image can only ever be added. If the capture,
 * the file read or the encoder fails, the text observation still succeeds with an honest note —
 * otherwise a phone whose screenshot permission is unavailable would look like a broken screen tool.
 */
class AgentScreenPortImageFallbackTest {

    private val dataUrl = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ=="

    private class ScriptedEngine(
        private val onDump: () -> A11yOutcome,
        private val onScreenshot: () -> A11yOutcome
    ) : AgentScreenEngine {
        val commands = mutableListOf<A11yCommand>()

        override suspend fun execute(command: A11yCommand): A11yOutcome {
            commands += command
            return when (command) {
                A11yCommand.Dump -> onDump()
                is A11yCommand.Screenshot -> onScreenshot()
                else -> A11yOutcome.Rejected(
                    com.muyuchat.core.dsh.a11y.policy.A11yRejection.invalid("unexpected command")
                )
            }
        }
    }

    private fun snapshot(nodes: Int = 0) = A11yOutcome.Dump(
        A11yTreeSnapshot(
            generation = 1L,
            packageName = "com.example",
            windowId = 1,
            nodes = emptyList(),
            truncated = false,
            textTruncated = false,
            redactedCount = 0
        )
    )

    private fun screenshot() = A11yOutcome.Screenshot(
        A11yScreenshot(
            path = "/tmp/screen.png",
            screenW = 1080,
            screenH = 2400,
            imageW = 1080,
            imageH = 2400,
            width = 1080,
            height = 2400,
            scaleX = 1.0,
            scaleY = 1.0,
            grid = 0,
            bytes = 1_024,
            redactedRegions = 0,
            hint = null
        )
    )

    private fun port(
        engine: AgentScreenEngine,
        encodeImage: (ByteArray) -> String? = { dataUrl },
        readScreenshot: (String) -> ByteArray? = { byteArrayOf(1, 2, 3) }
    ) = AndroidAgentScreenPort(
        engineProvider = { engine },
        snapshotParser = { "WLAN (tap)" },
        encodeImage = encodeImage,
        readScreenshot = readScreenshot
    )

    private fun read(port: AndroidAgentScreenPort, includeImage: Boolean) = runBlocking {
        port.act(if (includeImage) AgentScreenAction.ReadScreenshot else AgentScreenAction.ReadScreen)
    }

    @Test
    fun `read never captures a screenshot`() {
        val engine = ScriptedEngine(onDump = { snapshot() }, onScreenshot = { error("must not capture") })

        val outcome = read(port(engine), includeImage = false)

        assertTrue(outcome is AgentScreenOutcome.Observed)
        assertEquals("WLAN (tap)", (outcome as AgentScreenOutcome.Observed).text)
        assertNull(outcome.imageDataUrl)
        assertEquals(listOf<A11yCommand>(A11yCommand.Dump), engine.commands)
    }

    @Test
    fun `screenshot returns the encoded image beside the text`() {
        val engine = ScriptedEngine(onDump = { snapshot() }, onScreenshot = { screenshot() })

        val outcome = read(port(engine), includeImage = true) as AgentScreenOutcome.Observed

        assertEquals("WLAN (tap)", outcome.text)
        assertEquals(dataUrl, outcome.imageDataUrl)
        assertTrue(outcome.imageDataUrl!!.startsWith("data:image/jpeg;base64,"))
        assertEquals(
            listOf<A11yCommand>(A11yCommand.Dump, A11yCommand.Screenshot(grid = 0, displayId = 0)),
            engine.commands
        )
    }

    @Test
    fun `a failed capture keeps the text observation and explains the missing image`() {
        val engine = ScriptedEngine(
            onDump = { snapshot() },
            onScreenshot = {
                A11yOutcome.Rejected(
                    com.muyuchat.core.dsh.a11y.policy.A11yRejection.internal("capture refused")
                )
            }
        )

        val outcome = read(port(engine), includeImage = true) as AgentScreenOutcome.Observed

        assertTrue("the text half must survive", outcome.text.startsWith("WLAN (tap)"))
        assertTrue("the model must be told why there is no image", outcome.text.contains("screenshot unavailable"))
        assertTrue(outcome.text.contains("capture refused"))
        assertNull(outcome.imageDataUrl)
    }

    @Test
    fun `an unreadable screenshot file also falls back to text`() {
        val engine = ScriptedEngine(onDump = { snapshot() }, onScreenshot = { screenshot() })

        val outcome = read(
            port(engine, readScreenshot = { null }),
            includeImage = true
        ) as AgentScreenOutcome.Observed

        assertTrue(outcome.text.startsWith("WLAN (tap)"))
        assertTrue(outcome.text.contains("could not be read"))
        assertNull(outcome.imageDataUrl)
    }

    @Test
    fun `an encoder null falls back to text rather than failing`() {
        val engine = ScriptedEngine(onDump = { snapshot() }, onScreenshot = { screenshot() })

        val outcome = read(
            port(engine, encodeImage = { null }),
            includeImage = true
        ) as AgentScreenOutcome.Observed

        assertTrue(outcome.text.startsWith("WLAN (tap)"))
        assertTrue(outcome.text.contains("could not be encoded"))
        assertNull(outcome.imageDataUrl)
    }

    @Test
    fun `an oversized image is dropped so the text still fits the result budget`() {
        val engine = ScriptedEngine(onDump = { snapshot() }, onScreenshot = { screenshot() })
        val huge = "data:image/jpeg;base64," + "A".repeat(MAX_IMAGE_DATA_URL_CHARS)

        val outcome = read(port(engine, encodeImage = { huge }), includeImage = true)
            as AgentScreenOutcome.Observed

        assertNull("an image that cannot fit must not be sent", outcome.imageDataUrl)
        assertTrue(outcome.text.contains("too large"))
    }

    @Test
    fun `only when the tree and the image both fail does the call fail`() {
        val engine = ScriptedEngine(
            onDump = {
                A11yOutcome.Rejected(
                    com.muyuchat.core.dsh.a11y.policy.A11yRejection.internal("tree unavailable")
                )
            },
            onScreenshot = {
                A11yOutcome.Rejected(
                    com.muyuchat.core.dsh.a11y.policy.A11yRejection.internal("capture refused")
                )
            }
        )

        val outcome = read(port(engine), includeImage = true)

        assertTrue("was $outcome", outcome is AgentScreenOutcome.Failed)
        val reason = (outcome as AgentScreenOutcome.Failed).reason
        assertTrue(reason.contains("tree unavailable"))
        assertTrue(reason.contains("capture refused"))
    }

    @Test
    fun `an unreadable tree with a working image still observes something`() {
        val engine = ScriptedEngine(
            onDump = {
                A11yOutcome.Rejected(
                    com.muyuchat.core.dsh.a11y.policy.A11yRejection.internal("tree unavailable")
                )
            },
            onScreenshot = { screenshot() }
        )

        val outcome = read(port(engine), includeImage = true) as AgentScreenOutcome.Observed

        assertEquals(dataUrl, outcome.imageDataUrl)
        assertTrue("the model must know the text half is missing", outcome.text.contains("image only"))
    }

    @Test
    fun `a disabled service is never reported as a successful observation`() {
        val engine = ScriptedEngine(
            onDump = { A11yOutcome.Rejected(com.muyuchat.core.dsh.a11y.policy.A11yRejection.notEnabled()) },
            onScreenshot = { error("must not capture when the service is off") }
        )

        val outcome = read(port(engine), includeImage = true)

        assertEquals(AgentScreenOutcome.ServiceDisabled, outcome)
    }

    @Test
    fun `the tool result carries the image url and drops it under the model budget`() {
        val descriptor = AgentScreenTool.DESCRIPTOR
        val port = RecordingImagePort(AgentScreenOutcome.Observed("WLAN", dataUrl))

        val result = runBlocking {
            AgentScreenTool(port).execute(
                toolCommand(descriptor, """{"action":"screenshot"}"""),
                toolContext()
            )
        }

        val data = resultData(result)
        assertEquals("WLAN", data.getString("text"))
        assertEquals(dataUrl, data.getString("imageDataUrl"))
        assertFalse(result.truncated)
    }

    @Test
    fun `the tool result keeps the text when the image must be dropped for size`() {
        val descriptor = AgentScreenTool.DESCRIPTOR
        val huge = "data:image/jpeg;base64," + "A".repeat(20_000)
        val port = RecordingImagePort(AgentScreenOutcome.Observed("WLAN", huge))

        val result = runBlocking {
            AgentScreenTool(port).execute(
                toolCommand(descriptor, """{"action":"screenshot"}"""),
                toolContext()
            )
        }

        val data = resultData(result)
        assertEquals("WLAN", data.getString("text"))
        assertFalse("the image must be removed, not truncate the whole result", data.has("imageDataUrl"))
        assertTrue("the drop must be observable", data.getBoolean("imageDropped"))
        assertNotNull(result.dataJson)
    }

    private class RecordingImagePort(private val outcome: AgentScreenOutcome) : AgentScreenPort {
        override suspend fun act(action: AgentScreenAction): AgentScreenOutcome = outcome
    }
}

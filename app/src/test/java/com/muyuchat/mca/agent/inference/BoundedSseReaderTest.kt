package com.muyuchat.mca.agent.inference

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedSseReaderTest {

    @Test
    fun parsesEventNameAndJoinedDataLines() {
        val reader = BoundedSseReader(
            StringReader("event: response.output_text.delta\ndata: {\"a\":1}\ndata: {}\n\n"),
            maxEventChars = 64 * 1024,
            maxAttemptChars = 1024 * 1024
        )
        val event = reader.nextEvent()
        assertNotNull(event)
        assertEquals("response.output_text.delta", event?.eventName)
        assertEquals("{\"a\":1}\n{}", event?.data)
        assertNull(reader.nextEvent())
    }

    @Test
    fun rejectsSingleEventOverSixtyFourKiB() {
        val payload = "data: " + "x".repeat(70_000) + "\n\n"
        val reader = BoundedSseReader(StringReader(payload), 64 * 1024, 1024 * 1024)
        assertThrows(AgentStreamLimitExceeded::class.java) { reader.nextEvent() }
    }

    @Test
    fun rejectsAttemptOverOneMiB() {
        val event = "data: " + "y".repeat(60_000) + "\n\n"
        val reader = BoundedSseReader(StringReader(event.repeat(20)), 64 * 1024, 1024 * 1024)
        assertThrows(AgentStreamLimitExceeded::class.java) {
            while (reader.nextEvent() != null) {
                // consume until the attempt bound trips
            }
        }
    }
}

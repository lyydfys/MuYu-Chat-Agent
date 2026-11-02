package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPromptedProtocolFallbackTest {

    @Test
    fun plainProseStillCompletesAsALegacyFallback() {
        val run = AgentTestFixtures.run()
        val result = protocolFallbackCompletion(
            run,
            AgentLoopTranscript(emptyList(), 0, 0),
            "  这是普通回答。 "
        )
        assertNotNull(result)
    }

    @Test
    fun malformedProtocolJsonIsNeverAcceptedAsAFinalAnswer() {
        val run = AgentTestFixtures.run()
        val transcript = AgentLoopTranscript(emptyList(), 0, 0)
        assertNull(
            protocolFallbackCompletion(
                run,
                transcript,
                """{"protocol":"mca.agent.v1","kind":"tool","call":{"name":"device.notify","arguments":{}}}"""
            )
        )
        assertNull(
            protocolFallbackCompletion(
                run,
                transcript,
                """{"protocol":"mca.agent.v1","kind":"tool","call":{"name":"x","arguments":{}}}""" +
                    """{"protocol":"mca.agent.v1","kind":"final","text":"done","artifactIds":[]}"""
            )
        )
        assertNull(protocolFallbackCompletion(run, transcript, "  "))
    }
}

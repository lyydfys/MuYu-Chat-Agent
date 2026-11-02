package com.muyuchat.mca.agent.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCloudPromptedToolTrimTest {

    private val tool = """{"protocol":"mca.agent.v1","kind":"tool","call":{"name":"device.notify","arguments":{"title":"t","body":"b"}}}"""
    private val final = """{"protocol":"mca.agent.v1","kind":"final","text":"done","artifactIds":[]}"""

    @Test
    fun firstBalancedObjectIsExtractedFromConcatenatedRoots() {
        assertEquals(tool, firstCompleteJsonObject(tool + final))
        assertEquals("""{"a":{"b":"}"}}""", firstCompleteJsonObject("""{"a":{"b":"}"}}rest"""))
        assertNull(firstCompleteJsonObject("""{"a":1"""))
        assertNull(firstCompleteJsonObject("""prose {"a":1}"""))
    }

    @Test
    fun onlyATrailingToolRootIsTrimmed() {
        assertEquals(tool, promptedToolText(tool + "\n" + final))
        assertEquals(final + tool, promptedToolText(final + tool))
        assertEquals("普通回答", promptedToolText("普通回答"))
        assertEquals(tool, promptedToolText(tool))
    }
}

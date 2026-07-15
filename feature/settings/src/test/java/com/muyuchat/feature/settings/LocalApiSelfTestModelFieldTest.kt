package com.muyuchat.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiSelfTestModelFieldTest {
    @Test
    fun usesCurrentlyLoadedModelIdInsteadOfLegacyAlias() {
        val field = localApiSelfTestModelField("qwen35 4b mnn bundle")

        assertEquals("\"model\":\"qwen35 4b mnn bundle\",", field)
        assertFalse(field.contains("mca-local"))
    }

    @Test
    fun omitsModelWhenNothingIsLoaded() {
        assertEquals("", localApiSelfTestModelField(null))
        assertEquals("", localApiSelfTestModelField("   "))
    }

    @Test
    fun escapesModelIdAsJson() {
        val field = localApiSelfTestModelField("model\\\"line\nnext")

        assertTrue(field.contains("model\\\\\\\"line\\nnext"))
    }
}

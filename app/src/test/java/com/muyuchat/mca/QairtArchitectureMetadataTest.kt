package com.muyuchat.mca

import com.muyuchat.core.download.ModelScopeClient
import org.junit.Assert.assertEquals
import org.junit.Test

class QairtArchitectureMetadataTest {
    private val recommendations = ModelScopeClient().recommendedModels()

    @Test
    fun recordsTheActualQairtModelFamily() {
        assertEquals(
            "qwen3-vl",
            recommendedQairtArchitecture(recommendations.single { it.id == "qwen3_vl_4b_qairt_w4a16" })
        )
        assertEquals(
            "qwen2.5-vl",
            recommendedQairtArchitecture(recommendations.single { it.id == "qwen25_vl_7b_qairt_w4a16" })
        )
        assertEquals(
            "qwen3",
            recommendedQairtArchitecture(recommendations.single { it.id == "qwen3_4b_2507_qairt_w4a16" })
        )
    }
}

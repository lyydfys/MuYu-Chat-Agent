package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTokenEstimatorTest {
    @Test
    fun latinDocumentsUseConservativeThreeCharactersPerTokenFallback() {
        assertEquals(10, estimateLocalPromptTokens("a".repeat(30)))
    }

    @Test
    fun denseScriptsAndEmojiAreNotUndercountedLikeLatinText() {
        assertEquals(4, estimateLocalPromptTokens("中文测试"))
        assertTrue(estimateLocalPromptTokens("🙂") >= 2)
    }
}

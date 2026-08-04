package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImagePromptLanguageV4HardContractTest {
    @Test
    fun `hard contracts preserve arbitrary Chinese clause layout after verified translation`() {
        val source =
            "一个女孩站在雪山前，夕阳穿过云层\r\n电影感，35mm胶片质感 <lora:cinematic:0.8>"
        val layout = localImagePromptClauseLayoutV4(source)

        assertEquals(4, layout.clauses.size)
        val translated = layout.render(
            listOf(
                "one girl standing in front of a snow mountain",
                "sunset light passing through clouds",
                "cinematic look",
                "35mm film texture <lora:cinematic:0.8>"
            )
        )

        assertEquals(
            "one girl standing in front of a snow mountain," +
                "sunset light passing through clouds\r\ncinematic look," +
                "35mm film texture <lora:cinematic:0.8>",
            translated
        )
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = source,
            effectivePrompt = translated,
            originalNegativePrompt = "不要畸形手指，不要乱码",
            effectiveNegativePrompt = "deformed fingers,garbled text"
        )
    }

    @Test
    fun `known quantity color and spatial contradictions remain fail closed`() {
        val result = runCatching {
            validateLocalImagePromptTranslationHardContractsV4(
                originalPrompt = "一只红色杯子左侧有两个绿色苹果",
                effectivePrompt = "one blue cup with three green apples to the right",
                originalNegativePrompt = null,
                effectiveNegativePrompt = null
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `layout preserves escaped separators and CRLF boundaries`() {
        val source = "红色杯子\\,raw_tag，蓝色桌子\r\n柔和晨光"
        val layout = localImagePromptClauseLayoutV4(source)

        assertEquals(
            "red cup\\,raw_tag,blue table\r\nsoft morning light",
            layout.render(listOf("red cup\\,raw_tag", "blue table", "soft morning light"))
        )
    }

    @Test
    fun `weighted scopes reject moved negation swapped nesting and control punctuation`() {
        listOf(
            "(不要猫:1.2)" to "no (cat:1.2)",
            "((猫)狗:1.2)" to "((dog) cat:1.2)",
            "(红猫|蓝狗:1.2)" to "(red cat blue dog|:1.2)"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    validateLocalImagePromptTranslationHardContractsV4(
                        originalPrompt = source,
                        effectivePrompt = translated,
                        originalNegativePrompt = null,
                        effectiveNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `protected attributes and quantities require distinct bound occurrences`() {
        listOf(
            "(红猫和红猫:1.2)" to "(red red cat and cat:1.2)",
            "(两个苹果和两个苹果:1.2)" to "(two two apples and apples:1.2)"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    validateLocalImagePromptTranslationHardContractsV4(
                        originalPrompt = source,
                        effectivePrompt = translated,
                        originalNegativePrompt = null,
                        effectiveNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `relations are scoped and every target operator is consumed`() {
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "(红猫在蓝狗左边:1.2)",
            effectivePrompt = "(red cat to the left of blue dog:1.2)",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
        assertTrue(
            runCatching {
                validateLocalImagePromptTranslationHardContractsV4(
                    originalPrompt = "猫和狗和猫",
                    effectivePrompt = "cat and and dog and cat",
                    originalNegativePrompt = null,
                    effectiveNegativePrompt = null
                )
            }.isFailure
        )
    }

    @Test
    fun `verified spatial relations may use one auxiliary copula`() {
        listOf(
            "红猫在蓝狗左边" to "red cat is to the left of blue dog",
            "杯子放在桌子上" to "cup is on table",
            "杯子放在桌子上" to "cup is placed on table",
            "两个苹果放在桌子上" to "two apples are on table",
            "猫位于狗上方" to "cat is on top of dog",
            "猫在狗旁边" to "cat is next to dog",
            "杯子左侧有两个苹果" to "to the left of cup are two apples",
            "杯子放在桌子上" to "on table is cup",
            "猫在杯子里" to "inside cup is cat"
        ).forEach { (source, translated) ->
            validateLocalImagePromptTranslationHardContractsV4(
                originalPrompt = source,
                effectivePrompt = translated,
                originalNegativePrompt = null,
                effectiveNegativePrompt = null
            )
        }
        listOf(
            "cup is are on table",
            "on table is are cup"
        ).forEach { translated ->
            assertTrue(
                translated,
                runCatching {
                    validateLocalImagePromptTranslationHardContractsV4(
                        originalPrompt = "杯子放在桌子上",
                        effectivePrompt = translated,
                        originalNegativePrompt = null,
                        effectiveNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `mixed ASCII anchor literals remain distinct from translated semantics`() {
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "红猫 red",
            effectivePrompt = "red cat red",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "猫 cat",
            effectivePrompt = "cat cat",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "(猫 cat:1.2)",
            effectivePrompt = "(cat cat:1.2)",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "猫和cat",
            effectivePrompt = "cat and cat",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "猫 on 狗",
            effectivePrompt = "cat on dog",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "(猫 cat 狗:1.2)",
            effectivePrompt = "(cat cat dog:1.2)",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
        validateLocalImagePromptTranslationHardContractsV4(
            originalPrompt = "(猫 inside 狗:1.2)",
            effectivePrompt = "(cat inside dog:1.2)",
            originalNegativePrompt = null,
            effectiveNegativePrompt = null
        )
    }

    @Test
    fun `fully known clauses reject new spatial and unknown English semantics`() {
        listOf(
            "猫 狗" to "cat on dog",
            "猫 狗" to "cat in dog",
            "猫 狗" to "cat inside dog",
            "猫 狗" to "cat outside dog",
            "猫 狗 on" to "cat on dog",
            "猫 狗 in" to "cat in dog",
            "猫 狗 inside" to "cat inside dog",
            "猫 狗 outside" to "cat outside dog",
            "(猫 狗:1.2) on" to "(cat on dog:1.2) on",
            "红猫" to "red cat nuclear explosion",
            "红猫" to "red cat smiling"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    validateLocalImagePromptTranslationHardContractsV4(
                        originalPrompt = source,
                        effectivePrompt = translated,
                        originalNegativePrompt = null,
                        effectiveNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `weighted singular articles normalize inside attention scope`() {
        assertEquals(
            "(one red cat:1.2)",
            normalizeLocalImagePromptTranslationClauseV4(
                source = "(一只红猫:1.2)",
                translated = "(a red cat:1.2)",
                negativeConditioning = false
            )
        )
    }
}

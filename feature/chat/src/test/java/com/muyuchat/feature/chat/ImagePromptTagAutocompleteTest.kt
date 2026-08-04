package com.muyuchat.feature.chat

import java.util.LinkedHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptTagAutocompleteTest {
    @Test
    fun `strict csv parses quoted aliases and independent utf8 translations`() {
        val record = requireNotNull(
            ImagePromptTagCsv.parseTagLine(
                "\uFEFFred_hair,4,123456,\"scarlet_hair, ginger hair,scarlet hair\""
            )
        )
        assertEquals("red_hair", record.tag)
        assertEquals(4, record.category)
        assertEquals(123_456L, record.postCount)
        assertEquals(listOf("scarlet_hair", "ginger hair"), record.aliases)

        val translation = requireNotNull(
            ImagePromptTagCsv.parseTranslationUtf8Line("red_hair,12345,红发".toByteArray(Charsets.UTF_8))
        )
        assertEquals(ImagePromptTagTranslationRow("red_hair", "红发"), translation)
    }

    @Test
    fun `csv rejects malformed utf8 bad rows and every configured hard boundary`() {
        assertNull(ImagePromptTagCsv.parseTagUtf8Line(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(ImagePromptTagCsv.parseTagLine("red_hair,4,10,\"unclosed"))
        assertNull(ImagePromptTagCsv.parseTagLine("red\"hair,4,10"))
        assertNull(ImagePromptTagCsv.parseTagLine("red_hair,4,-1"))
        assertNull(ImagePromptTagCsv.parseTagLine("red_hair,256,1"))
        assertNull(ImagePromptTagCsv.parseTagLine("red_hair,4,1,aliases,extra"))
        assertNull(ImagePromptTagCsv.parseTagLine("x".repeat(ImagePromptTagCsv.MAX_UTF8_LINE_BYTES + 1)))
        val tooManyAliases = (0..64).joinToString(",") { "alias_$it" }
        assertNull(ImagePromptTagCsv.parseTagLine("red_hair,4,1,\"$tooManyAliases\""))
        assertNull(ImagePromptTagCsv.parseTranslationLine("red_hair"))
        assertNull(ImagePromptTagCsv.parseTranslationLine("red_hair,123,45.6"))
        val excessColumns = (0..ImagePromptTagCsv.MAX_TRANSLATION_COLUMNS).joinToString(",")
        assertNull(ImagePromptTagCsv.parseTranslationLine("red_hair,$excessColumns"))
    }

    @Test
    fun `normalization makes spaces underscores and case equivalent`() {
        assertEquals("red_hair", normalizeImagePromptTag("  RED___  Hair  "))
        assertEquals("red_hair", normalizeImagePromptTag("Red-Hair"))
        assertEquals("红_发", normalizeImagePromptTag(" 红__发 "))
        assertEquals("", normalizeImagePromptTag(" _  __ "))
    }

    @Test
    fun `prefix alias and translation suggestions use stable popularity ordering`() {
        val records = listOf(
            record("red_cat", posts = 100),
            record("red_hair", posts = 1_000, aliases = listOf("scarlet_hair")),
            record("red_apple", posts = 100)
        )
        val translations = linkedMapOf("red_hair" to "红 发")
        val index = ImagePromptTagAutocomplete.create(records, translations)

        assertEquals(
            listOf("red_hair", "red_apple", "red_cat"),
            index.suggest("RED ", limit = 3).map(ImagePromptTagSuggestion::replacementTag)
        )
        val alias = index.suggest("scarlet").single()
        assertEquals(ImagePromptTagMatchKind.ALIAS_PREFIX, alias.matchKind)
        assertEquals("scarlet_hair", alias.matchedText)
        val translation = index.suggest("红").single()
        assertEquals(ImagePromptTagMatchKind.TRANSLATION_PREFIX, translation.matchKind)
        assertEquals("红 发", translation.matchedText)
        assertEquals("红 发", translation.translation)
        assertEquals("red_hair", index.suggest("red hair").first().replacementTag)
        assertEquals("red_hair", index.suggest("red-hair").first().replacementTag)
    }

    @Test
    fun `ranking and normalized translation collisions remain deterministic`() {
        val forward = listOf(record("blue_sky", 50), record("blue_archive", 50))
        val reverse = forward.reversed()
        val firstTranslations = LinkedHashMap<String, String>().apply {
            put("blue_sky", "天空")
            put("blue sky", "蓝天")
        }
        val secondTranslations = LinkedHashMap<String, String>().apply {
            put("blue sky", "蓝天")
            put("blue_sky", "天空")
        }

        assertEquals(
            listOf("blue_archive", "blue_sky"),
            ImagePromptTagAutocomplete.create(forward, firstTranslations)
                .suggest("blue", 2)
                .map(ImagePromptTagSuggestion::replacementTag)
        )
        assertEquals(
            listOf("blue_archive", "blue_sky"),
            ImagePromptTagAutocomplete.create(reverse, secondTranslations)
                .suggest("blue", 2)
                .map(ImagePromptTagSuggestion::replacementTag)
        )
        assertEquals(
            "蓝天",
            ImagePromptTagAutocomplete.create(forward, secondTranslations)
                .suggest("蓝")
                .single()
                .translation
        )
    }

    @Test
    fun `fuzzy fallback covers one edit transpose alias translation and bounded abbreviation`() {
        val index = ImagePromptTagAutocomplete.create(
            records = listOf(
                record("blue_hair"),
                record("red_hair", aliases = listOf("scarlet_hair")),
                record("silver_hair")
            ),
            translations = mapOf("silver_hair" to "yin fa")
        )

        listOf("bue_hair", "bluue_hair", "blue_hsir", "bule_hair").forEach { query ->
            val match = index.suggest(query).single { it.replacementTag == "blue_hair" }
            assertEquals(ImagePromptTagMatchKind.FUZZY_TAG, match.matchKind)
        }
        assertEquals(
            ImagePromptTagMatchKind.FUZZY_ALIAS,
            index.suggest("scaret_hair").single { it.replacementTag == "red_hair" }.matchKind
        )
        assertEquals(
            ImagePromptTagMatchKind.FUZZY_TRANSLATION,
            index.suggest("yifna").single { it.replacementTag == "silver_hair" }.matchKind
        )
        assertEquals(
            ImagePromptTagMatchKind.FUZZY_TAG,
            index.suggest("rdhair").single { it.replacementTag == "red_hair" }.matchKind
        )
    }

    @Test
    fun `prefix stays ahead of fuzzy and edit quality stays ahead of popularity`() {
        val index = ImagePromptTagAutocomplete.create(
            listOf(
                record("blue_low", posts = 10),
                record("bluish", posts = 100),
                record("blx", posts = 1_000_000),
                record("rd_hair", posts = 1),
                record("red_hair", posts = 2_000_000)
            )
        )

        val prefixFirst = index.suggest("blu", limit = 3)
        assertEquals(listOf("bluish", "blue_low", "blx"), prefixFirst.map { it.replacementTag })
        assertEquals(
            listOf(
                ImagePromptTagMatchKind.TAG_PREFIX,
                ImagePromptTagMatchKind.TAG_PREFIX,
                ImagePromptTagMatchKind.FUZZY_TAG
            ),
            prefixFirst.map { it.matchKind }
        )
        assertEquals(
            listOf("rd_hair", "red_hair"),
            index.suggest("rdhair", limit = 2).map { it.replacementTag }
        )
    }

    @Test
    fun `equal fuzzy quality uses tag alias translation source order`() {
        val index = ImagePromptTagAutocomplete.create(
            records = listOf(
                record("bule_hair", posts = 100),
                record("alias_owner", posts = 100, aliases = listOf("bule_hair")),
                record("translation_owner", posts = 100)
            ),
            translations = mapOf("translation_owner" to "bule hair")
        )

        val results = index.suggest("blue_hair", limit = 3)
        assertEquals(
            listOf("bule_hair", "alias_owner", "translation_owner"),
            results.map { it.replacementTag }
        )
        assertEquals(
            listOf(
                ImagePromptTagMatchKind.FUZZY_TAG,
                ImagePromptTagMatchKind.FUZZY_ALIAS,
                ImagePromptTagMatchKind.FUZZY_TRANSLATION
            ),
            results.map { it.matchKind }
        )
    }

    @Test
    fun `fuzzy fallback is deduplicated deterministic and fails closed at hard bounds`() {
        val longCandidate = "a".repeat(ImagePromptTagAutocomplete.MAX_FUZZY_QUERY_CHARS) + "b"
        val index = ImagePromptTagAutocomplete.create(
            listOf(
                record("blue_hair", aliases = listOf("bule_hair")),
                record("red_hair"),
                record("a123456789bc"),
                record(longCandidate)
            )
        )

        val deduplicated = index.suggest("bule_hair")
        assertEquals(1, deduplicated.count { it.replacementTag == "blue_hair" })
        assertEquals(ImagePromptTagMatchKind.ALIAS_PREFIX, deduplicated.first().matchKind)
        assertEquals(emptyList<ImagePromptTagSuggestion>(), index.suggest("zzzzzz"))
        assertEquals(emptyList<ImagePromptTagSuggestion>(), index.suggest("qed_xair"))
        assertEquals(emptyList<ImagePromptTagSuggestion>(), index.suggest("rd"))
        assertEquals(emptyList<ImagePromptTagSuggestion>(), index.suggest("abc"))
        assertEquals(
            emptyList<ImagePromptTagSuggestion>(),
            index.suggest("a".repeat(ImagePromptTagAutocomplete.MAX_FUZZY_QUERY_CHARS) + "c")
        )
        assertEquals(1, ImagePromptTagAutocomplete.MAX_FUZZY_EDITS)
        assertTrue(
            ImagePromptTagAutocomplete.MAX_FUZZY_SCANNED_TERMS >= 100_000L
        )
        assertTrue(
            isImagePromptTagFuzzyScanAllowed(ImagePromptTagAutocomplete.MAX_FUZZY_SCANNED_TERMS)
        )
        assertFalse(
            isImagePromptTagFuzzyScanAllowed(
                ImagePromptTagAutocomplete.MAX_FUZZY_SCANNED_TERMS + 1L
            )
        )
    }

    @Test
    fun `active comma token replacement escapes literal parentheses and appends separator`() {
        val prompt = "masterpiece,   aqua_ko old text, detailed"
        val cursor = prompt.indexOf(" old")
        val active = requireNotNull(findImagePromptActiveToken(prompt, cursor))
        assertEquals("aqua_ko", active.query)

        val edited = requireNotNull(
            applyImagePromptTagSuggestion(
                text = prompt,
                cursor = cursor,
                suggestion = suggestion("aqua_(konosuba)")
            )
        )
        assertEquals("masterpiece,   aqua \\(konosuba\\), detailed", edited.text)
        assertEquals(edited.text.indexOf("detailed"), edited.cursor)
        assertEquals("name \\(series\\)", renderImagePromptTagLiteral("name_(series)"))
        assertEquals("name \\(series\\)", renderImagePromptTagLiteral("name_\\(series\\)"))
        assertEquals("literal_under", renderImagePromptTagLiteral("literal\\_under"))
        assertNull(findImagePromptActiveToken(prompt, 0))
    }

    @Test
    fun `weight adjustment and clear operate only on the active comma segment`() {
        val explicit = requireNotNull(
            adjustImagePromptActiveTagWeight("(red hair:1.2), blue sky", cursor = 5, delta = -0.2)
        )
        assertEquals("red hair, blue sky", explicit.text)
        assertEquals("red hair".length, explicit.cursor)

        val shorthand = requireNotNull(
            adjustImagePromptActiveTagWeight("[red hair], blue sky", cursor = 4, delta = 0.2)
        )
        assertEquals("(red hair:1.1), blue sky", shorthand.text)
        val nestedExplicit = requireNotNull(
            adjustImagePromptActiveTagWeight("((red hair:1.2)), blue sky", cursor = 6, delta = 0.1)
        )
        assertEquals("(red hair:1.4), blue sky", nestedExplicit.text)
        assertNull(adjustImagePromptActiveTagWeight("red hair", 3, Double.NaN))
        assertEquals(
            ImagePromptTextEdit("red hair, green eyes", "red hair".length),
            clearImagePromptActiveTag("red hair, blue sky, green eyes", cursor = 15)
        )
        assertEquals(ImagePromptTextEdit("blue sky", 0), clearImagePromptActiveTag("red hair, blue sky", 2))
        assertNull(clearImagePromptActiveTag("red hair, ", "red hair, ".length))
    }

    @Test
    fun `dictionary and query limits fail closed without mutable alias leakage`() {
        val mutableAliases = mutableListOf("scarlet")
        val source = record("red_hair", 1, mutableAliases)
        mutableAliases.clear()
        assertEquals(listOf("scarlet"), source.aliases)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (source.aliases as MutableList<String>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePromptTagAutocomplete.create(listOf(record("red hair"), record("red_hair")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePromptTagAutocomplete.create(listOf(record("red_hair"))).suggest("red", 51)
        }
        val repeated = List(ImagePromptTagAutocomplete.MAX_DICTIONARY_ENTRIES + 1) { source }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePromptTagAutocomplete.create(repeated)
        }
    }

    @Test
    fun `one hundred thousand tags use bounded deterministic top k results`() {
        val records = List(100_000) { index ->
            val suffix = index.toString().padStart(6, '0')
            record(tag = "tag_$suffix", posts = index.toLong())
        }
        val autocomplete = ImagePromptTagAutocomplete.create(records)

        val results = autocomplete.suggest("TAG 0999", limit = 5)
        assertEquals(5, results.size)
        assertEquals(
            listOf("tag_099999", "tag_099998", "tag_099997", "tag_099996", "tag_099995"),
            results.map(ImagePromptTagSuggestion::replacementTag)
        )
        val fuzzyResults = autocomplete.suggest("tag 09999x", limit = 5)
        assertEquals(
            listOf("tag_099999", "tag_099998", "tag_099997", "tag_099996", "tag_099995"),
            fuzzyResults.map(ImagePromptTagSuggestion::replacementTag)
        )
        assertTrue(fuzzyResults.all { it.matchKind == ImagePromptTagMatchKind.FUZZY_TAG })
        assertEquals(fuzzyResults, autocomplete.suggest("tag 09999x", limit = 5))
        assertEquals(emptyList<ImagePromptTagSuggestion>(), autocomplete.suggest("missing prefix"))
    }

    private fun record(
        tag: String,
        posts: Long = 0,
        aliases: List<String> = emptyList()
    ): ImagePromptTagRecord = ImagePromptTagRecord(
        tag = tag,
        category = 0,
        postCount = posts,
        aliases = aliases
    )

    private fun suggestion(tag: String): ImagePromptTagSuggestion = ImagePromptTagSuggestion(
        replacementTag = tag,
        category = 0,
        postCount = 0,
        matchKind = ImagePromptTagMatchKind.TAG_PREFIX,
        matchedText = tag,
        translation = null
    )
}

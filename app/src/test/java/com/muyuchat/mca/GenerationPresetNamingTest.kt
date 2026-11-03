package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming rules for imported generation presets.
 *
 * Regression: the preset picker handed back SAF `content://` URIs, and feeding
 * `lastPathSegment` straight in as the fallback name made the preset page show provider ids such as
 * `document:1000156859` instead of a file name.
 */
class GenerationPresetNamingTest {

    @Test
    fun `document id segment is never treated as a name`() {
        assertEquals("模型预设", GenerationPresetNaming.fromFileName("document:1000156859"))
        assertEquals("模型预设", GenerationPresetNaming.fromFileName("msf:1000000078"))
        assertEquals("模型预设", GenerationPresetNaming.fallbackName(null, "document:1000156859"))
    }

    @Test
    fun `relative saf path keeps only the file stem`() {
        assertEquals("我的预设", GenerationPresetNaming.fromFileName("primary:Download/我的预设.json"))
        assertEquals(
            "我的预设",
            GenerationPresetNaming.fromFileName("raw:/storage/emulated/0/Download/我的预设.json")
        )
        assertEquals(
            "我的预设",
            GenerationPresetNaming.fromFileName(
                "content://com.android.providers.downloads.documents/document/我的预设.json"
            )
        )
    }

    @Test
    fun `json extension is stripped case insensitively`() {
        assertEquals("超级预设", GenerationPresetNaming.fromFileName("超级预设.JSON"))
        assertEquals("超级预设", GenerationPresetNaming.fromFileName("超级预设.json"))
        assertEquals("超级预设", GenerationPresetNaming.fromFileName("  超级预设.Json  "))
    }

    @Test
    fun `blank numeric uri and symbol input fall back`() {
        assertEquals("模型预设", GenerationPresetNaming.fromFileName(null))
        assertEquals("模型预设", GenerationPresetNaming.fromFileName(""))
        assertEquals("模型预设", GenerationPresetNaming.fromFileName("   "))
        assertEquals("模型预设", GenerationPresetNaming.fromFileName("1000156859"))
        assertEquals("模型预设", GenerationPresetNaming.fromFileName("1000156859.json"))
        assertEquals("模型预设", GenerationPresetNaming.fromFileName("2026-11-03"))
        assertEquals(
            "模型预设",
            GenerationPresetNaming.fromFileName("content://media/external/file/1000156859")
        )
    }

    @Test
    fun `overlong names are capped without splitting a surrogate pair`() {
        val capped = GenerationPresetNaming.fromFileName("预".repeat(200) + ".json")
        assertEquals(GenerationPresetNaming.MAX_NAME_CHARS, capped.length)
        assertEquals("预".repeat(GenerationPresetNaming.MAX_NAME_CHARS), capped)

        // 63 chars + a 2-unit emoji would be cut mid-pair by a plain take(64).
        val emojiCut = GenerationPresetNaming.fromFileName("预".repeat(63) + "😀" + "尾".repeat(20))
        assertEquals(63, emojiCut.length)
        assertTrue(emojiCut.none { it.isHighSurrogate() || it.isLowSurrogate() })
    }

    @Test
    fun `display name wins and the saf segment is only the fallback`() {
        assertEquals(
            "我的预设",
            GenerationPresetNaming.fallbackName("我的预设.json", "document:1000156859")
        )
        // No display name: the normalized segment is still usable.
        assertEquals(
            "我的预设",
            GenerationPresetNaming.fallbackName(null, "primary:Download/我的预设.json")
        )
        // Both unusable: generic fallback instead of an id.
        assertEquals(
            "模型预设",
            GenerationPresetNaming.fallbackName("1000156859.json", "document:1000156859")
        )
        // A prefixed display name is cleaned as well.
        assertEquals(
            "我的预设",
            GenerationPresetNaming.fallbackName("primary:Download/我的预设.json", null)
        )
    }

    @Test
    fun `no branch leaks a saf prefix into the resolved name`() {
        val candidates = listOf(
            "document:1000156859",
            "primary:Download/我的预设.json",
            "raw:/storage/emulated/0/Download/我的预设.json",
            "msf:1000000078",
            "content://com.android.providers.downloads.documents/document/我的预设.json"
        )
        for (candidate in candidates) {
            val resolved = GenerationPresetNaming.fallbackName(candidate, candidate)
            assertTrue("leaked prefix for <$candidate>: $resolved", resolved.isNotBlank())
            assertTrue("leaked prefix for <$candidate>: $resolved", !resolved.contains("document:"))
            assertTrue("leaked prefix for <$candidate>: $resolved", !resolved.contains("primary:"))
            assertTrue("leaked prefix for <$candidate>: $resolved", !resolved.contains("raw:"))
            assertTrue("leaked prefix for <$candidate>: $resolved", !resolved.contains("msf:"))
            assertTrue("leaked prefix for <$candidate>: $resolved", !resolved.contains("content:"))
            assertTrue("leaked prefix for <$candidate>: $resolved", !resolved.contains("://"))
            assertTrue(
                "leaked extension for <$candidate>: $resolved",
                !resolved.endsWith(".json", ignoreCase = true)
            )
        }
    }

    @Test
    fun `record name keeps user authored text but strips plumbing`() {
        // A preset file may legitimately be named by its author with digits only.
        assertEquals("2024", GenerationPresetNaming.recordName("2024"))
        assertEquals("Night Drive", GenerationPresetNaming.recordName("Night Drive"))
        assertEquals("我的预设", GenerationPresetNaming.recordName("我的预设.json"))
        assertEquals("我的预设", GenerationPresetNaming.recordName("primary:Download/我的预设.json"))
        assertEquals("两行 名称", GenerationPresetNaming.recordName("两行\n名称"))
        assertEquals("模型预设", GenerationPresetNaming.recordName("   "))
        assertEquals("模型预设", GenerationPresetNaming.recordName(null))
        assertEquals(
            GenerationPresetNaming.MAX_NAME_CHARS,
            GenerationPresetNaming.recordName("长".repeat(200)).length
        )
    }
}

package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFormattingTest {
    @Test
    fun `brace code emitted without indentation is structurally indented`() {
        val formatted = repairCodeText(
            "function greet() {\nconsole.log(\"hi\");\nif (ok) {\nreturn true;\n}\n}",
            "javascript"
        )
        assertEquals(
            "function greet() {\n    console.log(\"hi\");\n    if (ok) {\n        return true;\n    }\n}",
            formatted
        )
    }

    @Test
    fun `python indentation is preserved`() {
        val source = "def f():\n    if ready:\n        return 1"
        assertEquals(source, repairCodeText(source, "python"))
    }

    @Test
    fun `flat python blocks receive structural indentation`() {
        val source = "def f():\nif ready:\nreturn 1\nelse:\nreturn 0"
        assertEquals(
            "def f():\n    if ready:\n        return 1\n    else:\n        return 0",
            repairCodeText(source, "python")
        )
    }

    @Test
    fun `top level python declarations are not nested by repair`() {
        val source = "def first():\nreturn 1\ndef second():\nreturn 2"
        assertEquals(
            "def first():\n    return 1\ndef second():\n    return 2",
            repairCodeText(source, "python")
        )
    }

    @Test
    fun `python indentation is repaired when fence omits language`() {
        assertEquals(
            "def f():\n    return 1",
            repairCodeText("def f():\nreturn 1", null)
        )
    }

    @Test
    fun `compact arithmetic rows are split by chat normalization`() {
        val normalized = "1×1=1 2×2=4 3×3=9".repairCompactArithmeticRows()
        assertTrue(normalized.contains("\n2×2=4"))
    }
}

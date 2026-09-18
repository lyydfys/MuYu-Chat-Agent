package com.muyuchat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImagePromptTokenMeasurementTest {
    @Test
    fun `exact measurement may publish an overflow offset`() {
        val measurement = ImagePromptTokenMeasurement(
            count = 80,
            maxTokens = 77,
            overflowOffset = 120,
        )

        assertTrue(measurement.exact)
        assertTrue(measurement.overflows)
    }

    @Test
    fun `estimated measurement is explicit and cannot publish an exact offset`() {
        val estimate = ImagePromptTokenMeasurement(
            count = 80,
            maxTokens = 77,
            exact = false,
        )

        assertFalse(estimate.exact)
        assertTrue(estimate.overflows)
        assertThrows(IllegalArgumentException::class.java) {
            ImagePromptTokenMeasurement(
                count = 80,
                maxTokens = 77,
                overflowOffset = 12,
                exact = false,
            )
        }
    }

    @Test
    fun `ui distinguishes estimates and keeps unavailable token state actionable`() {
        val source = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt",
        )

        assertTrue(source.contains("Token 统计暂不可用 · 生成前仍会检查模型和提示词"))
        assertTrue(source.contains("生成前仍会检查模型完整性和提示词语言"))
        assertTrue(source.contains("约 \${measurement.count} / \${measurement.maxTokens} Token"))
        assertFalse(source.contains("可直接生成 · 暂不支持 Token 统计"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}

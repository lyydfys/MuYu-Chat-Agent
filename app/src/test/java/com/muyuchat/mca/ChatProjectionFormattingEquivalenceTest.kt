package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatProjectionFormattingEquivalenceTest {
    private val timestamps = listOf(
        1_704_067_199_000L,
        1_704_067_200_000L,
        1_704_937_200_000L,
        1_711_953_599_000L
    )

    @Test
    fun `history image and file timestamps match original per item formatting in chinese locale`() {
        val locale = Locale.CHINA
        val state = projectionState(timestamps)

        val history = buildChatHistoryItems(state, emptyMap(), locale)
        val projected = buildChatUiState(
            state = state,
            sessionPresentations = emptyMap(),
            timestampLocale = locale,
            imageGenerationHistoryById = emptyMap(),
            assistantImageToolPermission = ChatImageToolPermissionUiState(
                available = false,
                unavailableReason = "",
                autoApproval = false,
                canChange = false
            ),
            preparedImageUi = emptyMap(),
            generationParamsForChatModel = { GenerationParams() }
        )
        val expected = timestamps.map { originalTimestampText(it, locale) }

        assertEquals(expected, history.map { it.updatedAtText })
        assertEquals(expected, projected.images.map { it.createdAtText })
        assertEquals(expected, projected.files.map { it.createdAtText })
        assertEquals(
            history.map { it.updatedAtText },
            projected.images.map { it.createdAtText }
        )
    }

    @Test
    fun `timestamp formatting is rebuilt for each mapping call so timezone changes are observed`() {
        val locale = Locale.CHINA
        val timestamp = 1_704_067_200_000L
        val state = projectionState(listOf(timestamp))
        val originalTimeZone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val shanghaiHistory = buildChatHistoryItems(state, emptyMap(), locale).single().updatedAtText
            val shanghaiImage = projectedImages(state, locale).single().createdAtText
            assertEquals(originalTimestampText(timestamp, locale), shanghaiHistory)
            assertEquals(originalTimestampText(timestamp, locale), shanghaiImage)

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utcHistory = buildChatHistoryItems(state, emptyMap(), locale).single().updatedAtText
            val utcImage = projectedImages(state, locale).single().createdAtText
            assertEquals(originalTimestampText(timestamp, locale), utcHistory)
            assertEquals(originalTimestampText(timestamp, locale), utcImage)

            assertNotEquals(shanghaiHistory, utcHistory)
            assertNotEquals(shanghaiImage, utcImage)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    private fun projectedImages(state: MainUiState, locale: Locale) =
        buildChatUiState(
            state = state,
            sessionPresentations = emptyMap(),
            timestampLocale = locale,
            imageGenerationHistoryById = emptyMap(),
            assistantImageToolPermission = ChatImageToolPermissionUiState(
                available = false,
                unavailableReason = "",
                autoApproval = false,
                canChange = false
            ),
            preparedImageUi = emptyMap(),
            generationParamsForChatModel = { GenerationParams() }
        ).images

    private fun projectionState(times: List<Long>) = MainUiState(
        activeChatSessionId = "session-0",
        chatSessions = times.mapIndexed { index, timestamp ->
            ChatSessionRecord(
                id = "session-$index",
                title = "会话 $index",
                messages = emptyList(),
                updatedAt = timestamp
            )
        },
        images = times.mapIndexed { index, timestamp ->
            ImageAssetRecord(
                id = "image-$index",
                name = "图片 $index.png",
                uriString = "content://images/$index",
                source = "generated",
                createdAt = timestamp
            )
        },
        files = times.mapIndexed { index, timestamp ->
            FileAssetRecord(
                id = "file-$index",
                name = "文件 $index.txt",
                text = "中文文件内容",
                createdAt = timestamp
            )
        }
    )

    private fun originalTimestampText(timestamp: Long, locale: Locale): String =
        SimpleDateFormat("MM-dd HH:mm", locale).format(Date(timestamp))
}
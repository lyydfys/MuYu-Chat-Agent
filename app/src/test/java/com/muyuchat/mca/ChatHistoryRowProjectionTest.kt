package com.muyuchat.mca

import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.ChatHistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * P02b history-row cache equivalence and invalidation.
 *
 * The oracle below is the pre-cache mapping (with the same explicit timezone), so expected
 * values never come from the new projector. Identity assertions prove unchanged rows are
 * reused while every displayed dependency change rebuilds only the affected row.
 */
class ChatHistoryRowProjectionTest {
    private val timestamp = 1_704_067_230_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun unchangedRowsAreReusedAcrossUnrelatedRootStateChangesAndReleasedOnDelete() {
        val input = baseInput()
        val projector = ChatHistoryRowProjector()
        val first = project(input, projector)
        val unrelated = projectChatHistoryItems(
            state = input.toState().copy(input = "typing does not affect history"),
            sessionPresentations = input.presentations,
            timestampLocale = input.locale,
            timestampTimeZone = input.timeZone,
            rowProjector = projector
        )

        assertSame(first[0], unrelated[0])
        assertSame(first[1], unrelated[1])
        assertEquals(originalProjection(input), unrelated)

        val afterDelete = project(input.copy(sessions = listOf(input.sessions[1])), projector)
        assertEquals(listOf("b"), afterDelete.map { it.id })
        assertSame(first[1], afterDelete.single())

        val afterReinsert = project(input, projector)
        assertNotSame("a deleted row must not survive in the cache", first[0], afterReinsert[0])
        assertSame("b remained cached while a was released", first[1], afterReinsert[1])
        assertEquals(originalProjection(input), afterReinsert)
    }

    @Test
    fun everyDisplayedRowDependencyInvalidatesItsRowAndMatchesTheOriginalOutput() {
        val base = baseInput()
        val a = base.sessions[0]
        val presentationA = base.presentations.getValue("a")
        val variants = listOf(
            Variant("title", base.replaceSession(a.copy(title = "Title A changed")), setOf(0)),
            Variant(
                "display name",
                base.copy(presentations = base.presentations + ("a" to presentationA.copy(name = "Name A changed"))),
                setOf(0)
            ),
            Variant(
                "summary",
                base.copy(presentations = base.presentations + ("a" to presentationA.copy(summary = "Summary A changed"))),
                setOf(0)
            ),
            Variant(
                "assistant id",
                base.copy(
                    presentations = base.presentations + (
                        "a" to presentationA.copy(assistantId = "assistant-a-changed")
                    )
                ),
                setOf(0)
            ),
            Variant("updated timestamp", base.replaceSession(a.copy(updatedAt = a.updatedAt + 1_000L)), setOf(0)),
            Variant(
                "message count",
                base.replaceSession(a.copy(messages = a.messages + ChatMessage(Role.ASSISTANT, "extra"))),
                setOf(0)
            ),
            Variant("pinned", base.replaceSession(a.copy(pinned = true)), setOf(0)),
            Variant("manual title authority", base.replaceSession(a.copy(manualTitle = true)), setOf(0)),
            Variant("mixed assistant history", base.replaceSession(a.copy(mixedAssistantHistory = true)), setOf(0)),
            Variant("selected session", base.copy(activeSessionId = "b"), setOf(0, 1))
        )

        variants.forEach { variant ->
            val projector = ChatHistoryRowProjector()
            val before = project(base, projector)
            val after = project(variant.input, projector)

            assertEquals(variant.name, originalProjection(variant.input), after)
            variant.changedIndexes.forEach { index ->
                assertNotSame(variant.name + " must invalidate row " + index, before[index], after[index])
            }
            if (variant.changedIndexes == setOf(0)) {
                assertSame(variant.name + " must not invalidate unaffected rows", before[1], after[1])
            }
        }
    }

    @Test
    fun rawUpdatedAtLocaleAndTimeZoneInvalidateFormattingWithoutFrozenTimestamps() {
        val base = baseInput()
        val projector = ChatHistoryRowProjector()
        val utcRows = project(base, projector)

        val sameMinute = base.replaceSession(base.sessions[0].copy(updatedAt = timestamp + 1_000L))
        val sameMinuteRows = project(sameMinute, projector)
        assertEquals(utcRows[0].updatedAtText, sameMinuteRows[0].updatedAtText)
        assertNotSame("a one-minute bucket must not freeze the raw updatedAt", utcRows[0], sameMinuteRows[0])
        assertEquals(timestamp + 1_000L, sameMinuteRows[0].updatedAtMillis)
        assertEquals(originalProjection(sameMinute), sameMinuteRows)

        val arabic = base.copy(locale = Locale.forLanguageTag("ar-EG"))
        val arabicRows = project(arabic, projector)
        assertNotSame(utcRows[0], arabicRows[0])
        assertNotEquals(utcRows[0].updatedAtText, arabicRows[0].updatedAtText)
        assertEquals(originalProjection(arabic), arabicRows)

        val shanghaiInput = base.copy(timeZone = shanghai)
        val shanghaiRows = project(shanghaiInput, projector)
        assertNotSame(utcRows[0], shanghaiRows[0])
        assertNotEquals(utcRows[0].updatedAtText, shanghaiRows[0].updatedAtText)
        assertEquals(originalProjection(shanghaiInput), shanghaiRows)
    }

    @Test
    fun productionWrapperObservesDefaultTimeZoneChanges() {
        val originalTimeZone = TimeZone.getDefault()
        val utcInput = HistoryProjectionInput(
            sessions = listOf(session("default-zone", title = "Default zone")),
            presentations = emptyMap(),
            activeSessionId = "default-zone",
            locale = Locale.US,
            timeZone = utc
        )
        val shanghaiInput = utcInput.copy(timeZone = shanghai)

        try {
            TimeZone.setDefault(utc)
            val utcRow = buildChatHistoryItems(
                state = utcInput.toState(),
                sessionPresentations = utcInput.presentations,
                timestampLocale = utcInput.locale
            ).single()

            TimeZone.setDefault(shanghai)
            val shanghaiRow = buildChatHistoryItems(
                state = shanghaiInput.toState(),
                sessionPresentations = shanghaiInput.presentations,
                timestampLocale = shanghaiInput.locale
            ).single()

            assertNotSame(utcRow, shanghaiRow)
            assertNotEquals(utcRow.updatedAtText, shanghaiRow.updatedAtText)
            assertEquals(originalProjection(utcInput).single(), utcRow)
            assertEquals(originalProjection(shanghaiInput).single(), shanghaiRow)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun firstUserContentAndEditsChangeTheDerivedTitleAndInvalidateItsRow() {
        val firstMessages = listOf(ChatMessage(Role.USER, "first question"))
        val beforeSession = session("title", title = firstMessages.chatTitle(), messages = firstMessages)
        val before = singleInput(beforeSession)
        val projector = ChatHistoryRowProjector()
        val beforeRow = project(before, projector)
        assertEquals(originalProjection(before), beforeRow)

        val editedMessages = listOf(ChatMessage(Role.USER, "edited question"))
        val after = before.replaceSession(beforeSession.copy(title = editedMessages.chatTitle(), messages = editedMessages))
        val afterRow = project(after, projector)

        assertNotSame(beforeRow, afterRow)
        assertEquals(originalProjection(after), afterRow)
    }

    @Test
    fun attachmentOnlyTitleChangesInvalidateTheCachedRow() {
        val beforeMessages = listOf(
            ChatMessage(
                Role.USER,
                "【上传图片：before.png】",
                imageAttachments = listOf(ChatImageAttachment(name = "before.png", uriString = "content://before"))
            )
        )
        val beforeSession = session("attachment", title = beforeMessages.chatTitle(), messages = beforeMessages)
        val before = singleInput(beforeSession)
        val projector = ChatHistoryRowProjector()
        val beforeRow = project(before, projector)
        assertEquals(originalProjection(before), beforeRow)

        val afterMessages = listOf(
            ChatMessage(
                Role.USER,
                "【上传图片：after.png】",
                imageAttachments = listOf(ChatImageAttachment(name = "after.png", uriString = "content://after"))
            )
        )
        val after = before.replaceSession(beforeSession.copy(title = afterMessages.chatTitle(), messages = afterMessages))
        val afterRow = project(after, projector)

        assertNotSame(beforeRow, afterRow)
        assertEquals(originalProjection(after), afterRow)
    }

    @Test
    fun manualTitleStaysAuthoritativeThroughBodyEditsAndKeepsTheRowReusable() {
        val beforeMessages = listOf(ChatMessage(Role.USER, "body before edit"))
        val manualSession = session(
            "manual",
            title = "User-defined title",
            messages = beforeMessages,
            manualTitle = true
        )
        val before = singleInput(manualSession)
        val projector = ChatHistoryRowProjector()
        val beforeRow = project(before, projector)

        val edited = manualSession.copy(messages = listOf(ChatMessage(Role.USER, "body after edit")))
        val afterRow = project(before.replaceSession(edited), projector)

        assertSame("manual title and row deps are unchanged", beforeRow.single(), afterRow.single())
        assertEquals(originalProjection(before), afterRow)
    }

    @Test
    fun historyOrderAndEqualKeyTieBreaksSurviveCachedRowReuse() {
        val sessions = listOf(
            session("pinned-first", pinned = true, updatedAt = timestamp),
            session("pinned-second", pinned = true, updatedAt = timestamp),
            session("newest", updatedAt = timestamp + 2_000L),
            session("older-tied-first", updatedAt = timestamp + 1_000L),
            session("older-tied-second", updatedAt = timestamp + 1_000L)
        ).sortedForHistory()
        val input = HistoryProjectionInput(
            sessions = sessions,
            presentations = emptyMap(),
            activeSessionId = "pinned-first",
            locale = Locale.US,
            timeZone = utc
        )
        val expectedIds = listOf(
            "pinned-first",
            "pinned-second",
            "newest",
            "older-tied-first",
            "older-tied-second"
        )
        val projector = ChatHistoryRowProjector()
        val first = project(input, projector)
        val second = project(input, projector)

        assertEquals(expectedIds, first.map { it.id })
        assertEquals(expectedIds, second.map { it.id })
        first.indices.forEach { index -> assertSame(first[index], second[index]) }

        val retitled = input.replaceSession(sessions[2].copy(title = "Newest retitled"))
        val afterRetitle = project(retitled, projector)
        assertEquals(expectedIds, afterRetitle.map { it.id })
        assertNotSame(first[2], afterRetitle[2])
        first.indices.filter { it != 2 }.forEach { index ->
            assertSame("row " + index + " must stay reused", first[index], afterRetitle[index])
        }
    }

    private fun baseInput(): HistoryProjectionInput {
        val first = session("a", title = "Title A")
        val second = session("b", title = "Title B")
        return HistoryProjectionInput(
            sessions = listOf(first, second),
            presentations = mapOf(
                "a" to AssistantSessionPresentation("assistant-a", "Name A", "Summary A"),
                "b" to AssistantSessionPresentation("assistant-b", "Name B", "Summary B")
            ),
            activeSessionId = "a",
            locale = Locale.US,
            timeZone = utc
        )
    }

    private fun singleInput(session: ChatSessionRecord) = HistoryProjectionInput(
        sessions = listOf(session),
        presentations = emptyMap(),
        activeSessionId = session.id,
        locale = Locale.US,
        timeZone = utc
    )

    private fun session(
        id: String,
        title: String = id,
        messages: List<ChatMessage> = listOf(ChatMessage(Role.USER, id + " user message")),
        pinned: Boolean = false,
        manualTitle: Boolean = false,
        updatedAt: Long = timestamp,
        mixedAssistantHistory: Boolean = false
    ) = ChatSessionRecord(
        id = id,
        title = title,
        messages = messages,
        pinned = pinned,
        manualTitle = manualTitle,
        updatedAt = updatedAt,
        mixedAssistantHistory = mixedAssistantHistory
    )

    private fun project(
        input: HistoryProjectionInput,
        projector: ChatHistoryRowProjector
    ): List<ChatHistoryItem> = projectChatHistoryItems(
        state = input.toState(),
        sessionPresentations = input.presentations,
        timestampLocale = input.locale,
        timestampTimeZone = input.timeZone,
        rowProjector = projector
    )

    /** Verbatim pre-cache row mapping, kept independent from the implementation under test. */
    private fun originalProjection(input: HistoryProjectionInput): List<ChatHistoryItem> =
        input.sessions.map { session ->
            val presentation = input.presentations[session.id]
            ChatHistoryItem(
                id = session.id,
                title = session.title,
                displayName = presentation?.name ?: session.title,
                summary = presentation?.summary.orEmpty(),
                assistantId = presentation?.assistantId,
                updatedAtText = SimpleDateFormat("MM-dd HH:mm", input.locale).apply {
                    timeZone = input.timeZone.clone() as TimeZone
                }.format(Date(session.updatedAt)),
                updatedAtMillis = session.updatedAt,
                messageCount = session.messages.size,
                pinned = session.pinned,
                selected = session.id == input.activeSessionId,
                mixedAssistantHistory = session.mixedAssistantHistory
            )
        }

    private fun HistoryProjectionInput.toState() = MainUiState(
        chatSessions = sessions,
        activeChatSessionId = activeSessionId
    )

    private fun HistoryProjectionInput.replaceSession(updated: ChatSessionRecord) = copy(
        sessions = sessions.map { session -> if (session.id == updated.id) updated else session }
    )

    private data class HistoryProjectionInput(
        val sessions: List<ChatSessionRecord>,
        val presentations: Map<String, AssistantSessionPresentation>,
        val activeSessionId: String?,
        val locale: Locale,
        val timeZone: TimeZone
    )

    private data class Variant(
        val name: String,
        val input: HistoryProjectionInput,
        val changedIndexes: Set<Int>
    )
}

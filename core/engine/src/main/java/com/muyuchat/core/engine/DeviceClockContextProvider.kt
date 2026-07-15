package com.muyuchat.core.engine

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DeviceClockSnapshot(
    val instant: Instant,
    val localDate: String,
    val localTime: String,
    val timeZoneId: String,
    val utcOffset: String,
    val localeTag: String
)

/**
 * Supplies authoritative local date/time context at request time.
 *
 * The default prompt contains only date and zone, so its text remains stable
 * for prefix-cache reuse throughout a day. Exact time is appended only when
 * the newest user turn explicitly asks for the current time.
 */
class DeviceClockContextProvider(
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    private val localeProvider: () -> Locale = Locale::getDefault
) {
    fun snapshot(): DeviceClockSnapshot {
        val zoneId = zoneIdProvider()
        val locale = localeProvider()
        val dateTime = ZonedDateTime.ofInstant(clock.instant(), zoneId)
        return DeviceClockSnapshot(
            instant = dateTime.toInstant(),
            localDate = dateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
            localTime = dateTime.toLocalTime().format(EXACT_TIME_FORMATTER),
            timeZoneId = zoneId.id,
            utcOffset = dateTime.offset.displayValue(),
            localeTag = locale.toLanguageTag()
        )
    }

    fun contextFor(messages: List<ChatMessage>): String {
        val latestUserText = messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()
        return contextForUserText(latestUserText)
    }

    fun contextForUserText(latestUserText: String): String {
        val value = snapshot()
        return buildString {
            append("运行时信息：当前本地日期为 ")
                .append(value.localDate)
                .append("，时区为 ")
                .append(value.timeZoneId)
                .append("。\n日期和时间以此设备运行时信息为准，不得根据训练数据猜测。")
            if (asksForCurrentExactTime(latestUserText)) {
                append("\n用户明确询问当前时间：设备本地时间为 ")
                    .append(value.localTime)
                    .append("，UTC 偏移为 ")
                    .append(value.utcOffset)
                    .append("。")
            }
        }
    }

    fun asksForCurrentExactTime(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        return CURRENT_TIME_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }
    }

    private fun ZoneOffset.displayValue(): String {
        val id = id
        return if (id == "Z") "+00:00" else id
    }

    private companion object {
        private val EXACT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val CURRENT_TIME_PATTERNS = listOf(
            Regex("(?:现在|当前|此刻|此时|眼下).{0,10}(?:几点|时间)", RegexOption.IGNORE_CASE),
            Regex("(?:几点|时间).{0,10}(?:现在|当前|此刻|此时|眼下)", RegexOption.IGNORE_CASE),
            Regex("几点(?:了|钟)?(?:\\?|？|$)", RegexOption.IGNORE_CASE),
            Regex("\\bwhat\\s+time\\s+is\\s+it\\b", RegexOption.IGNORE_CASE),
            Regex("\\bcurrent\\s+(?:local\\s+)?time\\b", RegexOption.IGNORE_CASE),
            Regex("\\btime\\s+(?:right\\s+)?now\\b", RegexOption.IGNORE_CASE)
        )
    }
}

package com.muyuchat.mca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClockWebSearchRoutingTest {
    @Test
    fun pureDateTimeAndZoneQuestionsUseDeviceClock() {
        assertTrue("今天几号？".isDeviceClockOnlyQuestion())
        assertTrue("现在几点了".isDeviceClockOnlyQuestion())
        assertTrue("当前时区是什么？".isDeviceClockOnlyQuestion())
        assertTrue("What time is it?".isDeviceClockOnlyQuestion())
        assertFalse(
            WebSearchPlan(
                userQuestion = "今天几号？",
                queries = listOf("今天 日期", "current date")
            ).shouldUseWebSearchAutomatically(WebSearchTriggerMode.SMART)
        )
    }

    @Test
    fun externalTodayFactsStillRequireFreshInformation() {
        assertFalse("今天有什么新闻？".isDeviceClockOnlyQuestion())
        assertFalse("今天上海天气如何？".isDeviceClockOnlyQuestion())
        assertFalse("今天发生了什么大事？".isDeviceClockOnlyQuestion())
        assertTrue(
            WebSearchPlan(
                userQuestion = "今天有什么新闻？",
                queries = listOf("今天 新闻")
            ).shouldUseWebSearchAutomatically(WebSearchTriggerMode.SMART)
        )
    }

    @Test
    fun explicitSearchRequestStillHonorsUserIntent() {
        assertTrue(
            WebSearchPlan(
                userQuestion = "请联网查今天几号",
                queries = listOf("今天 日期"),
                explicitSearchRequested = true
            ).shouldUseWebSearchAutomatically(WebSearchTriggerMode.SMART)
        )
    }
}

package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the late model-load tab landing (proposal mca-native-video-workflow-v1 V0-02): a chat
 * model that finishes loading in the background must not eject the user from an overlay
 * workspace. DSH keeps its composed host for the gateway refresh; the video workspace keeps its
 * page so an in-flight video run is never interrupted by navigation. Every other tab keeps the
 * established chat landing.
 */
class AppTabLateModelLoadTest {

    @Test
    fun `overlay workspaces keep their tab`() {
        assertEquals(AppTab.DSH_WORKSPACE, tabAfterLateModelLoad(AppTab.DSH_WORKSPACE))
        assertEquals(AppTab.VIDEO_WORKSPACE, tabAfterLateModelLoad(AppTab.VIDEO_WORKSPACE))
    }

    @Test
    fun `every other tab lands on chat`() {
        val landingTabs = AppTab.entries.toSet() - setOf(AppTab.DSH_WORKSPACE, AppTab.VIDEO_WORKSPACE)
        assertTrue(landingTabs.isNotEmpty())
        for (tab in landingTabs) {
            assertEquals("tab $tab must land on CHAT", AppTab.CHAT, tabAfterLateModelLoad(tab))
        }
    }
}

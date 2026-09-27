package com.muyuchat.mca

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muyuchat.feature.chat.BrowserTask
import com.muyuchat.feature.chat.BrowserTaskAction
import com.muyuchat.feature.chat.BrowserTaskEvent
import com.muyuchat.feature.chat.BrowserTaskPhase
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import com.muyuchat.feature.chat.InternalBrowserDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionBrowserWorkflowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rejectingResultOriginAndClosingKeepsLateNavigationInactive() {
        var task by mutableStateOf(pendingResult())
        var visible by mutableStateOf(true)
        val events = mutableListOf<BrowserTaskEvent>()
        compose.setContent {
            MaterialTheme {
                Text("browser-owner-chat")
                if (visible) InternalBrowserDialog(
                    task = task,
                    onTaskEvent = { event -> events += event; task = BrowserTaskStateMachine.reduce(task, event) },
                    onDismiss = { visible = false }
                )
            }
        }

        compose.onNodeWithText(RESULT_URL).assertIsDisplayed()
        compose.onNodeWithText("\u53d6\u6d88\u8df3\u8f6c").performClick()
        compose.runOnIdle {
            assertEquals(BrowserTaskPhase.BLOCKED, task.phase)
            assertFalse(task.approvedOrigins.contains(RESULT_ORIGIN))
            assertTrue(events.any { it.action == BrowserTaskAction.REJECT_NAVIGATION })
        }
        compose.onNodeWithContentDescription("\u5173\u95ed\u7f51\u9875").performClick()
        compose.onNodeWithText("browser-owner-chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("\u5173\u95ed\u7f51\u9875").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(task.windowVisible)
            assertTrue(events.any { it.action == BrowserTaskAction.CANCEL })
            val closed = task
            task = BrowserTaskStateMachine.reduce(task, BrowserTaskEvent(
                task.taskId, task.navigationId, BrowserTaskAction.REQUEST_NAVIGATION,
                url = RESULT_URL, userInitiated = true
            ))
            assertEquals(closed, task)
        }
    }

    @Test
    fun explicitVisitApprovesOnlyTheSelectedOriginAndCloseStopsTheTask() {
        var task by mutableStateOf(pendingResult())
        var visible by mutableStateOf(true)
        var approved: BrowserTask? = null
        compose.setContent {
            MaterialTheme {
                Text("browser-owner-chat")
                if (visible) InternalBrowserDialog(
                    task = task,
                    onTaskEvent = { event ->
                        task = BrowserTaskStateMachine.reduce(task, event)
                        if (event.action == BrowserTaskAction.CONFIRM_NAVIGATION) approved = task
                    },
                    onDismiss = { visible = false }
                )
            }
        }

        compose.onNodeWithText("\u8bbf\u95ee").performClick()
        compose.runOnIdle {
            val visited = requireNotNull(approved)
            assertEquals(RESULT_URL, visited.currentUrl)
            assertEquals(setOf(RESULT_ORIGIN), visited.approvedOrigins)
            assertEquals("chat-session:browser-fixture", visited.owner)
        }
        compose.onNodeWithContentDescription("\u5173\u95ed\u7f51\u9875").performClick()
        compose.runOnIdle {
            assertFalse(visible)
            assertFalse(task.windowVisible)
        }
        compose.onNodeWithText("browser-owner-chat").assertIsDisplayed()
    }

    private fun pendingResult(): BrowserTask {
        val task = BrowserTaskStateMachine.begin(BrowserTaskStateMachine.acknowledgeVisible(
            BrowserTaskStateMachine.create("chat-session:browser-fixture", "https://search.example.invalid/query")
        ))
        return BrowserTaskStateMachine.requestNavigation(task, RESULT_URL, requiresConfirmation = true)
    }

    private companion object {
        const val RESULT_URL = "https://result.example.invalid/article"
        const val RESULT_ORIGIN = "https://result.example.invalid"
    }
}

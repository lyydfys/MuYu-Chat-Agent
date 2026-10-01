package com.muyuchat.mca

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImageWorkerExitDiagnosisTest {
    @Test fun ignoresOldDeathsAndOtherWorkers() {
        val old = QwenWorkerExitEvidence("com.muyuchat.mca:qwen_image21", 999L, ApplicationExitInfo.REASON_LOW_MEMORY, 0)
        val chat = old.copy(processName = "com.muyuchat.mca:local_chat", timestampMs = 1_010L)
        assertNull(currentQwenWorkerExit(listOf(old, chat), "com.muyuchat.mca", 1_000L))
    }

    @Test fun selectsLatestDeathOfThisOperation() {
        val older = QwenWorkerExitEvidence("com.muyuchat.mca:qwen_image21", 1_001L, ApplicationExitInfo.REASON_LOW_MEMORY, 0)
        val newer = older.copy(timestampMs = 1_015L, reason = ApplicationExitInfo.REASON_CRASH_NATIVE, status = 11)
        assertEquals(newer, currentQwenWorkerExit(listOf(newer, older), "com.muyuchat.mca", 1_000L))
        assertTrue(qwenWorkerExitMessage(newer).contains("原生"))
        assertTrue(qwenWorkerExitMessage(newer).contains("11"))
        assertFalse(qwenWorkerExitMessage(newer).contains("因内存压力"))
    }

    @Test fun memoryDiagnosisRequiresActualSystemEvidence() {
        val lowMemory = QwenWorkerExitEvidence("com.muyuchat.mca:qwen_image21", 1_001L, ApplicationExitInfo.REASON_LOW_MEMORY, 0)
        assertTrue(qwenWorkerExitMessage(lowMemory).contains("因内存压力"))
        assertTrue(qwenWorkerExitMessage(null).contains("尚未提供"))
        assertTrue(qwenWorkerExitMessage(null).contains("不能直接判定"))
    }
}

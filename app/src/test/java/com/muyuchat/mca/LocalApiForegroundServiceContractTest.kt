package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiForegroundServiceContractTest {
    @Test
    fun `sticky restart cannot outlive the MainViewModel owned API server`() {
        val source = serviceSource()
        val onStartCommand = Regex(
            "override fun onStartCommand[\\s\\S]*?(?=\\n    override fun onBind)"
        ).find(source)?.value

        assertNotNull("LocalApiForegroundService.onStartCommand is missing", onStartCommand)
        val method = requireNotNull(onStartCommand)
        val orphanRestartBranch = Regex(
            """if \(intent == null\) \{\s*stopSelf\(startId\)\s*return START_NOT_STICKY\s*\}"""
        )
        assertTrue(
            "An orphaned system restart must stop before publishing a notification",
            orphanRestartBranch.containsMatchIn(method)
        )
        assertEquals(2, Regex("return START_NOT_STICKY").findAll(method).count())
        assertFalse(method.contains("return START_STICKY"))
        assertFalse(source.contains("MCA 本地 API 保活通知"))
    }

    private fun serviceSource(): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/LocalApiForegroundService.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/LocalApiForegroundService.kt")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate LocalApiForegroundService.kt")
    }
}

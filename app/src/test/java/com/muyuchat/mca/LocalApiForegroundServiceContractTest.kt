package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiForegroundServiceContractTest {
    @Test
    fun `foreground promotion wins over a queued stop and orphan restart`() {
        val source = serviceSource()
        val onCreate = Regex(
            "override fun onCreate\\(\\)[\\s\\S]*?(?=\\n    override fun onStartCommand)"
        ).find(source)?.value
        val onStartCommand = Regex(
            "override fun onStartCommand[\\s\\S]*?(?=\\n    override fun onBind)"
        ).find(source)?.value

        assertNotNull("LocalApiForegroundService.onCreate is missing", onCreate)
        assertNotNull("LocalApiForegroundService.onStartCommand is missing", onStartCommand)
        val create = requireNotNull(onCreate)
        val method = requireNotNull(onStartCommand)
        assertTrue(
            "The service must promote itself before queued start/stop commands can race",
            create.contains("ServiceCompat.startForeground(")
        )
        assertTrue(method.contains("if (intent == null || !isRequested())"))
        assertTrue(source.contains("serviceCreated || serviceForeground"))
        assertTrue(source.contains("restartAfterDestroy"))
        assertTrue(source.contains("fun isForegroundReady(): Boolean"))
        assertTrue(source.contains("requestedRunning && serviceCreated && serviceForeground"))
        assertEquals(1, Regex("return START_NOT_STICKY").findAll(method).count())
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

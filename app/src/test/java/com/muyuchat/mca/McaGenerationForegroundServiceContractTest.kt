package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the Android foreground-service deadline fix from regressing when the lease registry is
 * refactored. The service must promote itself before inspecting process-local lease state.
 */
class McaGenerationForegroundServiceContractTest {
    @Test
    fun `generation service promotes on create and again at command boundary`() {
        val source = serviceSource()
        val onCreate = Regex(
            "override fun onCreate\\(\\)[\\s\\S]*?(?=\\n    override fun onStartCommand)"
        ).find(source)?.value
        val onStartCommand = Regex(
            "override fun onStartCommand[\\s\\S]*?(?=\\n    override fun onBind)"
        ).find(source)?.value

        assertNotNull("McaGenerationForegroundService.onCreate is missing", onCreate)
        assertNotNull("McaGenerationForegroundService.onStartCommand is missing", onStartCommand)
        val create = requireNotNull(onCreate)
        val start = requireNotNull(onStartCommand)
        assertTrue(create.contains("ensureChannel()"))
        assertTrue(create.contains("ServiceCompat.startForeground("))
        assertTrue(
            "onCreate must promote before any process-local lease lookup",
            create.indexOf("ServiceCompat.startForeground(") < create.indexOf("buildBootstrapNotification()") + 1
        )
        assertTrue(start.contains("ServiceCompat.startForeground("))
        assertTrue(start.contains("leases.withCurrentTask"))
        assertTrue(start.contains("buildNotification(task)"))
        assertEquals(1, Regex("return START_NOT_STICKY").findAll(start).count())
    }

    private fun serviceSource(): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(7) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/McaGenerationForegroundService.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/McaGenerationForegroundService.kt")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate McaGenerationForegroundService.kt")
    }
}

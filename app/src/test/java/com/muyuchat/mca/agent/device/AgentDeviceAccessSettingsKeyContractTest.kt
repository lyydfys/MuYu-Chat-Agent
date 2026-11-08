package com.muyuchat.mca.agent.device

import com.muyuchat.mca.agent.device.sensors.AgentSensorCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract between the app-side consent store and the Settings card.
 *
 * `feature:settings` cannot depend on `:app` (module direction), so the card writes the same
 * SharedPreferences file and the same keys by literal. That is only acceptable while the two sides
 * are provably in sync: this test reads both sources and fails on any drift, before a user toggle
 * could silently stop reaching the route.
 */
class AgentDeviceAccessSettingsKeyContractTest {

    private val storeSource = source(
        "app/src/main/java/com/muyuchat/mca/agent/device/AgentDeviceAccessStore.kt"
    )
    // The device-authorisation card lives on its own settings page now (the settings IA split the
    // "what may the phone hand over" rows out of 隐私与数据 into 设备与外设). The contract itself is
    // unchanged: the card still writes the store's preference file and keys by literal, and this
    // test still fails on any drift between the two sides.
    private val settingsSource = source(
        "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsDeviceScreen.kt"
    )
    private val manifest = source("app/src/main/AndroidManifest.xml")

    @Test
    fun `both sides use the same preference file and sms keys`() {
        assertEquals(
            "the settings card must write the store's preference file",
            "\"mca_agent_device_access\"",
            Regex("\"mca_agent_device_access\"").find(settingsSource)?.value
        )
        assertTrue(storeSource.contains("\"mca_agent_device_access\""))

        listOf("sms.read_enabled", "sms.skip_confirmation_session").forEach { key ->
            assertTrue("the store must declare $key", storeSource.contains("\"$key\""))
            assertTrue("the settings card must declare $key", settingsSource.contains("\"$key\""))
        }
        assertTrue("the sensor key prefix must match", settingsSource.contains("\"sensor.\""))
        assertTrue("the sensor key prefix must match", storeSource.contains("\"sensor.\""))
    }

    @Test
    fun `every catalog sensor id has exactly one settings row`() {
        val block = settingsSource.substringAfter("private val DEVICE_SENSOR_SETTINGS")
            .substringBefore("private fun deviceAccessPermissionGranted")
        val ids = Regex("id = \"([a-z_]+)\"")
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "each catalogue capability needs one authorisation row",
            AgentSensorCatalog.all.map { AgentSensorCatalog.wireName(it.id) }.toSet(),
            ids.toSet()
        )
        assertEquals("no capability may be listed twice", ids.size, ids.distinct().size)
    }

    @Test
    fun `the settings card asks for the same permissions the catalogue declares`() {
        AgentSensorCatalog.all.filter { it.permission != null }.forEach { capability ->
            assertTrue(
                "settings card is missing ${capability.permission}",
                settingsSource.contains("\"${capability.permission}\"")
            )
        }
        assertTrue(settingsSource.contains("\"android.permission.READ_SMS\""))
        assertTrue(settingsSource.contains("\"android.permission.ACTIVITY_RECOGNITION\""))
        assertTrue(settingsSource.contains("\"android.permission.ACCESS_FINE_LOCATION\""))
    }

    @Test
    fun `the manifest declares the optional permissions without requiring hardware`() {
        listOf(
            "android.permission.READ_SMS",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.ACCESS_FINE_LOCATION"
        ).forEach { permission ->
            assertTrue("manifest is missing $permission", manifest.contains(permission))
        }
        assertTrue(manifest.contains("android.hardware.sensor.stepcounter\" android:required=\"false\""))
        assertTrue(manifest.contains("android.hardware.location.gps\" android:required=\"false\""))
        assertTrue(manifest.contains("android.hardware.camera.flash\" android:required=\"false\""))
    }

    @Test
    fun `the sms session skip is cleared when the app process starts`() {
        // "本次运行内不再询问" must not survive a restart: the store clears the persisted flag when
        // the DSH lane is created (once per process), and reading it never re-enables it.
        assertTrue(
            "the store must detect a leftover skip flag on construction",
            storeSource.contains("preferences.getBoolean(SMS_SKIP_CONFIRMATION_KEY, false)")
        )
        assertTrue(
            "the store must clear the leftover skip flag on construction",
            storeSource.contains("putBoolean(SMS_SKIP_CONFIRMATION_KEY, false)")
        )
        assertTrue(storeSource.contains("init {"))
    }

    @Test
    fun `the sms lane never persists content or logs it`() {
        listOf("AgentSmsReader.kt", "AndroidAgentSmsReader.kt").forEach { file ->
            val source = source("app/src/main/java/com/muyuchat/mca/agent/device/sms/$file")
            assertFalse("$file must not write preferences", source.contains("SharedPreferences"))
            assertFalse("$file must not log message content", source.contains("Log."))
            assertFalse("$file must not touch files", source.contains("File("))
            assertFalse("$file must not send messages", source.contains("sendTextMessage"))
            assertFalse("$file must not delete messages", source.contains("delete("))
            assertFalse("$file must not mark messages read", source.contains("markAsRead"))
        }
    }

    @Test
    fun `the sensor lane reads once and never subscribes for the process lifetime`() {
        val source = source(
            "app/src/main/java/com/muyuchat/mca/agent/device/sensors/AndroidAgentSensorReader.kt"
        )
        // Every read goes through exactly one registration site, and every registration has a
        // matching release path (event, failure and cancellation). No foreground service is used.
        val registrations = Regex("manager\\.registerListener\\(").findAll(source).count()
        val releases = Regex("manager\\.unregisterListener\\(").findAll(source).count()
        assertEquals("one listener registration site per read", 1, registrations)
        assertTrue("every registration must be released", releases >= registrations)
        assertTrue(source.contains("awaitFirstEvent"))
        assertFalse("no foreground acquisition service", source.contains("startService"))
        assertFalse("no bound sensor service", source.contains("bindService"))
    }

    private fun source(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }
}

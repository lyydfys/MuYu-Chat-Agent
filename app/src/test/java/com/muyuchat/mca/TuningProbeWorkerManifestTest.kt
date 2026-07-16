package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningProbeWorkerManifestTest {
    @Test
    fun tuningWorkerIsPrivateAndRunsInDedicatedProcess() {
        val manifest = sequenceOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).firstOrNull(File::isFile)
        assertNotNull("Unable to locate app AndroidManifest.xml", manifest)
        val text = requireNotNull(manifest).readText()
        val service = Regex(
            "<service[^>]*android:name=\\\"\\.TuningProbeWorkerService\\\"[^>]*/>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(text)?.value
        assertNotNull("TuningProbeWorkerService manifest entry is missing", service)
        val declaration = requireNotNull(service)
        assertTrue(declaration.contains("android:process=\":tuning\""))
        assertTrue(declaration.contains("android:exported=\"false\""))
        assertTrue(declaration.contains("android:stopWithTask=\"true\""))
        assertFalse(declaration.contains("isolatedProcess=\"false\""))
    }
}

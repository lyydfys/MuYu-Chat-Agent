package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundWorkerLifecycleContractTest {
    @Test
    fun `resident text worker survives task removal`() {
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val declaration = serviceDeclaration(manifest, ".LocalChatWorkerService")
        val proxy = sourceFile("app/src/main/java/com/muyuchat/mca/RemoteLocalChatRunner.kt")
        val service = sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerService.kt")

        assertTrue(declaration.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(declaration.contains("android:stopWithTask=\"false\""))
        assertTrue(proxy.contains("private fun startResidentService()"))
        assertTrue(proxy.contains("ContextCompat.startForegroundService(appContext, serviceIntent)"))
        assertTrue(service.contains("override fun onStartCommand("))
        assertTrue(service.contains("return START_NOT_STICKY"))
    }

    @Test
    fun `view model detach never shuts down the shared native worker`() {
        val proxy = sourceFile("app/src/main/java/com/muyuchat/mca/RemoteLocalChatRunner.kt")
        val close = functionBody(proxy, "override fun close()")
        val shutdown = functionBody(proxy, "override fun shutdown()")

        assertFalse(close.contains(".shutdown()"))
        assertTrue(close.contains("releaseBinding"))
        assertTrue(shutdown.contains("endpoint.shutdown()"))
    }

    @Test
    fun `image worker is foreground only while native work is active`() {
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val declaration = serviceDeclaration(manifest, ".LocalImageWorkerService")
        val service = sourceFile(
            "app/src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt"
        )

        assertTrue(declaration.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(declaration.contains("local_image_generation"))
        assertTrue(service.contains("promoteActiveOperationToForeground(\"IMAGE_GENERATION\")"))
        assertTrue(service.contains("promoteActiveOperationToForeground(\"ESRGAN_UPSCALE\")"))
        assertTrue(service.contains("ServiceCompat.startForeground("))
        assertTrue(service.contains("ServiceCompat.stopForeground("))
        assertTrue(service.contains("leaveActiveOperationForeground()"))
    }

    private fun serviceDeclaration(manifest: String, serviceName: String): String {
        val name = "android:name=\"$serviceName\""
        val nameIndex = manifest.indexOf(name)
        require(nameIndex >= 0) { "Missing service $serviceName" }
        val start = manifest.lastIndexOf("<service", nameIndex)
        val selfClosing = manifest.indexOf("/>", nameIndex)
        val explicitClose = manifest.indexOf("</service>", nameIndex)
        val end = listOf(selfClosing, explicitClose)
            .filter { it >= 0 }
            .minOrNull()
            ?: error("Unterminated service $serviceName")
        return manifest.substring(start, end + 2)
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }
}

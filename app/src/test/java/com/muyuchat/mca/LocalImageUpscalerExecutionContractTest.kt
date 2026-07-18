package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageUpscalerExecutionContractTest {
    @Test
    fun productPublicationBoundsRejectOversizedFinalBeforeNativeExecution() {
        val accepted = validatedLocalImageUpscalePublicationDimensions(
            sourceWidth = 2_000,
            sourceHeight = 2_000,
            targetScale = 2
        )
        assertEquals(4_000, accepted.width)
        assertEquals(4_000, accepted.height)
        assertEquals(16_000_000L, accepted.pixels)

        assertTrue(
            runCatching {
                validatedLocalImageUpscalePublicationDimensions(1_024, 1_024, 4)
            }.isFailure
        )
        assertTrue(
            runCatching {
                validatedLocalImageUpscalePublicationDimensions(1_200, 800, 4)
            }.isFailure
        )
    }

    @Test
    fun workerLeaseClosesTheModelHashToOpenRaceAndRemainsCancellable() {
        val inputs = source("LocalImageInputFiles.kt")
        val store = source("LocalImageUpscalerStore.kt")
        val service = source("LocalImageWorkerService.kt")

        assertTrue(inputs.contains("channel.tryLock(0L, Long.MAX_VALUE, true)"))
        assertTrue(inputs.contains("source.sha256(isCancelled)"))
        assertTrue(inputs.contains("throwIfUpscaleCancelled(isCancelled)"))
        assertTrue(inputs.contains("LocalImageWorkerUpscaleInputs(") && inputs.contains(": AutoCloseable"))
        assertTrue(store.contains("channel.tryLock()"))
        assertTrue(store.contains("lockFileName(record.fileName)"))
        assertTrue(service.contains("workerInputs?.close()"))
    }

    @Test
    fun providerRequiresPhysicalEvidenceAndBoundsBitmapPublicationMemory() {
        val provider = source("LocalImageProvider.kt")

        listOf(
            "modelHashVerified",
            "modelFileIdentityStable",
            "upscalerFileName",
            "physicalComputeCount",
            "physicalComputeSuccessCount",
            "physicalTileComputeCount",
            "executionCompleted",
            "nativeFixedScale",
            "postResizeMethod",
            "android_bitmap_filtered",
            "MAX_UPSCALE_SOURCE_PIXELS",
            "MAX_UPSCALE_NATIVE_OUTPUT_PIXELS",
            "MAX_UPSCALE_NATIVE_PNG_BYTES",
            "MAX_UPSCALE_OUTPUT_PIXELS",
            "MAX_UPSCALE_PNG_BYTES",
            "coroutineContext.ensureActive()"
        ).forEach { needle -> assertTrue("Missing $needle", provider.contains(needle)) }
        assertTrue(provider.contains("FileOutputStream(postprocessedOutput)"))
        assertTrue(provider.contains("inSampleSize = decodeSampleSize"))
        assertTrue(provider.contains("val requiredStages = 255L"))
        assertFalse(provider.contains("ByteArrayOutputStream"))
        assertFalse(provider.contains("val finalBytes = if (targetScale == nativeScale)"))
    }

    private fun source(fileName: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/$fileName"),
                File(root, "app/src/main/java/com/muyuchat/mca/$fileName")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate $fileName")
    }
}

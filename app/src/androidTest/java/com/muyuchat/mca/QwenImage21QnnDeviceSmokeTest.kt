package com.muyuchat.mca

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local device smoke for the experimental MNN QNN DiT path. */
@RunWith(AndroidJUnit4::class)
class QwenImage21QnnDeviceSmokeTest {
    @Test
    fun qnnDditProducesPngAndNativeAudit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundleContainer = File(
            "/storage/emulated/0/Android/data/${context.packageName}/files/image_models/" +
                "bundle-qwen_image_21_mnn_opencl_bundle"
        )
        val bundle = bundleContainer.listFiles().orEmpty()
            .firstOrNull { it.isDirectory && it.name.startsWith("mnn-bundle-") }
            ?: bundleContainer
        assumeTrue("Qwen model bundle is not installed", bundle.isDirectory)

        val runtime = File(context.applicationInfo.nativeLibraryDir)
        val htpVariant = LiteRtQualcommRuntimeStager.variantForSocModel(android.os.Build.SOC_MODEL)
        val transportFiles = when (htpVariant) {
            "v73" -> listOf("libQnnHtpV73Stub.so", "libQnnHtpV73Skel.so")
            "v75" -> listOf("libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so")
            "v79" -> listOf("libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so")
            "v81" -> listOf("libQnnHtpV81Stub.so", "libQnnHtpV81Skel.so")
            else -> emptyList()
        }
        val required = listOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so")
        assumeTrue("App-packaged QAIRT transport is incomplete", required.all { File(runtime, it).isFile })
        val presentTransportFiles = transportFiles.filter { File(runtime, it).isFile }
        println(
            "QWEN_QNN_RUNTIME mode=product variant=$htpVariant directory=${runtime.absolutePath} " +
                "required=${required.joinToString(",")} " +
                "transportPresent=${presentTransportFiles.joinToString(",")}"
        )

        val client = QwenImage21WorkerClient(context)
        val fingerprint = bundle.walkTopDown().filter(File::isFile).sumOf { it.length() }.toString()
        val loaded = client.load(
            bundleRoot = bundle.absolutePath,
            useGpu = true,
            backend = QwenImage21Backend.QNN,
            qnnRuntimePath = runtime.absolutePath,
            threads = 2,
            bundleFingerprint = fingerprint,
            requestId = UUID.randomUUID().toString()
        )
        check(loaded.loaded && loaded.backendConfigured == "MNN_QNN") {
            "QNN load did not resolve MNN_QNN: ${loaded.rawJson}"
        }
        val output = File(context.cacheDir, "qwen-qnn-smoke-${System.currentTimeMillis()}.png")
        val result = client.generate(
            requestId = UUID.randomUUID().toString(),
            bundleRoot = bundle.absolutePath,
            prompt = "a red apple on a white table, studio photo",
            inputImagePath = null,
            outputPath = output.absolutePath,
            width = 320,
            height = 320,
            steps = 2,
            seed = 1729,
            threads = 2,
            useGpu = true,
            backend = QwenImage21Backend.QNN,
            qnnRuntimePath = runtime.absolutePath,
            bundleFingerprint = fingerprint
        )
        println(
            "QWEN_QNN_SMOKE output=${result.outputPath} bytes=${result.outputBytes} " +
                "elapsedMs=${result.elapsedMs} audit=${result.executionAudit}"
        )
        check(result.executionAudit.optBoolean("nativeRunCompleted", false)) {
            "QNN generation did not complete natively: ${result.executionAudit}"
        }
        check(result.executionAudit.optBoolean("backendExecutionConfirmedByNative", false)) {
            "QNN backend was not confirmed by native audit: ${result.executionAudit}"
        }
        check(output.isFile && output.length() > 1024L) { "QNN output PNG is missing or empty" }
        client.unload()
    }
}

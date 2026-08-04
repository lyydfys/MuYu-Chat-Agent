package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalQnnSmokeSpecTest {
    @Test
    fun qnnSmokeSpecRequiresContextInputsAndOutputs() {
        val spec = QnnSmokeSpec.fromSmokeJson(
            JSONObject(
                """
                {
                  "graphName": "sd15_unet",
                  "contextBinary": "diffusion/unet_context.bin",
                  "timeoutSeconds": 180,
                  "inputs": [
                    {"name": "latent", "dataType": "float32", "shape": [1, 4, 64, 64]},
                    {"name": "timestep", "dtype": "int32", "shape": [1]}
                  ],
                  "outputs": [
                    {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertTrue(spec.completeForGraphSmoke)
        assertEquals("sd15_unet", spec.graphName)
        assertEquals("diffusion/unet_context.bin", spec.contextBinary)
        assertEquals(2, spec.inputs.size)
        assertEquals(1, spec.outputs.size)
        assertEquals(listOf(1, 4, 64, 64), spec.inputs.first().shape)
        assertTrue(spec.bufferPlan.ready)
        assertEquals(65_540L, spec.bufferPlan.totalInputBytes)
        assertEquals(65_536L, spec.bufferPlan.totalOutputBytes)
    }

    @Test
    fun qnnSmokeSpecWithoutOutputIsNotGraphReady() {
        val spec = QnnSmokeSpec.fromSmokeJson(
            JSONObject(
                """
                {
                  "contextBinary": "diffusion/unet_context.bin",
                  "inputs": [
                    {"name": "latent", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertFalse(spec.completeForGraphSmoke)
        assertFalse(spec.bufferPlan.ready)
    }

    @Test
    fun unsupportedTensorDtypeBlocksBufferPlan() {
        val spec = QnnSmokeSpec.fromSmokeJson(
            JSONObject(
                """
                {
                  "contextBinary": "diffusion/unet_context.bin",
                  "inputs": [
                    {"name": "latent", "dataType": "complex64", "shape": [1, 4, 64, 64]}
                  ],
                  "outputs": [
                    {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertFalse(spec.bufferPlan.ready)
        assertFalse(spec.completeForGraphSmoke)
        assertFalse(spec.bufferPlan.inputs.first().supported)
    }

    @Test
    fun unsafeContextPathBlocksNativeSmokeValidation() {
        val spec = QnnSmokeSpec.fromSmokeJson(
            JSONObject(
                """
                {
                  "graphName": "sd15_unet",
                  "contextBinary": "../diffusion/unet_context.bin",
                  "inputs": [
                    {"name": "latent", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ],
                  "outputs": [
                    {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertFalse(spec.validation.readyForNativeSmoke)
        assertTrue(spec.validation.blockingReasons.any { it.contains("safe relative bundle path") })
        assertFalse(spec.completeForGraphSmoke)
    }

    @Test
    fun duplicateTensorNamesBlockNativeSmokeValidation() {
        val spec = QnnSmokeSpec.fromSmokeJson(
            JSONObject(
                """
                {
                  "graphName": "sd15_unet",
                  "contextBinary": "diffusion/unet_context.bin",
                  "inputs": [
                    {"name": "latent", "dataType": "float32", "shape": [1, 4, 64, 64]},
                    {"name": "latent", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ],
                  "outputs": [
                    {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertFalse(spec.validation.readyForNativeSmoke)
        assertTrue(spec.validation.blockingReasons.any { it.contains("Duplicate input tensor name: latent") })
    }

    @Test
    fun qnnSmokeContextIdentityRequiresAnExactSizeAndShaPair() {
        val root = Files.createTempDirectory("qnn-smoke-context-identity").toFile()
        val context = File(root, "vae_encoder.bin").apply {
            writeBytes("encoder-bytes".toByteArray())
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(context.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val valid = QnnSmokeSpec.fromSmokeJson(
            JSONObject()
                .put("graphName", "model")
                .put("contextBinary", "vae_encoder.bin")
                .put("expectedContextSizeBytes", context.length())
                .put("expectedContextSha256", sha256)
                .put("inputs", org.json.JSONArray().put(
                    JSONObject()
                        .put("name", "input")
                        .put("dataType", "uint16")
                        .put("shape", org.json.JSONArray(listOf(1, 3, 512, 512)))
                ))
                .put("outputs", org.json.JSONArray().put(
                    JSONObject()
                        .put("name", "mean")
                        .put("dataType", "uint16")
                        .put("shape", org.json.JSONArray(listOf(1, 4, 64, 64)))
                ))
        )

        assertTrue(valid.completeForGraphSmoke)
        assertEquals(context.canonicalFile, valid.contextBinaryFileIn(root))
        assertEquals(context.length(), valid.toJson().getLong("expectedContextSizeBytes"))
        assertEquals(sha256, valid.toJson().getString("expectedContextSha256"))

        context.appendText("changed")
        assertEquals(null, valid.contextBinaryFileIn(root))

        val missingSha = valid.copy(expectedContextSha256 = null)
        assertFalse(missingSha.validation.readyForNativeSmoke)
    }

    @Test
    fun oversizedTensorBuffersBlockNativeSmokeValidation() {
        val spec = QnnSmokeSpec.fromSmokeJson(
            JSONObject(
                """
                {
                  "graphName": "giant_graph",
                  "contextBinary": "diffusion/giant_context.bin",
                  "inputs": [
                    {"name": "latent", "dataType": "float32", "shape": [1, 4, 8192, 8192]}
                  ],
                  "outputs": [
                    {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 64, 64]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertTrue(spec.bufferPlan.ready)
        assertFalse(spec.validation.readyForNativeSmoke)
        assertTrue(spec.validation.blockingReasons.any { it.contains("native smoke limit") })
    }

    @Test
    fun imageBundleManifestKeepsQnnSmokeTensorMetadata() {
        val root = Files.createTempDirectory("qnn-image-smoke-spec").toFile()
        root.touch("diffusion/unet_context.bin")
        root.touch("vae/vae_decoder_context.bin")
        root.touch("text_encoder/clip_context.bin")
        root.touch("tokenizer/tokenizer.json")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.image_engine.bundle.v1",
              "runtime": "QNN_HTP",
              "components": [
                {"role": "DIFFUSION", "path": "diffusion/unet_context.bin"},
                {"role": "VAE", "path": "vae/vae_decoder_context.bin"},
                {"role": "TEXT_ENCODER", "path": "text_encoder/clip_context.bin"},
                {"role": "TOKENIZER", "path": "tokenizer/tokenizer.json"}
              ],
              "smoke": {
                "width": 384,
                "height": 384,
                "steps": 1,
                "graphName": "sd15_unet",
                "contextBinary": "diffusion/unet_context.bin",
                "inputs": [
                  {"name": "latent", "dataType": "float32", "shape": [1, 4, 48, 48]}
                ],
                "outputs": [
                  {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 48, 48]}
                ]
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val manifest = localImageBundleManifestFromRoot(root)!!

        assertTrue(manifest.qnnSmokeSpec.completeForGraphSmoke)
        assertEquals("sd15_unet", manifest.qnnSmokeSpec.graphName)
        assertEquals(1, manifest.qnnSmokeSpec.inputs.size)
        assertEquals(1, manifest.qnnSmokeSpec.outputs.size)
        assertEquals(36_864L, manifest.qnnSmokeSpec.bufferPlan.totalInputBytes)
        assertEquals(36_864L, manifest.qnnSmokeSpec.bufferPlan.totalOutputBytes)
    }

    @Test
    fun imageBundleManifestKeepsQnnSmokeSuiteMetadata() {
        val root = Files.createTempDirectory("qnn-image-smoke-suite").toFile()
        root.touch("unet.bin")
        root.touch("vae_decoder.bin")
        root.touch("clip_v2.mnn")
        root.touch("tokenizer.json")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.image_engine.bundle.v1",
              "runtime": "QNN_HTP",
              "components": [
                {"role": "DIFFUSION", "path": "unet.bin"},
                {"role": "VAE", "path": "vae_decoder.bin"},
                {"role": "TEXT_ENCODER", "path": "clip_v2.mnn"},
                {"role": "TOKENIZER", "path": "tokenizer.json"}
              ],
              "smokes": [
                {
                  "graphName": "model",
                  "contextBinary": "unet.bin",
                  "inputs": [
                    {"name": "sample", "dataType": "uint16", "shape": [1, 4, 64, 64]},
                    {"name": "timestamp", "dataType": "int32", "shape": [1]},
                    {"name": "text_embedding", "dataType": "uint16", "shape": [1, 77, 768]}
                  ],
                  "outputs": [
                    {"name": "output", "dataType": "uint16", "shape": [1, 4, 64, 64]}
                  ]
                },
                {
                  "graphName": "model",
                  "contextBinary": "vae_decoder.bin",
                  "inputs": [
                    {"name": "input", "dataType": "uint16", "shape": [1, 4, 64, 64]}
                  ],
                  "outputs": [
                    {"name": "output", "dataType": "uint16", "shape": [1, 3, 512, 512]}
                  ]
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val manifest = localImageBundleManifestFromRoot(root)!!

        assertEquals(2, manifest.qnnSmokeSpecs.size)
        assertEquals("unet.bin", manifest.qnnSmokeSpec.contextBinary)
        assertTrue(manifest.qnnSmokeSpecs.all { it.completeForGraphSmoke })
        assertEquals("vae_decoder.bin", manifest.qnnSmokeSpecs[1].contextBinary)
        assertEquals(1_572_864L, manifest.qnnSmokeSpecs[1].bufferPlan.totalOutputBytes)
    }

    @Test
    fun visionBundleManifestKeepsQnnSmokeTensorMetadata() {
        val root = Files.createTempDirectory("qnn-vision-smoke-spec").toFile()
        root.touch("fastvlm_context.bin")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.vision_engine.bundle.v1",
              "runtime": "LITERT_QNN",
              "accelerator": "QNN_HTP",
              "components": [
                {"role": "MAIN_MODEL", "path": "fastvlm_context.bin"}
              ],
              "smoke": {
                "imageWidth": 336,
                "imageHeight": 336,
                "graphName": "fastvlm",
                "contextBinary": "fastvlm_context.bin",
                "inputs": [
                  {"name": "image", "dataType": "uint8", "shape": [1, 336, 336, 3]}
                ],
                "outputs": [
                  {"name": "tokens", "dataType": "int32", "shape": [1, 64]}
                ]
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val manifest = localVisionBundleManifestFromRoot(root)!!

        assertTrue(manifest.qnnSmokeSpec.completeForGraphSmoke)
        assertEquals("fastvlm", manifest.qnnSmokeSpec.graphName)
        assertEquals(1, manifest.qnnSmokeSpec.inputs.size)
        assertEquals(1, manifest.qnnSmokeSpec.outputs.size)
    }

    @Test
    fun nativeSmokeResultParsesMetadataReadinessFields() {
        val result = NativeQnnSmokeResult.fromJson(
            """
            {
              "backend": "qnn_htp",
              "message": "metadata ready",
              "runnerReady": true,
              "graphMetadataReady": true,
              "graphRunnerReady": false,
              "graphExecute": false,
              "npuActive": false,
              "smokePassed": false,
              "elapsedMs": 12,
              "compile": {
                "sdkRootConfigured": true,
                "sdkHeadersPresent": true,
                "typedGraphBindingsCompiled": true
              },
              "runtime": {
                "qnnInterfacePresent": true,
                "cdspRpcLibraryPresent": true,
                "cdspRpcLibraryLoadable": false,
                "cdspRpcMessage": "library \"libhidlbase.so\" not found"
              },
              "binaryMetadata": {
                "attempted": true,
                "parsed": true,
                "version": 3,
                "backendId": 1,
                "buildId": "v2.28.0.241029232508_102474",
                "coreApiVersion": "2.28.0",
                "backendApiVersion": "2.28.0",
                "socVersion": "",
                "socModel": 43,
                "contextBlobSize": 4096,
                "graphCount": 1,
                "graphNames": ["sd15_unet"],
                "message": "QNN context binary metadata parsed; socModel=43"
              },
              "smokeSpec": {
                "inputCount": 2,
                "outputCount": 1,
                "tensorBufferPlanReady": true,
                "validationReady": true,
                "contextBinaryBytes": 4096,
                "inputBufferBytes": 65540,
                "outputBufferBytes": 65536,
                "bufferPlan": {
                  "ready": true,
                  "totalInputBytes": 65540,
                  "totalOutputBytes": 65536,
                  "inputs": [
                    {
                      "name": "latent",
                      "role": "input",
                      "dataType": "float32",
                      "shape": [1, 4, 64, 64],
                      "elementCount": 16384,
                      "bytesPerElement": 4,
                      "byteSize": 65536,
                      "supported": true,
                      "reason": ""
                    },
                    {
                      "name": "timestep",
                      "role": "input",
                      "dataType": "int32",
                      "shape": [1],
                      "elementCount": 1,
                      "bytesPerElement": 4,
                      "byteSize": 4,
                      "supported": true,
                      "reason": ""
                    }
                  ],
                  "outputs": [
                    {
                      "name": "noise_pred",
                      "role": "output",
                      "dataType": "float32",
                      "shape": [1, 4, 64, 64],
                      "elementCount": 16384,
                      "bytesPerElement": 4,
                      "byteSize": 65536,
                      "supported": true,
                      "reason": ""
                    }
                  ]
                },
                "validation": {
                  "readyForNativeSmoke": true,
                  "blockingReasons": []
                }
              },
              "executionStage": "graph_execution_unimplemented",
              "stages": {
                "runtimeLoaded": true,
                "qnnInterfaceFound": true,
                "bundleManifestFound": true,
                "bundleGraphArtifactFound": true,
                "bundleContextBinaryFound": true,
                "bundleContextBinaryNonEmpty": true,
                "smokeMetadataComplete": true,
                "tensorBufferPlanReady": true,
                "graphMetadataReady": true,
                "sdkHeadersCompiled": true,
                "backendCreated": false,
                "contextLoaded": false,
                "graphResolved": false,
                "tensorsBound": false,
                "graphExecuted": false
              }
            }
            """.trimIndent()
        )

        assertTrue(result.graphMetadataReady)
        assertTrue(result.qnnInterfacePresent)
        assertTrue(result.cdspRpcLibraryPresent)
        assertFalse(result.cdspRpcLibraryLoadable)
        assertTrue(result.cdspRpcMessage.contains("libhidlbase.so"))
        assertTrue(result.sdkRootConfigured)
        assertTrue(result.sdkHeadersPresent)
        assertTrue(result.typedGraphBindingsCompiled)
        assertEquals(2, result.smokeInputCount)
        assertEquals(1, result.smokeOutputCount)
        assertTrue(result.tensorBufferPlanReady)
        assertTrue(result.smokeValidationReady)
        assertTrue(result.smokeValidationBlockingReasons.isEmpty())
        assertEquals(4_096L, result.contextBinaryBytes)
        assertEquals(65_540L, result.inputBufferBytes)
        assertEquals(65_536L, result.outputBufferBytes)
        assertEquals(2, result.inputTensors.size)
        assertEquals(1, result.outputTensors.size)
        assertEquals("latent", result.inputTensors.first().name)
        assertEquals(listOf(1, 4, 64, 64), result.inputTensors.first().shape)
        assertEquals(65_536L, result.inputTensors.first().byteSize)
        assertTrue(result.inputTensors.first().bindable)
        assertEquals("noise_pred", result.outputTensors.first().name)
        assertTrue(QnnExecutionDiagnostics.from(result).allTensorsBindable)
        assertTrue(QnnExecutionDiagnostics.from(result).cdspRpcLibraryPresent)
        assertFalse(QnnExecutionDiagnostics.from(result).cdspRpcLibraryLoadable)
        assertEquals("graph_execution_unimplemented", result.executionStage)
        assertTrue(result.binaryMetadata.parsed)
        assertEquals(43, result.binaryMetadata.socModel)
        assertEquals("v2.28.0.241029232508_102474", result.binaryMetadata.buildId)
        assertEquals(listOf("sd15_unet"), result.binaryMetadata.graphNames)
        assertTrue(result.runtimeLoaded)
        assertTrue(result.qnnInterfaceFound)
        assertTrue(result.bundleManifestFound)
        assertTrue(result.bundleGraphArtifactFound)
        assertTrue(result.bundleContextBinaryFound)
        assertTrue(result.bundleContextBinaryNonEmpty)
        assertTrue(result.smokeMetadataComplete)
        assertTrue(result.sdkHeadersCompiled)
        assertFalse(result.backendCreated)
        assertFalse(result.contextLoaded)
        assertFalse(result.graphResolved)
        assertFalse(result.tensorsBound)
        assertFalse(result.provesNpuExecution)
    }

    @Test
    fun nativeSmokeResultDoesNotProveNpuWhenGraphExecuteFails() {
        val result = NativeQnnSmokeResult.fromJson(
            """
            {
              "backend": "qnn_htp",
              "message": "QNN graphExecute failed",
              "runnerReady": true,
              "graphMetadataReady": true,
              "graphRunnerReady": true,
              "graphExecute": false,
              "npuActive": false,
              "smokePassed": false,
              "elapsedMs": 1200,
              "compile": {
                "sdkRootConfigured": true,
                "sdkHeadersPresent": true,
                "typedGraphBindingsCompiled": true
              },
              "runtime": {"qnnInterfacePresent": true},
              "smokeSpec": {
                "inputCount": 1,
                "outputCount": 1,
                "tensorBufferPlanReady": true,
                "validationReady": true,
                "contextBinaryBytes": 4096,
                "inputBufferBytes": 4,
                "outputBufferBytes": 4,
                "bufferPlan": {
                  "ready": true,
                  "inputs": [
                    {
                      "name": "x",
                      "role": "input",
                      "dataType": "float32",
                      "shape": [1],
                      "elementCount": 1,
                      "bytesPerElement": 4,
                      "byteSize": 4,
                      "supported": true,
                      "reason": ""
                    }
                  ],
                  "outputs": [
                    {
                      "name": "y",
                      "role": "output",
                      "dataType": "float32",
                      "shape": [1],
                      "elementCount": 1,
                      "bytesPerElement": 4,
                      "byteSize": 4,
                      "supported": true,
                      "reason": ""
                    }
                  ]
                },
                "validation": {
                  "readyForNativeSmoke": true,
                  "blockingReasons": []
                }
              },
              "executionStage": "graph_execute_failed",
              "stages": {
                "runtimeLoaded": true,
                "qnnInterfaceFound": true,
                "bundleManifestFound": true,
                "bundleGraphArtifactFound": true,
                "bundleContextBinaryFound": true,
                "bundleContextBinaryNonEmpty": true,
                "smokeMetadataComplete": true,
                "tensorBufferPlanReady": true,
                "graphMetadataReady": true,
                "sdkHeadersCompiled": true,
                "backendCreated": true,
                "contextLoaded": true,
                "graphResolved": true,
                "tensorsBound": true,
                "graphExecuted": false
              }
            }
            """.trimIndent()
        )

        assertEquals("graph_execute_failed", result.executionStage)
        assertTrue(result.graphRunnerReady)
        assertTrue(result.backendCreated)
        assertTrue(result.contextLoaded)
        assertTrue(result.graphResolved)
        assertTrue(result.tensorsBound)
        assertFalse(result.graphExecute)
        assertFalse(result.npuActive)
        assertFalse(result.smokePassed)
        assertFalse(result.provesNpuExecution)
        assertFalse(QnnExecutionDiagnostics.from(result).graphExecuted)
    }

    @Test
    fun nativeSmokeRequiresCompleteStrictContextMetadataAndOutputProofWhenReported() {
        fun result(
            contextRequired: Boolean = false,
            contextMatched: Boolean = true,
            metadataMatched: Boolean = true,
            outputFinite: Boolean = true,
            outputPassed: Boolean = true,
            nonZeroElements: Int = 4
        ): NativeQnnSmokeResult =
            NativeQnnSmokeResult.fromJson(
                JSONObject()
                    .put("message", "strict smoke")
                    .put("runnerReady", true)
                    .put("graphRunnerReady", true)
                    .put("graphExecute", true)
                    .put("npuActive", true)
                    .put("smokePassed", true)
                    .put("smokeSpec", JSONObject()
                        .put("contextIdentityRequired", contextRequired)
                        .put("contextIdentityMatched", contextMatched)
                        .put("metadataContractMatched", metadataMatched)
                        .put("outputValuesFinite", outputFinite)
                        .put("outputValidationPassed", outputPassed)
                        .put("nonZeroOutputElements", nonZeroElements)
                    )
                    .toString()
            )

        assertTrue(result().provesNpuExecution)
        assertFalse(result(metadataMatched = false, outputPassed = true).provesNpuExecution)
        assertFalse(result(metadataMatched = true, outputPassed = false).provesNpuExecution)
        assertFalse(result(outputFinite = false).provesNpuExecution)
        assertFalse(result(nonZeroElements = 0).provesNpuExecution)
        assertFalse(
            result(contextRequired = true, contextMatched = false).provesNpuExecution
        )
    }

    private fun File.touch(name: String): File =
        File(this, name).also {
            it.parentFile?.mkdirs()
            it.writeText("x")
        }
}

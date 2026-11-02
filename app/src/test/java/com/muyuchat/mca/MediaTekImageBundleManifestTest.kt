package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAotConditioningContract
import com.muyuchat.core.mediatek.MediaTekAotConditioningMode
import com.muyuchat.core.mediatek.MediaTekAotCfgMode
import com.muyuchat.core.mediatek.MediaTekAotManifest
import com.muyuchat.core.mediatek.MediaTekAotManifestJson
import com.muyuchat.core.mediatek.MediaTekAotModelIdentity
import com.muyuchat.core.mediatek.MediaTekAotModulationContract
import com.muyuchat.core.mediatek.MediaTekAotParameterContract
import com.muyuchat.core.mediatek.MediaTekAotProfile
import com.muyuchat.core.mediatek.MediaTekAotVariant
import com.muyuchat.core.mediatek.MediaTekAotVaeContract
import com.muyuchat.core.mediatek.MediaTekAotVaeTensorContract
import com.muyuchat.core.mediatek.MediaTekAotVaeTransport
import com.muyuchat.core.mediatek.MediaTekBundleAssetContract
import com.muyuchat.core.mediatek.MediaTekCompiledNetworkContract
import com.muyuchat.core.mediatek.MediaTekGraphTransport
import com.muyuchat.core.mediatek.MediaTekNpugContainerMode
import com.muyuchat.core.mediatek.MediaTekNpugInputEncodingContract
import com.muyuchat.core.mediatek.MediaTekNpugRouteContract
import com.muyuchat.core.mediatek.MediaTekNpugRounding
import com.muyuchat.core.mediatek.MediaTekNpugSigmaSchedule
import com.muyuchat.core.mediatek.MediaTekNpugTensorEncoding
import com.muyuchat.core.mediatek.MediaTekNpugTopologyContract
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekImageBundleManifestTest {
    @Test
    fun graphAndAssetPinsMustMatchTheUniqueRequiredEnvelopeComponent() {
        val component = LocalImageBundleComponentContract(
            role = "DIFFUSION",
            relativePath = "graphs/block-0.tflite",
            required = true,
            expectedSizeBytes = 1024L,
            sha256 = "a".repeat(64)
        )
        val index = mapOf(component.relativePath to listOf(component))

        requireMatchingMediaTekComponent(index, component.relativePath, 1024L, "a".repeat(64))

        assertTrue(runCatching {
            requireMatchingMediaTekComponent(index, component.relativePath, 1025L, "a".repeat(64))
        }.isFailure)
        assertTrue(runCatching {
            requireMatchingMediaTekComponent(index, component.relativePath, 1024L, "b".repeat(64))
        }.isFailure)
        assertTrue(runCatching {
            requireMatchingMediaTekComponent(index + (component.relativePath to listOf(component, component)),
                component.relativePath, 1024L, "a".repeat(64))
        }.isFailure)
    }

    @Test
    fun v4ComponentManifestIsDispatchedAndAllAssetsArePinned() {
        val fixture = v4Bundle()
        try {
            val manifest = fixture.requireParsed()
            assertTrue(manifest.mediatekAotManifest != null)
            assertTrue(manifest.mediatekAnimaManifest == null)
            assertTrue(manifest.mediatekAnimaRequiredPaths.containsAll(fixture.expectedRequiredPaths))
            assertTrue(manifest.mediatekAotManifest!!.executionBlockers().isEmpty())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun v4BundleReadinessRequiresACompleteChain() {
        val fixture = v4Bundle()
        try {
            val record = LocalImageModelRecord(
                displayName = "v4",
                path = fixture.stub.absolutePath,
                fileName = fixture.stub.name,
                sizeBytes = fixture.stub.length(),
                sha256 = observedLocalImageFileSha256(fixture.stub),
                runtime = LocalImageRuntime.MEDIATEK_NEURON,
                family = LocalImageModelFamily.ANIMA,
                bundleRoot = fixture.root.absolutePath,
            )
            val message = record.mediaTekAnimaBundleReadinessMessage()
            assertTrue(message.orEmpty(), message.orEmpty().contains("incomplete"))
            assertTrue(message.orEmpty(), message.orEmpty().contains("at least two execution variants"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun v4StubWithoutACompiledNetworkParsesButReadinessReportsItIncomplete() {
        val fixture = v4Bundle(compiledNetwork = false)
        try {
            val manifest = fixture.requireParsed()
            assertTrue(manifest.mediatekAotManifest!!.executionBlockers().isNotEmpty())
            val record = LocalImageModelRecord(
                displayName = "v4",
                path = fixture.stub.absolutePath,
                fileName = fixture.stub.name,
                sizeBytes = fixture.stub.length(),
                sha256 = observedLocalImageFileSha256(fixture.stub),
                runtime = LocalImageRuntime.MEDIATEK_NEURON,
                family = LocalImageModelFamily.ANIMA,
                bundleRoot = fixture.root.absolutePath,
            )
            val message = record.mediaTekAnimaBundleReadinessMessage()
            assertTrue(message.orEmpty(), message.orEmpty().contains("incomplete"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun v4RouteMustMatchTheManifestIdentity() {
        val fixture = v4Bundle(routeTransform = { it.replace("\"stub-graph\"", "\"other-graph\"") })
        try {
            val inspection = inspectLocalImageBundleManifestFromRoot(fixture.root)
            assertTrue(inspection is LocalImageBundleManifestInspection.Invalid)
            val message = (inspection as LocalImageBundleManifestInspection.Invalid).message
            assertTrue(message, message.contains("does not match its manifest"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun v4AssetsMustBePinnedByTheEnvelope() {
        val fixture = v4Bundle(omitComponent = "assets/npug/ctx.bin")
        try {
            val inspection = inspectLocalImageBundleManifestFromRoot(fixture.root)
            assertTrue(inspection is LocalImageBundleManifestInspection.Invalid)
            val message = (inspection as LocalImageBundleManifestInspection.Invalid).message
            assertTrue(message, message.contains("must have exactly one required MCA v1 component"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun unknownComponentSchemaIsRejected() {
        val fixture = v4Bundle(aotManifestText = { it.replace("mca.mediatek-image/4", "mca.mediatek-image/9") })
        try {
            val inspection = inspectLocalImageBundleManifestFromRoot(fixture.root)
            assertTrue(inspection is LocalImageBundleManifestInspection.Invalid)
            val message = (inspection as LocalImageBundleManifestInspection.Invalid).message
            assertTrue(message, message.contains("Unsupported MediaTek component manifest schema"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun v3ComponentManifestStillReachesTheV3Parser() {
        val fixture = v4Bundle(aotManifestText = { "{\"schema\":\"${MediaTekAotManifest.SCHEMA.replace("mediatek-image/4", "image_engine.bundle.v3")}\"}" })
        try {
            val inspection = inspectLocalImageBundleManifestFromRoot(fixture.root)
            assertTrue(inspection is LocalImageBundleManifestInspection.Invalid)
            val message = (inspection as LocalImageBundleManifestInspection.Invalid).message
            assertTrue(message, message.contains("manifest: missing fields"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun v4DynamicTextBundlePinsTextAssetsAndDoesNotRequireAFixedContext() {
        val fixture = v4Bundle(dynamicText = true)
        try {
            val manifest = fixture.requireParsed()
            val aot = requireNotNull(manifest.mediatekAotManifest)
            assertTrue(aot.conditioning.mode == MediaTekAotConditioningMode.DYNAMIC_TEXT)
            assertTrue(aot.conditioning.contextAsset == null)
            assertTrue(aot.conditioning.textAssets.size == 2)
            assertTrue(
                manifest.mediatekAnimaRequiredPaths.containsAll(
                    listOf("assets/text/text.bin", "assets/text/tok.bin"),
                ),
            )
            val record = LocalImageModelRecord(
                displayName = "v4-dynamic",
                path = fixture.stub.absolutePath,
                fileName = fixture.stub.name,
                sizeBytes = fixture.stub.length(),
                sha256 = observedLocalImageFileSha256(fixture.stub),
                runtime = LocalImageRuntime.MEDIATEK_NEURON,
                family = LocalImageModelFamily.ANIMA,
                bundleRoot = fixture.root.absolutePath,
            )
            val message = record.mediaTekAnimaBundleReadinessMessage()
            assertTrue(message.orEmpty(), !message.orEmpty().contains("ACTX context or text assets"))
            assertTrue(message.orEmpty(), message.orEmpty().contains("at least two execution variants"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun v4DynamicTextBundleRequiresTheTokenizerComponent() {
        val fixture = v4Bundle(dynamicText = true, omitComponent = "assets/text/tok.bin")
        try {
            val inspection = inspectLocalImageBundleManifestFromRoot(fixture.root)
            assertTrue(inspection is LocalImageBundleManifestInspection.Invalid)
            val message = (inspection as LocalImageBundleManifestInspection.Invalid).message
            assertTrue(message, message.contains("exactly one required MCA v1 component"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private data class V4Fixture(
        val root: File,
        val stub: File,
        val expectedRequiredPaths: List<String>,
    ) {
        fun requireParsed(): LocalImageBundleManifest =
            when (val inspection = inspectLocalImageBundleManifestFromRoot(root)) {
                is LocalImageBundleManifestInspection.Ready -> inspection.manifest
                is LocalImageBundleManifestInspection.Invalid -> throw AssertionError(inspection.message)
                LocalImageBundleManifestInspection.Undeclared -> throw AssertionError("bundle was not declared")
            }
    }

    private fun v4Bundle(
        compiledNetwork: Boolean = true,
        omitComponent: String? = null,
        routeTransform: (String) -> String = { it },
        aotManifestText: ((String) -> String)? = null,
        dynamicText: Boolean = false,
    ): V4Fixture {
        val root = Files.createTempDirectory("mca-v4-bundle").toFile()
        val files = linkedMapOf<String, File>()
        fun write(path: String, content: String): File {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            files[path] = file
            return file
        }

        val stub = write("assets/npug/stub.npug", "NPUG-stub")
        val dla = write("assets/npug/net.dla", "DLA-network")
        val ctx = write("assets/npug/ctx.bin", "ACTX-context")
        val mods = write("assets/npug/mods_6.bin", "AMOD-6")
        val rope = write("assets/npug/rope_32x32.bin", "ROPE")
        val vae = write("assets/npug/vaedec_64.dla", "DLA-vae")
        val textWeights = if (dynamicText) write("assets/text/text.bin", "ATXW-test-weights") else null
        val textTokenizer = if (dynamicText) write("assets/text/tok.bin", "ATOK-test-tokenizer") else null

        val routeJson = routeTransform(
            routeJson(
                stub = assetRef(stub, "aot/stub"),
                dla = if (compiledNetwork) assetRef(dla, "aot/mt6993/np9") else null,
            ),
        )
        // Rewrite the route after the transform so the envelope pin always matches the bytes on disk.
        write("assets/npug/route.json", routeJson)

        val stubAsset = asset("assets/npug/stub.npug", stub)
        val dlaAsset = asset("assets/npug/net.dla", dla)
        val manifest = MediaTekAotManifest(
            schema = MediaTekAotManifest.SCHEMA,
            family = MediaTekAotManifest.ANIMA_FAMILY,
            runtime = MediaTekAotManifest.RUNTIME,
            modelIdentity = MediaTekAotModelIdentity("ANIMA", "e".repeat(64)),
            variants = listOf(
                MediaTekAotVariant(
                    id = "npug-aot",
                    transport = MediaTekGraphTransport.NEURON_COMPILED_NETWORK,
                    topology = MediaTekNpugTopologyContract(
                        asset = stubAsset,
                        containerMagic = MediaTekNpugTopologyContract.CONTAINER_MAGIC,
                        mode = MediaTekNpugContainerMode.STUB_TOPOLOGY,
                        tensorCount = 2,
                        operationCount = 1,
                        weightDataBytes = 0L,
                    ),
                    compiledNetwork = if (compiledNetwork) {
                        MediaTekCompiledNetworkContract(dlaAsset, runtimeMajor = 9, graphId = "stub-graph")
                    } else null,
                    route = MediaTekNpugRouteContract(
                        asset = asset("assets/npug/route.json", files.getValue("assets/npug/route.json")),
                        graphId = "stub-graph",
                        inputCount = 2,
                        outputCount = 1,
                        inputEncoding = MediaTekNpugInputEncodingContract(
                            defaultEncoding = MediaTekNpugTensorEncoding.IEEE_HALF,
                            overrides = emptyMap(),
                            rounding = MediaTekNpugRounding.HALF_AWAY_FROM_ZERO,
                            saturates = true,
                        ),
                    ),
                ),
            ),
            conditioning = if (dynamicText) {
                MediaTekAotConditioningContract(
                    mode = MediaTekAotConditioningMode.DYNAMIC_TEXT,
                    promptEffective = true,
                    contextVersion = null,
                    contextAsset = null,
                    contextDtype = null,
                    contextShape = emptyList(),
                    textAssets = listOf(
                        asset("assets/text/text.bin", requireNotNull(textWeights)),
                        asset("assets/text/tok.bin", requireNotNull(textTokenizer)),
                    ),
                )
            } else {
                MediaTekAotConditioningContract(
                    mode = MediaTekAotConditioningMode.FIXED_CONTEXT,
                    promptEffective = false,
                    contextVersion = 1,
                    contextAsset = asset("assets/npug/ctx.bin", ctx),
                    contextDtype = "float32",
                    contextShape = listOf(256, 1024),
                    textAssets = emptyList(),
                )
            },
            parameters = MediaTekAotParameterContract(false, MediaTekAotCfgMode.NONE),
            profiles = listOf(
                MediaTekAotProfile(
                    id = "profile-1024",
                    widthPixels = 512,
                    heightPixels = 512,
                    gridWidth = 32,
                    gridHeight = 32,
                    tokenCount = 1024,
                    supportedSteps = setOf(6),
                    defaultSteps = 6,
                    defaultCfgScale = 1.0f,
                    minimumCfgScale = 1.0f,
                    maximumCfgScale = 1.0f,
                    sigmaSchedule = MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T,
                    modulations = listOf(
                        MediaTekAotModulationContract(
                            asset = asset("assets/npug/mods_6.bin", mods),
                            stepCount = 6,
                            blockCount = 28,
                            blockSegments = 9,
                            finalSegments = 2,
                        ),
                    ),
                    ropeAsset = asset("assets/npug/rope_32x32.bin", rope),
                    vae = MediaTekAotVaeContract(
                        transport = MediaTekAotVaeTransport.NEURON_COMPILED_NETWORK,
                        asset = asset("assets/npug/vaedec_64.dla", vae),
                        input = MediaTekAotVaeTensorContract(
                            dims = listOf(1, 64, 64, 16),
                            dtype = MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16,
                            layout = "NHWC",
                            scale = 1.0,
                            zeroPoint = 0,
                        ),
                        output = MediaTekAotVaeTensorContract(
                            dims = listOf(1, 512, 512, 3),
                            dtype = MediaTekNpugTensorEncoding.SCALED_SIGNED_INT16,
                            layout = "NHWC",
                            scale = 1.0,
                            zeroPoint = 0,
                        ),
                    ),
                ),
            ),
        )
        val encodedManifest = aotManifestText?.invoke(MediaTekAotManifestJson.encode(manifest))
            ?: MediaTekAotManifestJson.encode(manifest)
        write("engine/aot.json", encodedManifest)

        val components = JSONArray()
        files.forEach { (path, file) ->
            if (path != omitComponent) {
                components.put(component(if (path == "engine/aot.json") "CONFIG" else "MODEL", path, file))
            }
        }
        val envelope = JSONObject()
            .put("schema", "mca.image_engine.bundle.v1")
            .put("id", "test-v4")
            .put("title", "Test v4")
            .put("runtime", "MEDIATEK_NEURON")
            .put("family", "ANIMA")
            .put("task", "TEXT_TO_IMAGE")
            .put("imageSize", "512x512")
            .put("primary", "assets/npug/stub.npug")
            .put("mediatekAnimaManifestPath", "engine/aot.json")
            .put("components", components)
            .put(
                "smoke",
                JSONObject()
                    .put("width", 512)
                    .put("height", 512)
                    .put("steps", 6)
                    .put("timeoutSeconds", 120),
            )
        File(root, "manifest.json").writeText(envelope.toString(2), Charsets.UTF_8)

        return V4Fixture(
            root = root,
            stub = stub,
            expectedRequiredPaths = files.keys.filter { it != "engine/aot.json" && it != omitComponent },
        )
    }

    private fun component(role: String, path: String, file: File): JSONObject = JSONObject()
        .put("role", role)
        .put("path", path)
        .put("required", true)
        .put("expectedSizeBytes", file.length())
        .put("sha256", observedLocalImageFileSha256(file))

    private fun asset(path: String, file: File): MediaTekBundleAssetContract = MediaTekBundleAssetContract(
        file = path,
        sha256 = observedLocalImageFileSha256(file),
        sizeBytes = file.length(),
        mediaType = "application/octet-stream",
    )

    private fun assetRef(file: File, directory: String): JSONObject = JSONObject()
        .put("path", "$directory/${file.name}")
        .put("bytes", file.length())
        .put("sha256", observedLocalImageFileSha256(file))

    private fun routeJson(stub: JSONObject, dla: JSONObject?): String {
        fun tensor(index: Int, id: Int, field0: Int): JSONObject = JSONObject()
            .put("routeIndex", index)
            .put("tensorId", id)
            .put("rank", 2)
            .put("dims", JSONArray().put(1).put(4))
            .put("shapeKey", "[1,4]")
            .put("field0", field0)
            .put("field1", 1)
            .put("quantTag", 1)
            .put("quant", JSONObject().put("scale", 1.0).put("zeroPoint", 0))
            .put("dataOffset", 0)
            .put("dataSize", 0)
            .put("bytesIfFP16", 8)
            .put("bytesIfFP32", 16)

        return JSONObject()
            .put("graph", "stub-graph")
            .put("stub", stub)
            .put("dla", dla ?: JSONObject.NULL)
            .put("schemaFile", "schema.json")
            .put("schemaSHA256", "a".repeat(64))
            .put("tensorCount", 2)
            .put("opCount", 1)
            .put("inputCount", 2)
            .put("outputCount", 1)
            .put("inputs", JSONArray().put(tensor(0, 0, 0)).put(tensor(1, 1, 0)))
            .put("outputs", JSONArray().put(tensor(0, 2, 3)))
            .put(
                "inputShapeGroups",
                JSONArray().put(
                    JSONObject()
                        .put("shapeKey", "[1,4]")
                        .put("count", 2)
                        .put("routeIndexes", JSONArray().put(0).put(1))
                        .put("quantTags", JSONArray().put(1))
                        .put("distinctScales", JSONArray().put(1.0))
                        .put("note", JSONObject.NULL),
                ),
            )
            .put("checks", JSONObject().put("inputCountMatchesSchema", true))
            .put("checksAllPass", true)
            .toString(2)
    }
}

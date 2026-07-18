package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StableDiffusionComponentSelectionTest {
    @Test
    fun `installed manifest roles cannot override record primary path`() = withTempBundle { root ->
        val stale = root.touch("stale.gguf")
        root.touch("weights/diffusion.gguf")
        root.touch("components/autoencoder.bin")
        root.touch("components/prompt-model.bin")
        writeRoleManifest(
            root,
            family = LocalImageModelFamily.FLUX,
            components = listOf(
                component("DIFFUSION", "weights/diffusion.gguf"),
                component("VAE", "components/autoencoder.bin"),
                component("TEXT_ENCODER", "components/prompt-model.bin")
            )
        )

        val error = assertInvalid {
            resolveStableDiffusionComponentSelection(
                model(root, stale, LocalImageModelFamily.FLUX)
            )
        }

        assertTrue(error.message.orEmpty().contains("does not match"))
    }

    @Test
    fun `installed split bundle missing required role fails closed`() = withTempBundle { root ->
        val diffusion = root.touch("diffusion.gguf")
        root.touch("text.gguf")
        writeRoleManifest(
            root,
            family = LocalImageModelFamily.Z_IMAGE,
            components = listOf(
                component("DIFFUSION", "diffusion.gguf"),
                component("TEXT_ENCODER", "text.gguf")
            )
        )

        val error = assertInvalid {
            resolveStableDiffusionComponentSelection(
                model(root, diffusion, LocalImageModelFamily.Z_IMAGE)
            )
        }

        assertTrue(error.message.orEmpty().contains("VAE role"))
    }

    @Test
    fun `installed manifest path escape fails closed`() = withTempBundle { root ->
        val diffusion = root.touch("diffusion.gguf")
        writeRoleManifest(
            root,
            family = LocalImageModelFamily.SD_TURBO,
            components = listOf(component("DIFFUSION", "../outside.safetensors"))
        )

        val error = assertInvalid {
            resolveStableDiffusionComponentSelection(
                model(root, diffusion, LocalImageModelFamily.SD_TURBO)
            )
        }

        assertTrue(error.message.orEmpty().contains("escapes its bundle root"))
    }

    @Test
    fun `manual roleless manifest retains explicit compatibility fallback`() = withTempBundle { root ->
        val diffusion = root.touch("custom-flux.gguf")
        root.touch("custom-ae.safetensors")
        root.touch("custom-qwen.gguf")
        File(root, "manifest.json").writeText(
            JSONObject()
                .put("family", "FLUX")
                .put(
                    "components",
                    JSONArray().put(JSONObject().put("path", "custom-flux.gguf"))
                )
                .toString()
        )

        val selection = resolveStableDiffusionComponentSelection(
            model(root, diffusion, LocalImageModelFamily.FLUX)
        )

        assertEquals(STABLE_DIFFUSION_COMPONENT_MODE_COMPATIBILITY, selection.mode)
        assertTrue(selection.fallback)
        assertEquals(diffusion.canonicalPath, selection.primaryPath)
    }

    @Test
    fun `native manifest echo must preserve exact selected paths`() = withTempBundle { root ->
        val diffusion = root.touch("diffusion.gguf")
        val vae = root.touch("vae.safetensors")
        val text = root.touch("qwen.gguf")
        writeRoleManifest(
            root,
            family = LocalImageModelFamily.FLUX,
            components = listOf(
                component("DIFFUSION", diffusion.name),
                component("VAE", vae.name),
                component("TEXT_ENCODER", text.name)
            )
        )
        val selection = resolveStableDiffusionComponentSelection(
            model(root, diffusion, LocalImageModelFamily.FLUX)
        )
        val nativeResult = JSONObject()
            .put("componentSelection", selection.toAuditJson())

        val actual = selection.verifyNativeEcho(nativeResult)

        assertEquals(STABLE_DIFFUSION_COMPONENT_MODE_MANIFEST, actual.getString("mode"))
        assertEquals(vae.canonicalPath, actual.getString("vaePath"))
    }

    @Test
    fun `controlnet manifest role reaches native params and exact echo validation`() = withTempBundle { root ->
        val diffusion = root.touch("diffusion.safetensors")
        val controlNet = root.touch("control/controlnet.safetensors")
        writeRoleManifest(
            root,
            family = LocalImageModelFamily.SD15,
            components = listOf(
                component("DIFFUSION", "diffusion.safetensors"),
                component("CONTROLNET", "control/controlnet.safetensors")
            )
        )

        val selection = resolveStableDiffusionComponentSelection(
            model(root, diffusion, LocalImageModelFamily.SD15)
        )
        val params = selection.putIntoNativeParams(JSONObject())

        assertEquals(controlNet.canonicalPath, selection.controlNetPath)
        assertEquals(controlNet.canonicalPath, params.getString("componentControlNetPath"))
        val echoed = selection.verifyNativeEcho(
            JSONObject().put("componentSelection", selection.toAuditJson())
        )
        assertEquals(controlNet.canonicalPath, echoed.getString("controlNetPath"))
    }

    private fun model(
        root: File,
        primary: File,
        family: LocalImageModelFamily
    ): LocalImageModelRecord = LocalImageModelRecord(
        id = "test-model",
        displayName = "Test model",
        path = primary.absolutePath,
        fileName = primary.name,
        sizeBytes = primary.length(),
        sha256 = "test",
        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
        family = family,
        bundleRoot = root.absolutePath,
        componentCount = 3
    )

    private fun writeRoleManifest(
        root: File,
        family: LocalImageModelFamily,
        components: List<JSONObject>
    ) {
        File(root, "manifest.json").writeText(
            JSONObject()
                .put("schema", "mca.image_engine.bundle.v1")
                .put("family", family.name)
                .put("components", JSONArray(components))
                .toString(),
            Charsets.UTF_8
        )
    }

    private fun component(role: String, path: String): JSONObject = JSONObject()
        .put("role", role)
        .put("path", path)
        .put("required", true)

    private fun File.touch(relativePath: String): File = File(this, relativePath).apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun <T> withTempBundle(block: (File) -> T): T {
        val root = Files.createTempDirectory("sdcpp-components").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertInvalid(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        throw AssertionError("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}

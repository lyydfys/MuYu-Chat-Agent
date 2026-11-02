package com.muyuchat.mca.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the picker label derivation for the loaded local model.
 *
 * The loaded model identity is the MCA manifest UUID, which must never become the user-facing name.
 * The label comes from (in order) the manifest display name, a readable model path, a native
 * runtime name, a readable model id, and finally a generic fallback.
 */
class DshLocalModelDisplayNameTest {

    private val uuid = "4f6f1171-6f11-4f6f-9a0e-123456789abc"
    private val hash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun `manifest display name wins and keeps its curated wording`() {
        assertEquals(
            "Qwen3.5 0.8B MNN CPU",
            DshLocalModelDisplayName.resolve(
                manifestName = "Qwen3.5 0.8B MNN CPU",
                modelPath = "/data/user/0/com.muyuchat.mca/files/models/$hash",
                nativeModelName = null,
                modelId = uuid
            )
        )
    }

    @Test
    fun `manifest display name is normalized but not quality-stripped`() {
        assertEquals(
            "Qwen3.5 0.8B MNN CPU",
            DshLocalModelDisplayName.resolve(
                manifestName = "  Qwen3.5_0.8B  MNN   CPU ",
                modelPath = null,
                nativeModelName = null,
                modelId = uuid
            )
        )
        assertEquals("Qwen3.5 0.8B MNN", DshLocalModelDisplayName.readable("Qwen3.5 0.8B MNN"))
    }

    @Test
    fun `gguf path becomes a readable name without the quantization tag`() {
        assertEquals(
            "Qwen3.5 0.8B",
            DshLocalModelDisplayName.fromPath(
                "/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/Qwen3.5-0.8B-Q4_K_M.gguf"
            )
        )
        assertEquals(
            "Gemma 3 1B IT",
            DshLocalModelDisplayName.fromPath("/models/Gemma-3-1B-IT.litertlm")
        )
    }

    @Test
    fun `hash named MNN bundle uses the native name when available`() {
        assertEquals(
            "Qwen3.5 0.8B",
            DshLocalModelDisplayName.resolve(
                manifestName = null,
                modelPath = "/data/user/0/com.muyuchat.mca/files/models/$hash",
                nativeModelName = "Qwen3.5 0.8B",
                modelId = uuid
            )
        )
    }

    @Test
    fun `hash named bundle without a name falls back honestly instead of showing the uuid`() {
        val label = DshLocalModelDisplayName.resolve(
            manifestName = null,
            modelPath = "/data/user/0/com.muyuchat.mca/files/models/$hash",
            nativeModelName = null,
            modelId = uuid
        )
        assertEquals(DshLocalModelDisplayName.FALLBACK, label)
        assertFalse(label.contains(uuid.take(8)))
        assertFalse(label.contains("mca-local"))
    }

    @Test
    fun `opaque identities are never accepted as display names`() {
        assertNull(DshLocalModelDisplayName.readable(uuid))
        assertNull(DshLocalModelDisplayName.readable(hash))
        assertNull(DshLocalModelDisplayName.readable("123456789012"))
        assertNull(DshLocalModelDisplayName.readable("   "))
        assertTrue(DshLocalModelDisplayName.looksOpaque(uuid))
    }

    @Test
    fun `readable model id is the last readable resort before the generic fallback`() {
        assertEquals(
            "qwen local",
            DshLocalModelDisplayName.resolve(
                manifestName = null,
                modelPath = null,
                nativeModelName = null,
                modelId = "qwen-local"
            )
        )
    }

    @Test
    fun `path derived labels never leak the full path or package name`() {
        val label = DshLocalModelDisplayName.resolve(
            manifestName = null,
            modelPath = "/data/user/0/com.muyuchat.mca/files/models/Secret-Model.gguf",
            nativeModelName = null,
            modelId = uuid
        )
        assertEquals("Secret Model", label)
        assertFalse(label.contains("/"))
        assertFalse(label.contains("com.muyuchat"))
        assertFalse(label.contains("data"))
    }

    @Test
    fun `a readable nested bundle directory is accepted`() {
        val label = DshLocalModelDisplayName.fromPath(
            "/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/Qwen3.5-0.8B-MNN/$hash"
        )
        assertEquals("Qwen3.5 0.8B", label)
    }

    @Test
    fun `package-like or generic path segments are not names`() {
        assertNull(DshLocalModelDisplayName.fromPath("/data/user/0/com.muyuchat.mca/files/models/$hash"))
        assertNull(DshLocalModelDisplayName.fromPath("models"))
        assertNull(DshLocalModelDisplayName.fromPath("/storage/emulated/0/Android/data/com.muyuchat.mca"))
    }

    @Test
    fun `long labels are bounded`() {
        val label = DshLocalModelDisplayName.readable("q".repeat(200))
        assertTrue(label != null)
        assertTrue(label!!.length <= 120)
    }

    @Test
    fun `loaded-model projection supplies the name only for the matching model id`() {
        val json = """{"id":"$uuid","name":"Qwen3.5 0.8B MNN CPU","displayName":"Qwen3.5 0.8B MNN CPU"}"""
        assertEquals(
            "Qwen3.5 0.8B MNN CPU",
            DshLocalModelDisplayName.loadedModelNameFromJson(json, uuid)
        )
        assertNull(
            "a stale projection must not label another model",
            DshLocalModelDisplayName.loadedModelNameFromJson(json, "another-model-id")
        )
    }

    @Test
    fun `loaded-model projection falls back to the name key and rejects unusable documents`() {
        assertEquals(
            "Qwen3.5 0.8B",
            DshLocalModelDisplayName.loadedModelNameFromJson(
                """{"id":"$uuid","name":"Qwen3.5 0.8B"}""",
                uuid
            )
        )
        assertNull(DshLocalModelDisplayName.loadedModelNameFromJson("""{"id":"$uuid"}""", uuid))
        assertNull(DshLocalModelDisplayName.loadedModelNameFromJson("{}", uuid))
        assertNull(DshLocalModelDisplayName.loadedModelNameFromJson("not json", uuid))
        assertNull(DshLocalModelDisplayName.loadedModelNameFromJson(null, uuid))
        assertNull(
            DshLocalModelDisplayName.loadedModelNameFromJson(
                """{"id":"$uuid","displayName":"$hash"}""",
                uuid
            )
        )
    }

    @Test
    fun `exact current picker label drops embedded hash tokens and keeps curated words`() {
        assertEquals(
            "Qwen3.5 0.8B mnn",
            DshLocalModelDisplayName.readable(
                "Qwen3.5 0.8B mnn 47f681e8 2d00d9d839a3453dbd2d4f75e08b48de"
            )
        )
        assertEquals(
            "Qwen3.5 0.8B MNN CPU",
            DshLocalModelDisplayName.readable(
                "Qwen3.5 0.8B MNN CPU 47f681e8 2d00d9d839a3453dbd2d4f75e08b48de"
            )
        )
    }

    @Test
    fun `embedded uuid or uppercase hash is removed from anywhere in the label`() {
        assertEquals(
            "Qwen3.5 0.8B MNN CPU",
            DshLocalModelDisplayName.readable(
                "Qwen3.5 0.8B 4f6f1171-6f11-4f6f-9a0e-123456789abc MNN CPU"
            )
        )
        assertEquals(
            "Qwen3.5 0.8B MNN CPU",
            DshLocalModelDisplayName.readable(
                "Qwen3.5 0.8B 2D00D9D839A3453DBD2D4F75E08B48DE MNN CPU"
            )
        )
    }

    @Test
    fun `version-like decimal runs and curated tokens survive stripping`() {
        assertEquals(
            "Qwen3.5 0.8B MNN CPU 20240601 12345678 v1.2.3",
            DshLocalModelDisplayName.readable(
                "Qwen3.5 0.8B MNN CPU 20240601 12345678 v1.2.3"
            )
        )
    }

    @Test
    fun `hex stripping requires eight characters with a letter and word boundaries`() {
        assertEquals("release", DshLocalModelDisplayName.readable("release 47f681e8"))
        assertEquals("release 47f681e", DshLocalModelDisplayName.readable("release 47f681e"))
        assertEquals("release 12345678", DshLocalModelDisplayName.readable("release 12345678"))
        assertEquals("release47f681e8x", DshLocalModelDisplayName.readable("release47f681e8x"))
        assertEquals("model x", DshLocalModelDisplayName.readable("model_47f681e8_x"))
    }

    @Test
    fun `token stripping collapses whitespace and dangling separators`() {
        assertEquals(
            "Qwen3.5 0.8B MNN CPU",
            DshLocalModelDisplayName.readable(
                "  Qwen3.5   0.8B   2d00d9d839a3453dbd2d4f75e08b48de   MNN CPU   "
            )
        )
        assertEquals(
            "Qwen3.5 0.8B",
            DshLocalModelDisplayName.readable("Qwen3.5-0.8B--47f681e8-")
        )
    }

    @Test
    fun `identity-token component and paths cannot leak through the label`() {
        assertEquals(
            "mnn",
            DshLocalModelDisplayName.readable(
                "mnn_47f681e8_2d00d9d839a3453dbd2d4f75e08b48de"
            )
        )
        assertNull(
            DshLocalModelDisplayName.fromPath(
                "/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/" +
                    "mnn_47f681e8_2d00d9d839a3453dbd2d4f75e08b48de"
            )
        )
        val label = DshLocalModelDisplayName.resolve(
            manifestName = null,
            modelPath = "/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/" +
                "mnn_47f681e8_2d00d9d839a3453dbd2d4f75e08b48de",
            nativeModelName = null,
            modelId = uuid
        )
        assertEquals(DshLocalModelDisplayName.FALLBACK, label)
        assertFalse(label.contains("/"))
        assertFalse(label.contains("data"))
        assertFalse(label.contains("com.muyuchat"))
        assertFalse(label.contains("47f681e8"))
    }

    @Test
    fun `unix path-like manifest name is reduced to its readable basename`() {
        assertEquals(
            "Qwen3.5 0.8B",
            DshLocalModelDisplayName.resolve(
                manifestName = "/data/user/0/com.muyuchat.mca/files/models/Qwen3.5 0.8B",
                modelPath = null,
                nativeModelName = null,
                modelId = uuid
            )
        )
        // An opaque basename is rejected, so the next readable source is used instead of a path.
        assertEquals(
            "Qwen3.5 0.8B",
            DshLocalModelDisplayName.resolve(
                manifestName = "/data/user/0/com.muyuchat.mca/files/models/$hash",
                modelPath = null,
                nativeModelName = "Qwen3.5 0.8B",
                modelId = uuid
            )
        )
        assertNull(DshLocalModelDisplayName.readable("/tmp/$hash"))
    }

    @Test
    fun `windows path-like manifest name is reduced to its readable basename`() {
        assertEquals(
            "Qwen3.5 0.8B",
            DshLocalModelDisplayName.resolve(
                manifestName = "C:\\Users\\mca\\.cache\\models\\Qwen3.5 0.8B.gguf",
                modelPath = null,
                nativeModelName = null,
                modelId = uuid
            )
        )
        val label = DshLocalModelDisplayName.readable("D:\\models\\Qwen3.5 0.8B.gguf")
        assertEquals("Qwen3.5 0.8B", label)
        assertFalse(label!!.contains("/"))
        assertFalse(label.contains("\\"))
    }
}

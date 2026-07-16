package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTuningCapabilityDiscoveryTest {
    @Test
    fun renamedGgufUsesHeaderContextInsteadOfItsFileName() {
        val file = Files.createTempFile("renamed-external-model", ".bin").toFile()
        try {
            file.writeBytes(ggufWithContext("qwen35moe", 131_072))
            assertEquals(131_072, trustedModelMaxContextTokens(model(file), identity()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun declaredGgufContextIsWiredIntoLlamaTuningCapabilities() {
        val file = Files.createTempFile("declared-context-capability", ".gguf").toFile()
        try {
            file.writeBytes(ggufWithContext("qwen35moe", 131_072))
            val capabilities = discoverModelTuningCapabilities(
                model = model(file),
                identity = identity(),
                qairtAdmissionPassed = false
            )

            assertEquals(131_072, capabilities.maxContextTokens)
            assertTrue(capabilities.metadataReadable)
            assertTrue(capabilities.supportsBatchTuning)
        } finally {
            file.delete()
        }
    }

    @Test
    fun runtimeWithoutGgufMetadataDoesNotGuessAContextLimit() {
        val directory = Files.createTempDirectory("mnn-bundle").toFile()
        try {
            assertNull(
                trustedModelMaxContextTokens(
                    model(directory, ChatModelRuntime.MNN),
                    identity(LocalChatRuntime.MNN_CPU)
                )
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nonLlamaRuntimeNeverInheritsGgufOnlyLoadParameters() {
        val directory = Files.createTempDirectory("mnn-capability-boundary").toFile()
        try {
            val capabilities = discoverModelTuningCapabilities(
                model = model(directory, ChatModelRuntime.MNN),
                identity = identity(LocalChatRuntime.MNN_CPU),
                qairtAdmissionPassed = false
            )

            assertNull(capabilities.maxContextTokens)
            assertFalse(capabilities.supportsBatchTuning)
            assertFalse(capabilities.supportsQuantizedKv)
            assertFalse(capabilities.supportsSpeculativeMtp)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun model(file: File, runtime: ChatModelRuntime = ChatModelRuntime.LLAMA_CPP) = ModelManifest(
        id = "model",
        displayName = "renamed",
        path = file.absolutePath,
        runtime = runtime,
        source = ModelSource.LOCAL,
        fileName = file.name,
        sizeBytes = file.length(),
        sha256 = "sha",
        architecture = "qwen35moe"
    )

    private fun identity(runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP) = ModelRuntimeIdentity(
        modelId = "model",
        artifactFingerprint = "artifact",
        runtime = runtime
    )

    private fun ggufWithContext(architecture: String, contextLength: Int): ByteArray =
        ByteArrayOutputStream().apply {
            write("GGUF".toByteArray())
            writeU32(3)
            writeU64(0L)
            writeU64(2L)
            writeString("general.architecture")
            writeU32(8)
            writeString(architecture)
            writeString("$architecture.context_length")
            writeU32(4)
            writeU32(contextLength)
        }.toByteArray()

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        repeat(4) { shift -> write((value ushr (shift * 8)) and 0xff) }
    }

    private fun ByteArrayOutputStream.writeU64(value: Long) {
        repeat(8) { shift -> write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }
}

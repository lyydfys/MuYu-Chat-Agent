package com.muyuchat.mca

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionProfileRuntimeMaterializationTest {
    @Test
    fun relativeProjectorIsResolvedAgainstModelDirectory() {
        val root = Files.createTempDirectory("mca-projector-root").toFile()
        try {
            val model = root.resolve("model.gguf").apply { writeText("model") }
            val projector = root.resolve("mmproj-model.gguf").apply { writeText("projector") }
            assertEquals(
                projector.canonicalPath,
                resolveVisionProjectorPath(model.absolutePath, projector.name)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingProjectorIsPreservedForActionableNativeError() {
        val root = Files.createTempDirectory("mca-projector-missing").toFile()
        try {
            assertEquals(
                "mmproj-model.gguf",
                resolveVisionProjectorPath(root.resolve("model.gguf").absolutePath, "mmproj-model.gguf")
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

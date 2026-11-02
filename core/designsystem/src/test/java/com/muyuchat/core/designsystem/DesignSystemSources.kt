package com.muyuchat.core.designsystem

import java.io.File

/**
 * Locates a designsystem source file from a JVM unit test.
 *
 * Tests normally run with `user.dir` = the module directory, but the V9 verification scripts
 * may also run from the repository root. Walking up and checking both layouts keeps the
 * source-structure contracts independent of where the test task was launched.
 */
internal fun designSystemSource(fileName: String): String {
    val relative = "src/main/java/com/muyuchat/core/designsystem/$fileName"
    val workingDirectory = System.getProperty("user.dir") ?: "."
    var directory = File(workingDirectory).absoluteFile
    while (true) {
        val candidates = listOf(
            File(directory, relative),
            File(directory, "core/designsystem/$relative")
        )
        candidates.firstOrNull { it.isFile }?.let { return it.readText() }
        directory = directory.parentFile ?: break
    }
    throw AssertionError("$fileName was not found while walking up from $workingDirectory")
}

internal fun mcaRepositoryRoot(): File {
    val workingDirectory = System.getProperty("user.dir") ?: "."
    var directory = File(workingDirectory).absoluteFile
    while (true) {
        if (File(directory, "settings.gradle.kts").isFile && File(directory, "core/designsystem").isDirectory) {
            return directory
        }
        directory = directory.parentFile ?: break
    }
    throw AssertionError("MCA repository root was not found while walking up from $workingDirectory")
}

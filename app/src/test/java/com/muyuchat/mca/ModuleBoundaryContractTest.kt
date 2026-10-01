package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the compile-time layering the refactor depends on (plan section 5.1).
 *
 * P2-P6 move responsibilities between layers. The primary enforcement is the Gradle
 * dependency graph itself: a feature module cannot import the application module because
 * `:app` is not on its compile classpath. What that graph does not protect is its own
 * shape -- adding `implementation(project(":app"))` to a feature would quietly legalise
 * the coupling the plan forbids.
 *
 * So this test checks both halves:
 *   1. the declared Gradle edges (the real gate),
 *   2. the imports actually present (catches anything arriving through a transitive or
 *      newly added edge).
 *
 * Starting state: these boundaries already hold at the baseline, so this is a regression
 * gate rather than a debt ledger. No suppression list is needed.
 *
 * Every assertion is guarded against silently scanning nothing: missing build files and
 * empty source trees fail the test rather than reducing it to a no-op.
 */
class ModuleBoundaryContractTest {

    @Test
    fun `no feature module declares a dependency on the application or the local api`() {
        assertNoModuleDependencies(
            owner = "feature modules",
            moduleRoots = featureModuleRoots(),
            forbidden = listOf(":app" to "the application module", ":api:local" to "the Local API module")
        )
    }

    @Test
    fun `no feature module depends on another feature`() {
        val features = featureModuleRoots().map { it.substringAfter("feature/") }
        val violations = mutableListOf<String>()

        featureModuleRoots().forEach { root ->
            val owner = root.substringAfter("feature/")
            declaredModules(root).forEach { edge ->
                val other = features.firstOrNull { it != owner && edge == ":feature:$it" }
                if (other != null) violations += "$root depends on :feature:$other"
            }
        }

        assertEquals(
            "features must navigate through the app's navigation seam, not each other",
            emptyList<String>(),
            violations.sorted()
        )
    }

    @Test
    fun `the local api declares no dependency on a feature or the application`() {
        declaredModules("api/local").forEach { edge ->
            assertTrue(
                "api:local must not depend on $edge",
                edge != ":app" && !edge.startsWith(":feature:")
            )
        }
        assertTrue(
            "api/local/build.gradle.kts declared no project dependencies; the check would be vacuous",
            declaredModules("api/local").isNotEmpty()
        )
    }

    @Test
    fun `no core module declares a dependency on app feature or api`() {
        coreModuleRoots().forEach { root ->
            declaredModules(root).forEach { edge ->
                assertTrue(
                    "$root must not depend on $edge",
                    edge != ":app" && !edge.startsWith(":feature:") && !edge.startsWith(":api:")
                )
            }
        }
    }

    @Test
    fun `no core module pulls compose or room into a non-presentation layer`() {
        coreModuleRoots().forEach { root ->
            val buildFile = buildFile(root).readText()
            assertTrue("$root must not pull Compose in", !buildFile.contains("androidx.compose"))
            assertTrue("$root must not pull Room in", !buildFile.contains("androidx.room"))
        }
    }

    @Test
    fun `feature modules never import the application or the local api`() {
        assertNoImports(
            owner = "feature modules",
            directories = featureModuleRoots().map { "$it/src/main" },
            forbidden = listOf(
                "com.muyuchat.mca." to "the application module",
                "com.muyuchat.api.local." to "the Local API module"
            )
        )
    }

    @Test
    fun `the local api never imports a feature or the application`() {
        assertNoImports(
            owner = "api:local",
            directories = listOf("api/local/src/main"),
            forbidden = listOf(
                "com.muyuchat.feature." to "a feature module",
                "com.muyuchat.mca." to "the application module"
            )
        )
    }

    @Test
    fun `core modules import neither app feature api compose nor room`() {
        assertNoImports(
            owner = "core modules",
            directories = coreModuleRoots().map { "$it/src/main" },
            forbidden = listOf(
                "com.muyuchat.mca." to "the application module",
                "com.muyuchat.feature." to "a feature module",
                "com.muyuchat.api.local." to "the Local API module",
                "androidx.compose." to "Compose (a presentation concern)",
                "androidx.room." to "Room (a data concern)"
            )
        )
    }

    @Test
    fun `one feature does not import another feature's internals`() {
        val names = featureModuleRoots().map { it.substringAfter("feature/") }
        val violations = mutableListOf<String>()

        featureModuleRoots().forEach { root ->
            val owner = root.substringAfter("feature/")
            kotlinFilesIn("$root/src/main").forEach { file ->
                importLines(file).forEach { imported ->
                    names.filter { it != owner }.forEach { other ->
                        if (imported.startsWith("com.muyuchat.feature.$other.")) {
                            violations += "${file.relativeTo(projectRoot())} imports $imported"
                        }
                    }
                }
            }
        }

        assertEquals(
            "features must navigate through the app's navigation seam, not each other's internals",
            emptyList<String>(),
            violations.sorted()
        )
    }

    @Test
    fun `every covered module is actually read`() {
        // A silently empty or mis-resolved path would make the assertions above vacuous.
        // An earlier revision resolved "feature/x/src/main/../build.gradle.kts" to a path
        // that does not exist, so every dependency assertion passed while scanning nothing.
        // These guards are what caught it; keep them.
        val covered = featureModuleRoots() + coreModuleRoots() + listOf("api/local")
        covered.forEach { root ->
            assertTrue("$root/build.gradle.kts is missing", buildFile(root).isFile)
            assertTrue(
                "$root/src/main contains no Kotlin sources",
                kotlinFilesIn("$root/src/main").isNotEmpty()
            )
        }

        // Leaf modules legitimately declare no project dependencies, so the proof that the
        // parser works is that it finds edges somewhere, not that every module has one.
        val edges = covered.flatMap { declaredModules(it) }
        assertTrue(
            "no project dependency edge was parsed at all; the regex or the paths are wrong",
            edges.isNotEmpty()
        )
        assertTrue(
            "known edges such as :core:engine must be discovered",
            edges.contains(":core:engine")
        )
    }

    private fun assertNoModuleDependencies(
        owner: String,
        moduleRoots: List<String>,
        forbidden: List<Pair<String, String>>
    ) {
        val violations = mutableListOf<String>()
        moduleRoots.forEach { root ->
            declaredModules(root).forEach { edge ->
                forbidden.forEach { (target, description) ->
                    if (edge == target) violations += "$root/build.gradle.kts depends on $target ($description)"
                }
            }
        }

        assertEquals(
            "$owner must not declare these dependencies; violations found:",
            emptyList<String>(),
            violations.sorted()
        )
    }

    /** Project edges declared in a module's Gradle build file, e.g. `:core:engine`. */
    private fun declaredModules(moduleRoot: String): List<String> =
        Regex("""project\(\s*"(:[^"]+)"""")
            .findAll(buildFile(moduleRoot).readText())
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    private fun buildFile(moduleRoot: String): File = File(projectRoot(), "$moduleRoot/build.gradle.kts")

    private fun assertNoImports(
        owner: String,
        directories: List<String>,
        forbidden: List<Pair<String, String>>
    ) {
        val violations = mutableListOf<String>()
        directories.forEach { relative ->
            kotlinFilesIn(relative).forEach { file ->
                importLines(file).forEach { imported ->
                    forbidden.forEach { (prefix, description) ->
                        if (imported.startsWith(prefix)) {
                            violations += "${file.relativeTo(projectRoot())} imports $description ($imported)"
                        }
                    }
                }
            }
        }

        assertEquals(
            "$owner must not depend on these; violations found:",
            emptyList<String>(),
            violations.sorted()
        )
    }

    private fun importLines(file: File): List<String> =
        file.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }

    private fun kotlinFilesIn(relativeDirectory: String): List<File> =
        File(projectRoot(), relativeDirectory).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun featureModuleRoots(): List<String> =
        listOf("feature/chat", "feature/agent", "feature/modelhub", "feature/settings")

    private fun coreModuleRoots(): List<String> =
        listOf(
            "core/engine",
            "core/download",
            "core/modelstore",
            "core/deviceprofile",
            "core/advisor",
            "core/benchmark",
            "core/telemetry",
            "core/tuning"
        )

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }
}

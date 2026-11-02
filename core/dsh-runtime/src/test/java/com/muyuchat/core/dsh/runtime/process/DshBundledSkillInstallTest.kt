package com.muyuchat.core.dsh.runtime.process

import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D14 skills: the payload ships **zero** `SKILL.md` and MCA never set `DSH_BUNDLED_SKILL_DIR`, so
 * the official catalog was empty by construction. This fixture proves the smallest honest fix:
 *
 *  - the real APK asset (`core/dsh-runtime/src/main/assets/dsh-skills/`) installs into the
 *    MCA-managed `$DSH_HOME/bundled-skills`, with byte-identical content and valid official
 *    frontmatter;
 *  - `DshNodeCommand.environment` points `DSH_BUNDLED_SKILL_DIR` at exactly that managed root;
 *  - a second install is a no-op (no rewrite, no temp debris), and foreign files in the managed
 *    root are preserved;
 *  - unsafe asset paths are refused instead of escaping the root;
 *  - the installed skill resolves through the official provider's root rules: bundled rank 600,
 *    overridden by `$DSH_HOME/skills` (400) and project `.dsh/skills` (100).
 *
 * Scope: this is a JVM fixture of the install + root-rank contract. It does not start the official
 * Node kernel, so it proves composition/placement, never that a model consumed the skill. Device
 * discovery remains NOT_RUN.
 */
class DshBundledSkillInstallTest {

    @Test
    fun thePackagedSkillInstallsIntoTheManagedHomeWithValidFrontmatter() {
        val root = tempRoot("dsh-skills-install")
        try {
            val home = File(root, "dsh/home")
            val assets = packagedSkillsRoot()
            val shipped = DirectorySkillSource(assets).listFiles().size
            val installer = DshBundledSkillInstaller(DirectorySkillSource(assets))

            val first = installer.ensureInstalled(home)
            assertTrue("expected Installed, got $first", first is DshBundledSkillInstallResult.Installed)
            first as DshBundledSkillInstallResult.Installed
            assertEquals(shipped, first.written)
            assertEquals(0, first.unchanged)
            assertEquals(DshBundledSkillRoot.of(home), first.root)
            assertEquals(File(home, "bundled-skills").absolutePath, DshBundledSkillRoot.environmentValue(home))

            val installed = File(first.root, "mca-android-device/SKILL.md")
            assertTrue("the shipped SKILL.md must be materialised at $installed", installed.isFile)
            assertArrayEquals(
                File(assets, "mca-android-device/SKILL.md").readBytes(),
                installed.readBytes()
            )
            val frontmatter = parseSkillFrontmatter(installed.readText())
            assertNotNull("the shipped SKILL.md must carry official frontmatter", frontmatter)
            val parsed = frontmatter!!
            assertEquals("mca-android-device", parsed.name)
            assertTrue("description must be non-empty", parsed.description.isNotBlank())
            assertTrue("the body must carry instructions", parsed.body.isNotBlank())
            assertTrue("whenToUse must be a plain string", parsed.whenToUse?.isNotBlank() == true)

            // Independent root model of the official provider: the bundled root is the only root
            // that carries the skill and it resolves with BUNDLED_SKILL_RANK (600).
            val resolvedBundled = OfficialSkillRootRules.winner(home = home, cwd = File(root, "workspace"))
            assertNotNull(resolvedBundled)
            val bundledWinner = resolvedBundled!!
            assertEquals("mca-android-device", bundledWinner.name)
            assertEquals("bundled", bundledWinner.source)
            assertEquals(600, bundledWinner.rank)

            // Steady state: nothing rewritten and no .mca-tmp debris.
            val second = installer.ensureInstalled(home)
            assertTrue(second is DshBundledSkillInstallResult.Installed)
            second as DshBundledSkillInstallResult.Installed
            assertEquals(0, second.written)
            assertEquals(shipped, second.unchanged)
            assertNoTempFiles(first.root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun userAndProjectSkillRootsOverrideTheBundledSkillByOfficialRank() {
        val root = tempRoot("dsh-skills-ranks")
        try {
            val home = File(root, "dsh/home")
            val workspace = File(root, "workspace")
            assertTrue(DshBundledSkillInstaller(DirectorySkillSource(packagedSkillsRoot())).ensureInstalled(home)
                is DshBundledSkillInstallResult.Installed)

            // $DSH_HOME/skills (rank 400) beats the bundled root (rank 600).
            writeSkill(File(home, "skills/mca-android-device/SKILL.md"), "user override")
            val resolvedUser = OfficialSkillRootRules.winner(home, workspace)
            assertNotNull(resolvedUser)
            val userWinner = resolvedUser!!
            assertEquals("user-dsh", userWinner.source)
            assertEquals(400, userWinner.rank)
            assertEquals("user override", userWinner.description)

            // Project .dsh/skills (rank 100) beats every user/bundled root.
            writeSkill(File(workspace, ".dsh/skills/mca-android-device/SKILL.md"), "project override")
            val resolvedProject = OfficialSkillRootRules.winner(home, workspace)
            assertNotNull(resolvedProject)
            val projectWinner = resolvedProject!!
            assertEquals("project-dsh", projectWinner.source)
            assertEquals(100, projectWinner.rank)
            assertEquals("project override", projectWinner.description)

            // The bundled copy was never modified by an override.
            assertEquals(
                File(packagedSkillsRoot(), "mca-android-device/SKILL.md").readText(),
                File(home, "bundled-skills/mca-android-device/SKILL.md").readText()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unsafeAssetPathsAreRefusedInsteadOfEscapingTheManagedRoot() {
        val root = tempRoot("dsh-skills-unsafe")
        try {
            val home = File(root, "dsh/home")
            val outside = File(root, "outside.md")
            val unsafe = listOf(
                "skill/../../outside.md",
                "/absolute.md",
                "skill\\windows.md",
                "skill//double.md",
                "a/b/c/d/e.md"
            )
            for (path in unsafe) {
                val installer = DshBundledSkillInstaller(BrokenSource(listOf(path)))
                val result = installer.ensureInstalled(home)
                assertTrue("$path must be refused, got $result", result is DshBundledSkillInstallResult.Failed)
            }
            assertFalse("nothing may escape the managed root", outside.exists())
            assertFalse(File(home, "absolute.md").exists())
            assertFalse(File(home, "bundled-skills/skill/windows.md").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun foreignFilesInTheManagedRootSurviveAndNoTempArtifactRemains() {
        val root = tempRoot("dsh-skills-foreign")
        try {
            val home = File(root, "dsh/home")
            val installedRoot = DshBundledSkillRoot.of(home)
            assertTrue(installedRoot.mkdirs() || installedRoot.isDirectory)
            val foreign = File(installedRoot, "user-notes.md")
            foreign.writeText("a user file that MCA did not ship")

            val result = DshBundledSkillInstaller(DirectorySkillSource(packagedSkillsRoot()))
                .ensureInstalled(home)
            assertTrue(result is DshBundledSkillInstallResult.Installed)
            assertTrue("the installer must not delete foreign content", foreign.isFile)
            assertEquals("a user file that MCA did not ship", foreign.readText())
            assertNoTempFiles(installedRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anAbsentOrEmptyBundleIsTypedAndCreatesNothing() {
        val root = tempRoot("dsh-skills-absent")
        try {
            val home = File(root, "dsh/home")
            assertTrue(
                DshBundledSkillInstaller(BrokenSource(null)).ensureInstalled(home)
                    is DshBundledSkillInstallResult.Missing
            )
            assertTrue(
                DshBundledSkillInstaller(BrokenSource(emptyList())).ensureInstalled(home)
                    is DshBundledSkillInstallResult.Missing
            )
            assertFalse("an absent bundle must not create a root", DshBundledSkillRoot.of(home).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun assertNoTempFiles(root: File) {
        val temps = root.walkTopDown().filter { it.isFile && it.name.endsWith(".mca-tmp") }.toList()
        assertTrue("no temp artifact may remain: $temps", temps.isEmpty())
    }

    private fun writeSkill(path: File, description: String) {
        assertTrue(path.parentFile?.mkdirs() == true || path.parentFile?.isDirectory == true)
        path.writeText(
            """
            ---
            name: mca-android-device
            description: $description
            whenToUse: Only for this fixture.
            ---
            # Override

            Fixture body.
            """.trimIndent() + "\n"
        )
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertTrue("expected ${expected.size} bytes, got ${actual.size}", expected.contentEquals(actual))

    private fun packagedSkillsRoot(): File {
        val candidates = listOf(
            File("src/main/assets/dsh-skills"),
            File("core/dsh-runtime/src/main/assets/dsh-skills")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "cannot locate the packaged dsh-skills assets from " + File(".").absolutePath
            )
    }

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    /** Directory-backed source used with the real packaged assets. */
    private class DirectorySkillSource(private val root: File) : DshBundledSkillSource {
        override val description: String = "test directory ${root.absolutePath}"

        override fun listFiles(): List<String> = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

        override fun open(relativePath: String): InputStream = File(root, relativePath).inputStream()
    }

    /** A source whose entries are never opened: the installer must refuse before reading. */
    private class BrokenSource(private val files: List<String>?) : DshBundledSkillSource {
        override val description: String = "broken test source"

        override fun listFiles(): List<String>? = files

        override fun open(relativePath: String): InputStream? {
            throw AssertionError("an unsafe path must be refused before it is opened: $relativePath")
        }
    }

    /**
     * JVM model of the official `dsh-skill-filesystem` root ranks (its `lib/index.js` constants:
     * project `.dsh/skills` 100, project `.agents/skills` 200, custom 300, `$DSH_HOME/skills` 400,
     * `$AGENTS_HOME/skills` 500) and of `dsh-skill`'s `BUNDLED_SKILL_RANK = 600`. Duplicate names
     * are first-wins after sorting by rank, provider order and local order, exactly like
     * `dsh-skill`'s `compareIndexedCandidates`.
     */
    private object OfficialSkillRootRules {
        data class Root(val path: File, val source: String, val rank: Int)

        data class Candidate(
            val name: String,
            val description: String,
            val source: String,
            val rank: Int,
            val path: File
        )

        fun roots(home: File, cwd: File): List<Root> = listOf(
            Root(File(cwd, ".dsh/skills"), "project-dsh", 100),
            Root(File(cwd, ".agents/skills"), "project-agents", 200),
            Root(File(home, "skills"), "user-dsh", 400),
            Root(File(home, "agents/skills"), "user-agents", 500),
            Root(File(home, "bundled-skills"), "bundled", 600)
        )

        fun winner(home: File, cwd: File): Candidate? {
            val indexed = ArrayList<Triple<Int, Int, Candidate>>()
            roots(home, cwd).forEachIndexed { providerOrder, root ->
                val entries = root.path.listFiles()?.sortedBy { it.name } ?: return@forEachIndexed
                var localOrder = 0
                for (entry in entries) {
                    val file = when {
                        entry.isDirectory -> File(entry, "SKILL.md").takeIf { it.isFile }
                        entry.isFile && entry.name.endsWith(".md") -> entry
                        else -> null
                    } ?: continue
                    val parsed = parseSkillFrontmatter(file.readText()) ?: continue
                    indexed += Triple(
                        root.rank,
                        providerOrder * 1_000 + localOrder,
                        Candidate(parsed.name, parsed.description, root.source, root.rank, file)
                    )
                    localOrder += 1
                }
            }
            return indexed.sortedWith(compareBy({ it.first }, { it.second })).firstOrNull()?.third
        }
    }

}

private data class SkillFrontmatter(
    val name: String,
    val description: String,
    val whenToUse: String?,
    val body: String
)

/**
 * Minimal mirror of the official `parseFrontmatter`/`parseSkillFile` acceptance rules: opening
 * and closing `---` lines, a YAML mapping, non-empty string `name`/`description`, the official
 * kebab-case name grammar, and no legacy invocation keys. Returns null for a file the official
 * provider would ignore.
 */
private fun parseSkillFrontmatter(raw: String): SkillFrontmatter? {
    val lines = raw.split('\n')
    if (lines.isEmpty() || lines.first().removeSuffix("\r") != "---") return null
    var closing = -1
    for (index in 1 until lines.size) {
        if (lines[index].removeSuffix("\r") == "---") {
            closing = index
            break
        }
    }
    if (closing < 0) return null
    val data = LinkedHashMap<String, String>()
    for (index in 1 until closing) {
        val line = lines[index].removeSuffix("\r")
        if (line.isBlank()) continue
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim().removeSurrounding("\"")
        data[key] = value
    }
    val name = data["name"]?.takeIf { it.isNotEmpty() } ?: return null
    val description = data["description"]?.takeIf { it.isNotEmpty() } ?: return null
    if (!Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(name)) return null
    val legacy = data.keys.any {
        it == "disableModelInvocation" || it == "modelInvocable" || it == "userInvocable"
    }
    if (legacy) return null
    val body = lines.drop(closing + 1).joinToString("\n").trim()
    return SkillFrontmatter(
        name = name,
        description = description,
        whenToUse = data["whenToUse"],
        body = body
    )
}


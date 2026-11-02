package com.muyuchat.mca.agent.toolpack

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exact dependency lock of executable packages (PKG-08/PKG-17/PKG-19).
 *
 * Everything is JVM file-system behavior over the real store: real content-addressed directories,
 * real state records and the real lifecycle service. No network, no download, no device storage.
 */
class ToolpackDependencyLockTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val parser = ExecutableToolPackageManifestParser()

    private fun store(
        root: File = temporary.newFolder("store")
    ): ToolPackageInstallStore = ToolPackageInstallStore(root, clockMillis = { 1_000L })

    private fun bundle(
        packageId: String,
        packageVersion: String = "1.0.0",
        dependencies: List<JSONObject>? = null
    ): ExecutableToolPackage = ToolpackFixtures.bundle(
        ToolpackFixtures.manifestJson(
            packageId = packageId,
            packageVersion = packageVersion,
            dependencies = dependencies
        )
    )

    private fun lockOf(packageBundle: ExecutableToolPackage): JSONObject =
        ToolpackFixtures.dependencyJson(
            packageId = packageBundle.packageId,
            version = packageBundle.packageVersion,
            contentHash = packageBundle.contentHash
        )

    private suspend fun install(
        store: ToolPackageInstallStore,
        packageBundle: ExecutableToolPackage
    ): ToolPackageInstallResult =
        store.install(packageBundle.manifest.canonicalJson.toByteArray(), packageBundle.scripts)

    private fun installed(result: ToolPackageInstallResult): ToolPackageInstalledRecord {
        assertTrue("expected Installed, got $result", result is ToolPackageInstallResult.Installed)
        return (result as ToolPackageInstallResult.Installed).record
    }

    private fun rejected(result: ToolPackageInstallResult, code: String) {
        assertTrue("expected Rejected, got $result", result is ToolPackageInstallResult.Rejected)
        assertEquals(code, (result as ToolPackageInstallResult.Rejected).code)
    }

    @Test
    fun `installs a package whose exact dependency lock is provisioned`() = runBlocking {
        val root = temporary.newFolder("valid")
        val store = store(root)
        val dependency = bundle("dep-core")
        assertEquals(dependency.packageId, installed(install(store, dependency)).packageId)

        val parent = bundle("parent-tools", dependencies = listOf(lockOf(dependency)))
        val record = installed(install(store, parent))

        assertEquals(parent.contentHash, record.contentHash)
        assertTrue(File(File(root, "content"), dependency.contentHash).isDirectory)
        assertTrue(File(File(root, "content"), parent.contentHash).isDirectory)
        val verified = store.verify(parent.contentHash)
        assertTrue(verified is ToolPackageVerifyResult.Valid)
        val manifest = (verified as ToolPackageVerifyResult.Valid).bundle.manifest
        assertEquals(listOf("dep-core"), manifest.dependencies.map { it.packageId })
        assertEquals(
            listOf(dependency.contentHash),
            manifest.dependencies.map { it.contentHash }
        )
        assertTrue(store.enable(parent.contentHash) is ToolPackageActivationResult.Activated)
    }

    @Test
    fun `a missing dependency is rejected and keeps the previous active version`() = runBlocking {
        val root = temporary.newFolder("missing")
        val store = store(root)
        val active = bundle("parent-tools", "1.0.0")
        install(store, active)
        store.enable(active.contentHash)

        val lock = ToolpackFixtures.dependencyJson("dep-core", contentHash = "a".repeat(64))
        val rejectedBundle = bundle("parent-tools", "2.0.0", dependencies = listOf(lock))

        rejected(install(store, rejectedBundle), ToolPackageDependencyCodes.MISSING)

        val record = requireNotNull(store.record("parent-tools"))
        assertEquals(active.contentHash, record.contentHash)
        assertEquals("1.0.0", record.packageVersion)
        assertTrue(record.enabled)
        assertTrue(File(File(root, "content"), active.contentHash).isDirectory)
        assertFalse(File(File(root, "content"), rejectedBundle.contentHash).exists())
        assertEquals(1, store.scan().records.size)
        assertTrue(File(root, "journal").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a dependency whose content was removed is unprovisioned`() = runBlocking {
        val root = temporary.newFolder("unprovisioned")
        val store = store(root)
        val dependency = bundle("dep-core")
        install(store, dependency)
        File(File(root, "content"), dependency.contentHash).deleteRecursively()

        val parent = bundle("parent-tools", dependencies = listOf(lockOf(dependency)))

        rejected(install(store, parent), ToolPackageDependencyCodes.UNPROVISIONED)
        assertTrue(store.record("parent-tools") == null)
        assertFalse(File(File(root, "content"), parent.contentHash).exists())
    }

    @Test
    fun `a dependency lock hash or version mismatch is rejected`() = runBlocking {
        val root = temporary.newFolder("mismatch")
        val store = store(root)
        val dependency = bundle("dep-core")
        install(store, dependency)

        val wrongHash = ToolpackFixtures.dependencyJson(
            packageId = dependency.packageId,
            version = dependency.packageVersion,
            contentHash = "0".repeat(64)
        )
        rejected(
            install(store, bundle("parent-tools", dependencies = listOf(wrongHash))),
            ToolPackageDependencyCodes.MISMATCH
        )

        val wrongVersion = ToolpackFixtures.dependencyJson(
            packageId = dependency.packageId,
            version = "2.0.0",
            contentHash = dependency.contentHash
        )
        rejected(
            install(store, bundle("parent-tools", dependencies = listOf(wrongVersion))),
            ToolPackageDependencyCodes.MISMATCH
        )
        assertTrue(store.record("parent-tools") == null)
    }

    @Test
    fun `rejects invalid duplicate self oversized and unknown dependency declarations`() {
        val hash = "c".repeat(64)
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_SELF) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    dependencies = listOf(
                        ToolpackFixtures.dependencyJson(ToolpackFixtures.PACKAGE_ID, contentHash = hash)
                    )
                )
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_DUPLICATE) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    dependencies = listOf(
                        ToolpackFixtures.dependencyJson("dep-core", contentHash = hash),
                        ToolpackFixtures.dependencyJson("dep-core", contentHash = "d".repeat(64))
                    )
                )
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    dependencies = listOf(
                        ToolpackFixtures.dependencyJson("Bad Id", contentHash = hash)
                    )
                )
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    dependencies = listOf(
                        ToolpackFixtures.dependencyJson("dep-core", contentHash = "C".repeat(64))
                    )
                )
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    dependencies = listOf(
                        ToolpackFixtures.dependencyJson("dep-core", version = "", contentHash = hash)
                    )
                )
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_UNKNOWN_FIELD) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    dependencies = listOf(
                        ToolpackFixtures.dependencyJson("dep-core", contentHash = hash)
                            .put("registry", "https://example.invalid")
                    )
                )
            )
        }
        val tooMany = (0 until ToolPackageLimits.MAX_DEPENDENCIES + 1).map { index ->
            ToolpackFixtures.dependencyJson(
                "dep-" + index.toString().padStart(2, '0'),
                contentHash = hash
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_TOO_MANY) {
            parser.parse(ToolpackFixtures.manifestJson(dependencies = tooMany))
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID) {
            parser.parse(ToolpackFixtures.manifestJson(extraRoot = mapOf("dependencies" to "none")))
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DEPENDENCY_INVALID) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    extraRoot = mapOf("dependencies" to JSONArray().put("dep-core"))
                )
            )
        }
    }

    @Test
    fun `a dependency cycle reachable from an update is rejected`() = runBlocking {
        val root = temporary.newFolder("cycle")
        val service = ToolPackageLifecycleService(
            store = ToolPackageInstallStore(root, clockMillis = { 1_000L }),
            runtime = ToolPackageScriptRuntime(RhinoToolPackageScriptEngine(), RecordingHostHandler()),
            approval = ToolPackageHostApprovalPort.DENY_ALL
        )
        val first = bundle("pkg-a", "1.0.0")
        val second = bundle("pkg-b", "1.0.0")
        applied(service.install(first.manifest.canonicalJson.toByteArray(), first.scripts))
        applied(service.install(second.manifest.canonicalJson.toByteArray(), second.scripts))

        val nextA = bundle("pkg-a", "2.0.0", dependencies = listOf(lockOf(second)))
        applied(service.update(nextA.manifest.canonicalJson.toByteArray(), nextA.scripts))
        applied(service.enable("pkg-b"))

        val nextB = bundle("pkg-b", "2.0.0", dependencies = listOf(lockOf(nextA)))
        val outcome = service.update(nextB.manifest.canonicalJson.toByteArray(), nextB.scripts)

        assertTrue("expected Rejected, got $outcome", outcome is ToolPackageLifecycleOutcome.Rejected)
        assertEquals(
            ToolPackageDependencyCodes.CYCLE,
            (outcome as ToolPackageLifecycleOutcome.Rejected).code
        )
        val status = requireNotNull(service.status("pkg-b"))
        assertEquals(second.contentHash, status.contentHash)
        assertTrue(status.enabled)
        assertTrue(File(File(root, "content"), second.contentHash).isDirectory)
        assertFalse(File(File(root, "content"), nextB.contentHash).exists())
    }

    @Test
    fun `gc retains dependency content while a parent is installed and frees it after removal`() =
        runBlocking {
            val root = temporary.newFolder("gc")
            val store = store(root)
            val dependency = bundle("dep-core")
            val firstParent = bundle("parent-a", dependencies = listOf(lockOf(dependency)))
            val secondParent = bundle("parent-b", dependencies = listOf(lockOf(dependency)))
            install(store, dependency)
            install(store, firstParent)
            install(store, secondParent)

            // The dependency is no longer independently installed: only the parent locks keep it.
            assertTrue(
                store.apply(ToolPackageStateChange.Remove(dependency.packageId))
                    is ToolPackageStateChangeResult.Applied
            )

            val retained = store.collectGarbage()
            assertTrue(dependency.contentHash in retained.keptContentHashes)
            assertTrue(File(File(root, "content"), dependency.contentHash).isDirectory)

            assertTrue(
                store.apply(ToolPackageStateChange.Remove(firstParent.packageId))
                    is ToolPackageStateChangeResult.Applied
            )
            val shared = store.collectGarbage()
            assertTrue(shared.keptContentHashes.contains(dependency.contentHash))
            assertTrue(File(File(root, "content"), dependency.contentHash).isDirectory)

            assertTrue(
                store.apply(ToolPackageStateChange.Remove(secondParent.packageId))
                    is ToolPackageStateChangeResult.Applied
            )
            val released = store.collectGarbage()
            assertTrue(released.deletedContentHashes.contains(dependency.contentHash))
            assertFalse(File(File(root, "content"), dependency.contentHash).exists())
        }

    @Test
    fun `gc retains a transitive dependency chain until the top parent is removed`() = runBlocking {
        val root = temporary.newFolder("gc-transitive")
        val store = store(root)
        val base = bundle("dep-base")
        val middle = bundle("dep-middle", dependencies = listOf(lockOf(base)))
        val top = bundle("parent-top", dependencies = listOf(lockOf(middle)))
        install(store, base)
        install(store, middle)
        install(store, top)
        assertTrue(
            store.apply(ToolPackageStateChange.Remove(base.packageId))
                is ToolPackageStateChangeResult.Applied
        )
        assertTrue(
            store.apply(ToolPackageStateChange.Remove(middle.packageId))
                is ToolPackageStateChangeResult.Applied
        )

        val retained = store.collectGarbage()
        assertTrue(retained.keptContentHashes.containsAll(listOf(base.contentHash, middle.contentHash)))
        assertTrue(File(File(root, "content"), base.contentHash).isDirectory)
        assertTrue(File(File(root, "content"), middle.contentHash).isDirectory)

        assertTrue(
            store.apply(ToolPackageStateChange.Remove(top.packageId))
                is ToolPackageStateChangeResult.Applied
        )
        val released = store.collectGarbage()
        assertTrue(
            released.deletedContentHashes.containsAll(listOf(base.contentHash, middle.contentHash))
        )
    }

    @Test
    fun `gc keeps dependency content of the rollback target version`() = runBlocking {
        val root = temporary.newFolder("gc-previous")
        val store = store(root)
        val base = bundle("dep-base")
        val firstParent = bundle("parent-tools", "1.0.0", dependencies = listOf(lockOf(base)))
        val secondParent = bundle("parent-tools", "2.0.0")
        install(store, base)
        install(store, firstParent)
        install(store, secondParent)
        assertEquals(
            firstParent.contentHash,
            requireNotNull(store.record("parent-tools")).previousContentHash
        )
        assertTrue(
            store.apply(ToolPackageStateChange.Remove(base.packageId))
                is ToolPackageStateChangeResult.Applied
        )

        val retained = store.collectGarbage()
        assertTrue(base.contentHash in retained.keptContentHashes)
        assertTrue(File(File(root, "content"), base.contentHash).isDirectory)

        // Replacing the rollback target with a version that has no lock releases both old contents.
        install(store, bundle("parent-tools", "3.0.0"))
        val released = store.collectGarbage()
        assertTrue(
            released.deletedContentHashes.containsAll(
                listOf(base.contentHash, firstParent.contentHash)
            )
        )
    }

    @Test
    fun `a manifest without dependencies keeps its canonical hash and installs`() = runBlocking {
        val withoutField = parser.parse(ToolpackFixtures.manifestJson())
        val emptyArray = parser.parse(ToolpackFixtures.manifestJson(dependencies = emptyList()))

        assertEquals(withoutField.canonicalJson, emptyArray.canonicalJson)
        assertEquals(withoutField.manifestSha256, emptyArray.manifestSha256)
        assertTrue(withoutField.dependencies.isEmpty())
        assertFalse(withoutField.canonicalJson.contains("dependencies"))

        val locked = parser.parse(
            ToolpackFixtures.manifestJson(
                dependencies = listOf(
                    ToolpackFixtures.dependencyJson("dep-core", contentHash = "e".repeat(64))
                )
            )
        )
        assertTrue(locked.canonicalJson.contains("\"dependencies\""))
        assertNotEquals(withoutField.manifestSha256, locked.manifestSha256)
        assertEquals(1, locked.dependencies.size)

        val root = temporary.newFolder("legacy")
        val store = store(root)
        val legacyBundle = ExecutableToolPackage.load(withoutField, ToolpackFixtures.scripts())
        val result = install(store, legacyBundle)
        assertEquals(withoutField.packageId, installed(result).packageId)
        assertEquals(withoutField.packageId, requireNotNull(store.record(withoutField.packageId)).packageId)
    }

    @Test
    fun `a legacy installed record without dependency data still loads`() = runBlocking {
        val root = temporary.newFolder("legacy-state")
        val hash = "b".repeat(64)
        val stateDir = File(root, "state")
        assertTrue(stateDir.mkdirs())
        File(stateDir, "legacy-pkg.json").writeText(
            """{"formatVersion":1,"packageId":"legacy-pkg","packageVersion":"1.0.0",""" +
                """"contentHash":"$hash","enabled":false,"installedAtMillis":5,"revision":1}"""
        )

        val record = requireNotNull(store(root).record("legacy-pkg"))

        assertEquals(hash, record.contentHash)
        assertEquals("1.0.0", record.packageVersion)
        assertFalse(record.enabled)
    }

    @Test
    fun `a transitive dependency removed after its parent installs blocks a new parent`() = runBlocking {
        val root = temporary.newFolder("transitive-missing")
        val store = store(root)
        val leaf = bundle("dep-leaf")
        val middle = bundle("dep-middle", dependencies = listOf(lockOf(leaf)))
        installed(install(store, leaf))
        installed(install(store, middle))

        val removal = store.apply(ToolPackageStateChange.Remove("dep-leaf"))
        assertTrue("removal must apply, got $removal", removal is ToolPackageStateChangeResult.Applied)
        store.collectGarbage()

        val parent = bundle("parent-tools", dependencies = listOf(lockOf(middle)))
        rejected(install(store, parent), ToolPackageDependencyCodes.MISSING)
        assertEquals(null, store.record("parent-tools"))
        assertEquals(middle.contentHash, store.record("dep-middle")!!.contentHash)
    }

    private fun applied(outcome: ToolPackageLifecycleOutcome): ToolPackageLifecycleOutcome.Applied {
        assertTrue("expected Applied, got $outcome", outcome is ToolPackageLifecycleOutcome.Applied)
        return outcome as ToolPackageLifecycleOutcome.Applied
    }
}

package com.muyuchat.mca.agent.toolpack

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolver-level evidence for the exact dependency closure.
 *
 * The store integration tests cover install/update and GC; these tests drive the resolver directly
 * with already-constructed bundles so the closure walk (diamond reuse, conflicting pins, missing
 * transitive node) is proven without relying on a particular store state.
 */
class ToolPackageDependencyResolverTest {

    private fun bundle(
        packageId: String,
        packageVersion: String = "1.0.0",
        dependencies: List<ExecutableToolPackage> = emptyList()
    ): ExecutableToolPackage = ToolpackFixtures.bundle(
        ToolpackFixtures.manifestJson(
            packageId = packageId,
            packageVersion = packageVersion,
            dependencies = dependencies.map { dependency ->
                ToolpackFixtures.dependencyJson(
                    packageId = dependency.packageId,
                    version = dependency.packageVersion,
                    contentHash = dependency.contentHash
                )
            }
        )
    )

    private fun record(bundle: ExecutableToolPackage): ToolPackageInstalledRecord =
        ToolPackageInstalledRecord(
            packageId = bundle.packageId,
            packageVersion = bundle.packageVersion,
            contentHash = bundle.contentHash,
            enabled = true,
            installedAtMillis = 1L,
            revision = 1L
        )

    private fun resolver(
        installed: List<ExecutableToolPackage>
    ): ToolPackageDependencyResolver {
        val records = installed.associateBy { it.packageId }
        val bundles = installed.associateBy { it.contentHash }
        return ToolPackageDependencyResolver(
            recordOf = { packageId -> records[packageId]?.let(::record) },
            verifyContent = { contentHash ->
                bundles[contentHash]?.let { bundle ->
                    ToolPackageVerifyResult.Valid(bundle, record(bundle))
                } ?: ToolPackageVerifyResult.Rejected(
                    "TOOLPACK_CONTENT_MISSING",
                    "fixture: content absent"
                )
            }
        )
    }

    @Test
    fun `a diamond reuses one verified dependency`() = runBlocking {
        val shared = bundle("dep-shared")
        val left = bundle("dep-left", dependencies = listOf(shared))
        val right = bundle("dep-right", dependencies = listOf(shared))
        val incoming = bundle("parent-tools", dependencies = listOf(left, right))

        val result = resolver(listOf(shared, left, right)).resolve(incoming.manifest)

        assertEquals(ToolPackageDependencyResolution.Satisfied, result)
    }

    @Test
    fun `two parents pinning different content for the same id are rejected`() = runBlocking {
        val first = bundle("dep-shared", packageVersion = "1.0.0")
        val second = bundle("dep-shared", packageVersion = "2.0.0")
        val left = bundle("dep-left", dependencies = listOf(first))
        val right = bundle("dep-right", dependencies = listOf(second))
        val incoming = bundle("parent-tools", dependencies = listOf(left, right))

        val result = resolver(listOf(first, left, right)).resolve(incoming.manifest)

        assertTrue(result is ToolPackageDependencyResolution.Rejected)
        assertEquals(
            ToolPackageDependencyCodes.MISMATCH,
            (result as ToolPackageDependencyResolution.Rejected).code
        )
    }

    @Test
    fun `a missing transitive node blocks the closure`() = runBlocking {
        val leaf = bundle("dep-leaf")
        val middle = bundle("dep-middle", dependencies = listOf(leaf))
        val incoming = bundle("parent-tools", dependencies = listOf(middle))

        val result = resolver(listOf(middle)).resolve(incoming.manifest)

        assertTrue(result is ToolPackageDependencyResolution.Rejected)
        assertEquals(
            ToolPackageDependencyCodes.MISSING,
            (result as ToolPackageDependencyResolution.Rejected).code
        )
    }

    @Test
    fun `an exact transitive lock that matches is satisfied`() = runBlocking {
        val leaf = bundle("dep-leaf")
        val middle = bundle("dep-middle", dependencies = listOf(leaf))
        val incoming = bundle("parent-tools", dependencies = listOf(middle))

        val result = resolver(listOf(leaf, middle)).resolve(incoming.manifest)

        assertEquals(ToolPackageDependencyResolution.Satisfied, result)
    }
}

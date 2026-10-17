package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single-owner rule for local image generation.
 *
 * The product requirement is that the UI and the authenticated Local API do not each get
 * their own execution path: one coordinator instance serializes both, a lease is bound to
 * one request and one epoch, and a stale completion cannot release a newer request.
 *
 * The behavioural half covers the coordinator directly. The contract half asserts that the
 * production wiring keeps a single instance and that the API path reports contention
 * instead of silently racing the UI.
 */
class LocalImageGenerationOwnershipContractTest {

    @Test
    fun `a second request is refused while a lease is active`() {
        val coordinator = LocalImageGenerationCoordinator()

        val first = requireNotNull(coordinator.tryAcquire("ui-request"))
        assertNull("a second caller must not receive a lease", coordinator.tryAcquire("api-request"))
        assertEquals("ui-request", coordinator.activeRequestId())

        assertTrue(coordinator.release(first))
        assertNull(coordinator.activeRequestId())
    }

    @Test
    fun `a stale lease cannot release a newer request`() {
        val coordinator = LocalImageGenerationCoordinator()

        val stale = requireNotNull(coordinator.tryAcquire("request-1"))
        assertTrue(coordinator.release(stale))

        val current = requireNotNull(coordinator.tryAcquire("request-2"))
        assertFalse(
            "releasing the retired lease must not free the request that replaced it",
            coordinator.release(stale)
        )
        assertEquals("request-2", coordinator.activeRequestId())
        assertTrue(coordinator.release(current))
    }

    @Test
    fun `every lease carries a strictly increasing epoch`() {
        val coordinator = LocalImageGenerationCoordinator()

        val epochs = (1..5).map { index ->
            val lease = requireNotNull(coordinator.tryAcquire("request-$index"))
            coordinator.release(lease)
            lease.epoch
        }

        assertEquals("epochs must be unique", epochs.size, epochs.toSet().size)
        assertEquals("epochs must be ordered", epochs.sorted(), epochs)
        assertTrue("epochs must advance", epochs.first() < epochs.last())
    }

    @Test
    fun `re-entrant leases are distinguishable even for the same request id`() {
        val coordinator = LocalImageGenerationCoordinator()

        val first = requireNotNull(coordinator.tryAcquire("same-id"))
        coordinator.release(first)
        val second = requireNotNull(coordinator.tryAcquire("same-id"))

        assertNotEquals(
            "identity, not the request id, decides whether a release applies",
            first.epoch,
            second.epoch
        )
        assertFalse(coordinator.release(first))
        assertEquals("same-id", coordinator.activeRequestId())
    }

    @Test
    fun `a blank request id is rejected before any lease is taken`() {
        val coordinator = LocalImageGenerationCoordinator()

        val failure = runCatching { coordinator.tryAcquire("   ") }.exceptionOrNull()
        assertTrue(
            "a blank request identity must fail loudly, not silently serialize",
            failure is IllegalArgumentException
        )
        assertNull(coordinator.activeRequestId())
    }

    @Test
    fun `exactly one coordinator instance exists in production source`() {
        val constructions = Regex("LocalImageGenerationCoordinator\\(\\)")
            .findAll(productionSources())
            .count()

        assertEquals(
            "a second coordinator instance would let the UI and the API run concurrently",
            1,
            constructions
        )
    }

    @Test
    fun `the UI and the Local API acquire the same owner lease`() {
        val mainViewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val owner = sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt")
        val operations = sourceFile(
            "app/src/main/java/com/muyuchat/mca/presentation/LocalApiImageGenerationOperations.kt"
        )

        assertTrue(
            "the UI admission must acquire the shared lease through the owner host",
            owner.contains("host.tryAcquireObservedImageGenerationLease(jobId)")
        )
        assertTrue(
            "the Local API owner gate must use the same lease helper",
            owner.contains("tryAcquireLease = host::tryAcquireObservedImageGenerationLease")
        )
        assertTrue(
            "the Local API operation must enter the owner",
            operations.contains("port.runLocalApiImageOperation(requestId)")
        )
        assertFalse(
            "the protocol operation must not acquire a lease directly",
            operations.contains("tryAcquireObservedImageGenerationLease")
        )
        assertTrue(
            "contention must surface as a typed busy error with a 409 status",
            operations.contains("code = \"image_generation_busy\"") &&
                operations.contains("httpStatus = 409")
        )
        assertFalse(
            "MainViewModel must not retain a second Local API ownership registry",
            mainViewModel.contains("activeLocalApiImageGenerationOwnership")
        )
        assertFalse(
            "MainViewModel must not register or cancel Local API worker ownership",
            mainViewModel.contains("registerLocalApiImageGenerationOwnership") ||
                mainViewModel.contains("cancelOwnedLocalApiImageWorker")
        )
    }

    @Test
    fun `every lease acquisition has a matching release on the same lease`() {
        // R3/C3-S15: the two lease helpers moved verbatim to ImageGenerationOwner.kt (the ViewModel
        // keeps same-name delegates); the assertions keep their strength -- the positive halves scan
        // both sources, and the no-clearout regex guards both the ViewModel and the helper file.
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val leaseSource = sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt")

        assertTrue(
            "the acquisition helper must be the only constructor of observed leases",
            viewModel.contains("localImageGenerationCoordinator.tryAcquire(requestId)") ||
                leaseSource.contains("coordinator.tryAcquire(requestId)")
        )
        assertTrue(
            "releases must go through the identity-checked coordinator call",
            viewModel.contains("localImageGenerationCoordinator.release(lease)") ||
                leaseSource.contains("coordinator.release(lease)")
        )
        assertFalse(
            "no caller may clear the active lease without the identity check",
            Regex("localImageGenerationCoordinator\\s*\\.\\s*(?!release|tryAcquire|activeRequestId)\\w+")
                .containsMatchIn(viewModel) ||
                Regex("\\bcoordinator\\s*\\.\\s*(?!release|tryAcquire|activeRequestId)\\w+")
                    .containsMatchIn(leaseSource)
        )
    }

    @Test
    fun `the Local API bridge is a provider the app installs, not a second runtime`() {
        val runtime = sourceFile("api/local/src/main/java/com/muyuchat/api/local/LocalApiRuntime.kt")

        assertTrue(
            "the API must delegate image generation to an app-owned bridge",
            runtime.contains("var imageGenerationProvider")
        )
        assertTrue(
            "the bridge signature must carry the request id so ownership stays bound",
            runtime.contains("suspend (requestId: String, body: String) -> String")
        )
    }

    private fun productionSources(): String {
        val roots = listOf("app/src/main", "api/local/src/main", "core", "feature")
        return roots.joinToString("\n") { root ->
            File(projectRoot(), root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}build${File.separator}") }
                .joinToString("\n") { it.readText() }
        }
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private fun sourceFile(relativePath: String): String =
        File(projectRoot(), relativePath).readText()
}

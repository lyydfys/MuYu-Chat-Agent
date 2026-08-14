package com.muyuchat.core.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test

class PersistentPrefixCacheStoreTest {
    @Test
    fun androidFrameworkUserAliasRecognitionIsNarrow() {
        assertTrue(isFrameworkManagedAndroidUserAlias(Paths.get("/data/user/0")))
        assertTrue(isFrameworkManagedAndroidUserAlias(Paths.get("/data/user_de/10")))
        assertFalse(isFrameworkManagedAndroidUserAlias(Paths.get("/data/user/not-a-user")))
        assertFalse(isFrameworkManagedAndroidUserAlias(Paths.get("/data/user/0/com.example.app")))
        assertFalse(isFrameworkManagedAndroidUserAlias(Paths.get("/tmp/data/user/0")))
    }

    @Test
    fun keyUsesOnlyStrictLowercaseSha256ComponentsAndChangesWithEveryBinding() {
        val key = key("first")

        assertTrue(PrefixCacheKey.isSha256Hex(key.cacheId))
        assertFalse(PrefixCacheKey.isSha256Hex(key.modelFingerprint.uppercase()))
        assertTrue(key.cacheId != key("second").cacheId)

        try {
            PrefixCacheKey(
                modelFingerprint = key.modelFingerprint.uppercase(),
                tokenizerFingerprint = key.tokenizerFingerprint,
                templateFingerprint = key.templateFingerprint,
                systemPromptFingerprint = key.systemPromptFingerprint,
                runtimeFingerprint = key.runtimeFingerprint,
                prefixFingerprint = key.prefixFingerprint
            )
            fail("Uppercase model fingerprint must be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected: persisted metadata has one canonical digest spelling.
        }
    }

    @Test
    fun savePublishesVerifiedStateAndMetadataWithoutPromptText() = withRoot { root ->
        val prompt = "do-not-persist-this-system-prompt"
        val key = key("save", prompt)
        val store = PersistentPrefixCacheStore(root, maxBytes = 16_384L, clock = { 10L })
        val state = "serialized-llama-state".toByteArray()

        val entry = requireNotNull(store.save(key, state))
        val metadata = File(root, "${key.cacheId}.meta")

        assertArrayEquals(state, entry.stateFile.readBytes())
        assertEquals(PrefixCacheKey.sha256(state), entry.stateSha256)
        assertTrue(metadata.isFile)
        assertTrue(metadata.readText().contains("format_version=1"))
        assertFalse(metadata.readText().contains(prompt))
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertEquals(entry, store.load(key))
    }

    @Test
    fun nativeStagingCommitPublishesStateAndDiscardsTheStagingPath() = withRoot { root ->
        val store = PersistentPrefixCacheStore(root, maxBytes = 16_384L, clock = { 100L })
        val key = key("native")
        val state = ByteArray(1_024) { index -> (index and 0xff).toByte() }

        val pending = requireNotNull(store.prepareWrite(key))
        assertNull("Only one native writer can stage at a time.", store.prepareWrite(key("other")))
        pending.stateFile.writeBytes(state)

        val entry = requireNotNull(store.commit(pending))
        assertFalse(pending.stateFile.exists())
        assertArrayEquals(state, entry.stateFile.readBytes())
        assertEquals(entry, store.load(key))
    }

    @Test
    fun nativeStagingCommitSyncsStateBeforePublishingIt() = withRoot { root ->
        val events = mutableListOf<String>()
        val key = key("native-sync")
        val store = PersistentPrefixCacheStore.createForTest(
            rootDirectory = root,
            maxBytes = 16_384L,
            clock = { 100L },
            stateFileSyncer = PrefixCacheStateFileSyncer { staging ->
                assertTrue(staging.isFile)
                assertFalse(File(root, "${key.cacheId}.meta").exists())
                events += "sync"
            }
        )
        val pending = requireNotNull(store.prepareWrite(key))
        pending.stateFile.writeText("native state")

        val entry = requireNotNull(store.commit(pending))

        assertEquals(listOf("sync"), events)
        assertTrue(entry.stateFile.isFile)
        assertFalse(pending.stateFile.exists())
    }

    @Test
    fun nativeStagingSyncFailureDoesNotPublishStateOrMetadata() = withRoot { root ->
        val key = key("native-sync-failure")
        val store = PersistentPrefixCacheStore.createForTest(
            rootDirectory = root,
            maxBytes = 16_384L,
            clock = { 100L },
            stateFileSyncer = PrefixCacheStateFileSyncer {
                throw IOException("fsync failed")
            }
        )
        val pending = requireNotNull(store.prepareWrite(key))
        pending.stateFile.writeText("native state")

        assertNull(store.commit(pending))
        assertFalse(pending.stateFile.exists())
        assertFalse(File(root, "${key.cacheId}.meta").exists())
        assertTrue(store.entries().isEmpty())
        assertNotNull(
            "A failed commit must release the process lock.",
            PersistentPrefixCacheStore(root, maxBytes = 16_384L)
                .save(key("after-sync-failure"), "replacement state".toByteArray())
        )
    }

    @Test
    fun corruptStateOrTraversalMetadataIsRejectedWithoutTouchingOutsideFiles() = withRoot { root ->
        val store = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val key = key("corrupt")
        val state = "valid state".toByteArray()
        val first = requireNotNull(store.save(key, state))

        first.stateFile.writeBytes("tampered".toByteArray())
        assertNull(store.load(key))

        val restored = requireNotNull(store.save(key, state))
        val metadata = File(root, "${key.cacheId}.meta")
        val outside = File(root.parentFile, "prefix-cache-outside-${System.nanoTime()}.txt")
        outside.writeText("outside survives")
        metadata.writeText(
            metadata.readText().replace(
                "state_file=${restored.stateFile.name}",
                "state_file=../${outside.name}"
            )
        )

        assertNull(store.load(key))
        assertTrue(outside.isFile)
        assertEquals("outside survives", outside.readText())
        outside.delete()
    }

    @Test
    fun metadataVersionAndDigestSpellingAreStrict() = withRoot { root ->
        val store = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val key = key("metadata-strict")
        requireNotNull(store.save(key, "state".toByteArray()))
        val metadata = File(root, "${key.cacheId}.meta")
        val original = metadata.readText()

        metadata.writeText(original.replace("format_version=1", "format_version=2"))
        assertNull(store.load(key))

        val restored = requireNotNull(store.save(key, "state".toByteArray()))
        val restoredMetadata = File(root, "${key.cacheId}.meta")
        restoredMetadata.writeText(
            restoredMetadata.readText().replace(
                "state_sha256=${restored.stateSha256}",
                "state_sha256=${restored.stateSha256.uppercase()}"
            )
        )
        assertNull(store.load(key))
    }

    @Test
    fun quotaEvictsLeastRecentlyUsedEntryAndClearLeavesUnmanagedFilesAlone() = withRoot { root ->
        var now = 0L
        val quotaBytes = 4_500L
        val store = PersistentPrefixCacheStore(root, maxBytes = quotaBytes, clock = { now })
        val first = key("first")
        val second = key("second")
        val third = key("third")

        assertNotNull(store.save(first, ByteArray(1_000) { 1 }))
        now = 10L
        assertNotNull(store.save(second, ByteArray(1_000) { 2 }))
        now = 20L
        assertNotNull(store.load(first))
        now = 30L
        assertNotNull(store.save(third, ByteArray(1_000) { 3 }))

        assertNotNull(store.load(first))
        assertNull(store.load(second))
        assertNotNull(store.load(third))
        assertTrue(managedBytes(root) <= quotaBytes)

        val unmanaged = File(root, "keep-me.txt").apply { writeText("not a cache artifact") }
        assertTrue(store.clear())
        assertTrue(unmanaged.isFile)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun summaryReportsVerifiedStateOnlyAndReturnsToZeroAfterClear() = withRoot { root ->
        val store = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val first = ByteArray(512) { 1 }
        val second = ByteArray(1_024) { 2 }

        assertEquals(PersistentPrefixCacheSummary(entryCount = 0, totalBytes = 0L), store.summary())
        assertNotNull(store.save(key("summary-first"), first))
        assertNotNull(store.save(key("summary-second"), second))
        File(root, "unmanaged.bin").writeBytes(ByteArray(4_096) { 3 })

        assertEquals(
            PersistentPrefixCacheSummary(entryCount = 2, totalBytes = first.size.toLong() + second.size),
            store.summary()
        )
        assertTrue(store.clear())
        assertEquals(PersistentPrefixCacheSummary(entryCount = 0, totalBytes = 0L), store.summary())
    }

    @Test
    fun symbolicLinkStateIsRejectedWhenTheFilesystemSupportsLinks() = withRoot { root ->
        val store = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val key = key("symlink")
        val entry = requireNotNull(store.save(key, "state".toByteArray()))
        val outside = File(root.parentFile, "prefix-cache-link-target-${System.nanoTime()}.bin")
        outside.writeText("outside")
        assertTrue(entry.stateFile.delete())
        try {
            Files.createSymbolicLink(entry.stateFile.toPath(), outside.toPath())
        } catch (error: Exception) {
            outside.delete()
            assumeNoException(error)
        }

        assertNull(store.load(key))
        assertTrue(outside.isFile)
        outside.delete()
    }

    @Test
    fun independentStoreCannotScanAwayAnotherStorePendingTemp() = withRoot { root ->
        val firstStore = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val secondStore = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val key = key("cross-process-pending")
        val pending = requireNotNull(firstStore.prepareWrite(key))
        pending.stateFile.writeText("pending native state")

        // The first store holds the OS lock for the entire native export. A
        // second process must report cache misses and leave the temp alone.
        assertTrue(pending.stateFile.isFile)
        assertTrue(secondStore.entries().isEmpty())
        assertNull(secondStore.prepareWrite(key("second-writer")))
        assertFalse(secondStore.clear())
        assertTrue(pending.stateFile.isFile)

        val committed = requireNotNull(firstStore.commit(pending))
        assertFalse(pending.stateFile.exists())
        assertNotNull(secondStore.load(key))
        assertTrue(committed.stateFile.isFile)
    }

    @Test
    fun lockReleaseAllowsIndependentStoreToReadWriteAndClearLeavesLockFile() = withRoot { root ->
        val firstStore = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val secondStore = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        val firstKey = key("cross-process-release-first")
        val secondKey = key("cross-process-release-second")

        assertNotNull(firstStore.save(firstKey, "first state".toByteArray()))
        val pending = requireNotNull(firstStore.prepareWrite(key("held")))
        assertNull(secondStore.save(secondKey, "blocked while held".toByteArray()))
        assertTrue(firstStore.discard(pending))

        assertNotNull(secondStore.load(firstKey))
        assertNotNull(secondStore.save(secondKey, "second state".toByteArray()))
        assertNotNull(firstStore.load(secondKey))

        val lockFile = File(root, ".prefix-cache.lock")
        assertTrue("The fixed lock file is retained outside managed cache cleanup.", lockFile.isFile)
        assertTrue(firstStore.clear())
        assertTrue(lockFile.isFile)
    }

    @Test
    fun symbolicLinkLockIsRejectedWithoutFollowingTheTarget() = withRoot { root ->
        val target = File(root.parentFile, "prefix-cache-lock-target-${System.nanoTime()}.bin")
        target.writeText("outside")
        val lockFile = File(root, ".prefix-cache.lock")
        try {
            Files.createSymbolicLink(lockFile.toPath(), target.toPath())
        } catch (error: Exception) {
            target.delete()
            assumeNoException(error)
        }

        val store = PersistentPrefixCacheStore(root, maxBytes = 16_384L)
        assertNull(store.save(key("symlink-lock"), "state".toByteArray()))
        assertTrue(target.isFile)
        assertEquals("outside", target.readText())
        target.delete()
    }

    private fun key(seed: String, systemPrompt: String = "system-$seed"): PrefixCacheKey = PrefixCacheKey(
        modelFingerprint = digest("model-$seed"),
        tokenizerFingerprint = digest("tokenizer-$seed"),
        templateFingerprint = digest("template-$seed"),
        systemPromptFingerprint = digest(systemPrompt),
        runtimeFingerprint = digest("runtime-$seed"),
        prefixFingerprint = digest("prefix-$seed")
    )

    private fun digest(value: String): String = PrefixCacheKey.sha256Utf8(value)

    private fun managedBytes(root: File): Long = root.listFiles().orEmpty()
        .filter { file -> file.name.endsWith(".meta") || file.name.endsWith(".state") }
        .sumOf(File::length)

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("persistent-prefix-cache-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

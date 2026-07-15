package com.muyuchat.core.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject

class ModelBundleInstallerTest {
    @Test
    fun plansNestedComponentsWithoutFlatteningOrTempNameCollisions() {
        val tempDir = Files.createTempDirectory("mca-bundle-plan-test").toFile()
        try {
            val bundleRoot = File(tempDir, "sana")
            val components = listOf(
                remote("config.json"),
                remote("llm/config.json"),
                remote("llm.mnn"),
                remote("llm/llm.mnn")
            )

            val plan = ModelBundleInstaller().plan(bundleRoot, components)

            assertEquals(
                listOf("config.json", "llm/config.json", "llm.mnn", "llm/llm.mnn"),
                plan.targets.map { it.relativePath }
            )
            assertEquals(
                listOf(
                    File(bundleRoot, "config.json").canonicalFile,
                    File(bundleRoot, "llm/config.json").canonicalFile,
                    File(bundleRoot, "llm.mnn").canonicalFile,
                    File(bundleRoot, "llm/llm.mnn").canonicalFile
                ),
                plan.targets.map { it.finalFile }
            )
            assertEquals(4, plan.targets.map { it.finalFile }.toSet().size)
            assertEquals(4, plan.targets.map { it.tempFile }.toSet().size)
            assertEquals(
                File(plan.partsRoot, "config.json.part").canonicalFile,
                plan.targets[0].tempFile
            )
            assertEquals(
                File(plan.partsRoot, "llm/config.json.part").canonicalFile,
                plan.targets[1].tempFile
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsAbsoluteTraversalAndEscapingRelativePaths() {
        val tempDir = Files.createTempDirectory("mca-bundle-path-test").toFile()
        try {
            val bundleRoot = File(tempDir, "bundle")
            val invalidPaths = listOf(
                "/absolute/config.json",
                "C:\\absolute\\config.json",
                "\\\\server\\share\\config.json",
                "../config.json",
                "llm/../../config.json",
                "llm/./config.json"
            )

            invalidPaths.forEach { relativePath ->
                val error = runCatching {
                    ModelBundleInstaller().plan(bundleRoot, listOf(remote(relativePath)))
                }.exceptionOrNull()
                assertTrue("Expected rejection for $relativePath", error is IllegalArgumentException)
            }
            assertFalse(File(tempDir, "config.json").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateAndFileDirectoryConflicts() {
        val tempDir = Files.createTempDirectory("mca-bundle-conflict-test").toFile()
        try {
            val bundleRoot = File(tempDir, "bundle")
            val conflicts = listOf(
                listOf(remote("llm/config.json"), remote("llm\\config.json")),
                listOf(remote("llm/config.json"), remote("LLM/CONFIG.JSON")),
                listOf(remote("llm"), remote("llm/config.json"))
            )

            conflicts.forEach { components ->
                val error = runCatching {
                    ModelBundleInstaller().plan(bundleRoot, components)
                }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun failedInstallKeepsOldBundleAndRetryReusesPartialWorkspace() = runBlocking {
        val tempDir = Files.createTempDirectory("mca-bundle-recovery-test").toFile()
        try {
            val bundleRoot = File(tempDir, "sana").apply { mkdirs() }
            File(bundleRoot, "old-marker.txt").writeText("old")
            val components = listOf(
                remote("config.json", payload = "root-config"),
                remote("llm/llm.mnn", payload = "nested-model")
            )
            var failNestedDownload = true
            var resumedNestedDownload = false
            val installer = ModelBundleInstaller(
                BundleComponentDownloader { remote, tempFile, finalFile, onProgress ->
                    tempFile.parentFile?.mkdirs()
                    finalFile.parentFile?.mkdirs()
                    if (remote.relativePath == "llm/llm.mnn" && failNestedDownload) {
                        tempFile.writeText("partial")
                        failNestedDownload = false
                        throw IOException("simulated connection failure")
                    }
                    if (remote.relativePath == "llm/llm.mnn" && tempFile.exists()) {
                        resumedNestedDownload = true
                    }
                    val payload = remote.downloadUrl.substringAfterLast('/')
                    tempFile.writeText(payload)
                    if (finalFile.exists()) finalFile.delete()
                    check(tempFile.renameTo(finalFile))
                    snapshot(remote, tempFile, finalFile).also(onProgress)
                }
            )
            val plan = installer.plan(bundleRoot, components)

            val firstError = runCatching {
                installer.install(bundleRoot, components)
            }.exceptionOrNull()

            assertTrue(firstError is IOException)
            assertEquals("old", File(bundleRoot, "old-marker.txt").readText())
            assertFalse(File(bundleRoot, "config.json").exists())
            assertTrue(File(plan.contentRoot, "config.json").isFile)
            assertEquals("partial", File(plan.partsRoot, "llm/llm.mnn.part").readText())

            val result = installer.install(bundleRoot, components)

            assertTrue(resumedNestedDownload)
            assertEquals("root-config", File(bundleRoot, "config.json").readText())
            assertEquals("nested-model", File(bundleRoot, "llm/llm.mnn").readText())
            assertFalse(File(bundleRoot, "old-marker.txt").exists())
            assertEquals(
                listOf("config.json", "llm/llm.mnn"),
                result.files.map { it.relativePath }
            )
            assertFalse(plan.workRoot.exists())
            assertFalse(plan.backupRoot.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installRecordsObservedDigestWithoutPretendingUnknownSourceMetadataIsVerified() = runBlocking {
        val tempDir = Files.createTempDirectory("mca-bundle-audit-test").toFile()
        try {
            val bundleRoot = File(tempDir, "bundle")
            val installer = ModelBundleInstaller(fakeDownloader())

            val result = installer.install(
                bundleRoot,
                listOf(remote("unet.mnn", payload = "model-data").copy(sizeBytes = null))
            )

            val auditFile = requireNotNull(result.auditManifest)
            val component = JSONObject(auditFile.readText()).getJSONArray("components").getJSONObject(0)
            assertEquals(ModelBundleInstaller.AUDIT_SCHEMA, JSONObject(auditFile.readText()).getString("schema"))
            assertEquals("UNKNOWN", component.getString("sourceMetadataStatus"))
            assertEquals(sha256("model-data"), component.getString("observedSha256"))
            assertTrue(installer.verifyInstalledBundle(bundleRoot).isVerified)
            assertEquals(
                ModelBundleComponentVerificationStatus.MATCHED_OBSERVED_DIGEST,
                installer.verifyInstalledBundle(bundleRoot).components.single().status
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun textOnlyTransformerDerivesConfigsAndAuditsOnlyObservedDigests() = runBlocking {
        val tempDir = Files.createTempDirectory("mca-text-only-transform-test").toFile()
        try {
            val bundleRoot = File(tempDir, "gemma")
            val payloads = linkedMapOf(
                "config.json" to """{"is_visual":true,"is_audio":true,"keep":"root"}""",
                "llm_config.json" to """{"is_visual":true,"is_audio":true,"model_type":"gemma4"}""",
                "llm.mnn" to "model",
                "llm.mnn.weight" to "weights",
                "llm.mnn.json" to "metadata",
                "tokenizer.mtok" to "tokenizer",
                "ple_embeddings_int4.bin" to "ple"
            )
            val components = payloads.map { (path, payload) ->
                remote(path, payload).copy(sha256 = sha256(payload))
            }
            val installer = ModelBundleInstaller(
                BundleComponentDownloader { remote, tempFile, finalFile, onProgress ->
                    tempFile.parentFile?.mkdirs()
                    finalFile.parentFile?.mkdirs()
                    finalFile.writeText(payloads.getValue(remote.relativePath))
                    snapshot(remote, tempFile, finalFile).also(onProgress)
                }
            )

            val result = installer.install(
                bundleRoot = bundleRoot,
                components = components,
                stagedTransformer = MnnModelBundleInstallProfile.TEXT_ONLY.stagedTransformer()
            )

            val rootConfig = JSONObject(File(bundleRoot, "config.json").readText())
            val llmConfig = JSONObject(File(bundleRoot, "llm_config.json").readText())
            assertFalse(rootConfig.getBoolean("is_visual"))
            assertFalse(rootConfig.getBoolean("is_audio"))
            assertEquals("root", rootConfig.getString("keep"))
            assertFalse(llmConfig.getBoolean("is_visual"))
            assertFalse(llmConfig.getBoolean("is_audio"))
            assertEquals("gemma4", llmConfig.getString("model_type"))

            val audits = result.files.associate { it.relativePath to requireNotNull(it.audit) }
            listOf("config.json", "llm_config.json").forEach { path ->
                val audit = audits.getValue(path)
                assertTrue(audit.transformed)
                assertNull(audit.sourceSizeBytes)
                assertNull(audit.sourceSha256)
                assertEquals(ImageEngineIntegrityMetadataStatus.UNKNOWN, audit.sourceMetadataStatus)
            }
            val modelAudit = audits.getValue("llm.mnn")
            assertFalse(modelAudit.transformed)
            assertEquals(sha256("model"), modelAudit.sourceSha256)
            assertEquals(
                ModelBundleComponentVerificationStatus.MATCHED_OBSERVED_DIGEST,
                installer.verifyInstalledBundle(bundleRoot).components
                    .first { it.audit.relativePath == "config.json" }
                    .status
            )
            assertEquals(
                ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256,
                installer.verifyInstalledBundle(bundleRoot).components
                    .first { it.audit.relativePath == "llm.mnn" }
                    .status
            )

            val auditJson = JSONObject(requireNotNull(result.auditManifest).readText())
                .getJSONArray("components")
            val derivedConfigAudit = (0 until auditJson.length())
                .map(auditJson::getJSONObject)
                .first { it.getString("path") == "config.json" }
            assertTrue(derivedConfigAudit.getBoolean("transformed"))
            assertTrue(derivedConfigAudit.isNull("sourceSha256"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun textOnlyTransformerFailureDoesNotReplaceExistingBundle() = runBlocking {
        val tempDir = Files.createTempDirectory("mca-text-only-transform-failure-test").toFile()
        try {
            val bundleRoot = File(tempDir, "gemma").apply { mkdirs() }
            File(bundleRoot, "old-marker.txt").writeText("old")
            val payloads = mapOf(
                "config.json" to "not-json",
                "llm_config.json" to """{"is_visual":true,"is_audio":true}"""
            )
            val components = payloads.map { (path, payload) -> remote(path, payload) }
            val installer = ModelBundleInstaller(
                BundleComponentDownloader { remote, tempFile, finalFile, onProgress ->
                    finalFile.parentFile?.mkdirs()
                    finalFile.writeText(payloads.getValue(remote.relativePath))
                    snapshot(remote, tempFile, finalFile).also(onProgress)
                }
            )

            val error = runCatching {
                installer.install(
                    bundleRoot = bundleRoot,
                    components = components,
                    stagedTransformer = MnnModelBundleInstallProfile.TEXT_ONLY.stagedTransformer()
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertEquals("old", File(bundleRoot, "old-marker.txt").readText())
            assertFalse(File(bundleRoot, "config.json").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verificationDetectsDownloadedComponentTampering() = runBlocking {
        val tempDir = Files.createTempDirectory("mca-bundle-tamper-test").toFile()
        try {
            val bundleRoot = File(tempDir, "bundle")
            val installer = ModelBundleInstaller(fakeDownloader())
            installer.install(bundleRoot, listOf(remote("unet.mnn", payload = "model-data")))
            File(bundleRoot, "unet.mnn").writeText("changed-data")

            val verification = installer.verifyInstalledBundle(bundleRoot)

            assertFalse(verification.isVerified)
            assertEquals(
                ModelBundleComponentVerificationStatus.SIZE_MISMATCH,
                verification.components.single().status
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun fakeDownloader(): BundleComponentDownloader = BundleComponentDownloader { remote, tempFile, finalFile, onProgress ->
        tempFile.parentFile?.mkdirs()
        finalFile.parentFile?.mkdirs()
        tempFile.writeText(remote.downloadUrl.substringAfterLast('/'))
        check(tempFile.renameTo(finalFile))
        snapshot(remote, tempFile, finalFile).also(onProgress)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun remote(relativePath: String, payload: String = relativePath): RemoteModelFile = RemoteModelFile(
        repoId = "owner/model",
        revision = "main",
        path = relativePath,
        name = relativePath.replace('\\', '/').substringAfterLast('/'),
        sizeBytes = payload.toByteArray().size.toLong(),
        downloadUrl = "https://example.com/$payload",
        relativePath = relativePath
    )

    private fun snapshot(
        remote: RemoteModelFile,
        tempFile: File,
        finalFile: File
    ): DownloadTaskSnapshot = DownloadTaskSnapshot(
        repoId = remote.repoId,
        revision = remote.revision,
        fileName = remote.name,
        url = remote.downloadUrl,
        expectedLength = finalFile.length(),
        downloadedBytes = finalFile.length(),
        tempFile = tempFile,
        finalFile = finalFile,
        status = DownloadStatus.DONE
    )
}

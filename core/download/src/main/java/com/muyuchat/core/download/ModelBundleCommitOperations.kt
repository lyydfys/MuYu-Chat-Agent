package com.muyuchat.core.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject

    internal fun commit(plan: ModelBundleInstallPlan) {
        require(plan.contentRoot.isDirectory) { "Bundle staging content is missing: ${plan.contentRoot}" }
        if (plan.backupRoot.exists() && !plan.backupRoot.deleteRecursively()) {
            throw IOException("Unable to remove stale bundle backup: ${plan.backupRoot}")
        }

        val hadExistingBundle = plan.bundleRoot.exists()
        if (hadExistingBundle && !plan.bundleRoot.renameTo(plan.backupRoot)) {
            throw IOException("Unable to back up the existing model bundle: ${plan.bundleRoot}")
        }

        if (!plan.contentRoot.renameTo(plan.bundleRoot)) {
            if (hadExistingBundle && !plan.bundleRoot.exists()) {
                val restored = plan.backupRoot.renameTo(plan.bundleRoot)
                if (!restored) {
                    throw IOException(
                        "Unable to commit the new bundle or restore the previous bundle. Backup: ${plan.backupRoot}"
                    )
                }
            }
            throw IOException("Unable to commit staged model bundle: ${plan.contentRoot}")
        }

        plan.workRoot.deleteRecursively()
        plan.backupRoot.deleteRecursively()
    }

    internal suspend fun reusableStagedDigest(target: ModelBundleDownloadTarget): String? {
        val file = target.stagedFile
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return null
        val expectedLength = target.remote.sizeBytes
        if (expectedLength != null && expectedLength > 0L && file.length() != expectedLength) return null
        val digest = cancellableSha256(file)
        val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
        if (expectedSha != null) return digest.takeIf { it.equals(expectedSha, ignoreCase = true) }
        val receipt = runCatching { JSONObject(stagedReceiptFile(target).readText()) }.getOrNull() ?: return null
        return digest.takeIf {
            receipt.optString("sourceIdentity") == target.remote.sourceIdentity() &&
                receipt.optLong("sizeBytes", -1L) == file.length() &&
                receipt.optString("sha256").equals(digest, ignoreCase = true)
        }
    }

    private fun stagedReceiptFile(target: ModelBundleDownloadTarget): File =
        File(target.tempFile.parentFile, target.tempFile.name + ".completed.json")

    internal suspend fun recordStagedReceipt(target: ModelBundleDownloadTarget) {
        val digest = cancellableSha256(target.stagedFile)
        val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
        require(expectedSha == null || digest.equals(expectedSha, ignoreCase = true)) {
            "Downloaded bundle component has an unexpected SHA-256: "+target.relativePath
        }
        val receipt = stagedReceiptFile(target)
        val pending = File(receipt.parentFile, receipt.name + ".writing")
        pending.writeText(JSONObject().put("sourceIdentity", target.remote.sourceIdentity())
            .put("sizeBytes", target.stagedFile.length()).put("sha256", digest).toString())
        java.nio.file.Files.move(pending.toPath(), receipt.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    internal fun requireDownloadedFile(target: ModelBundleDownloadTarget) {
        require(target.stagedFile.isFile && target.stagedFile.canRead()) {
            "Downloaded bundle component is missing or unreadable: ${target.relativePath}"
        }
        require(target.stagedFile.length() > 0L) {
            "Downloaded bundle component is empty: ${target.relativePath}"
        }
        val expectedLength = target.remote.sizeBytes
        if (expectedLength != null && expectedLength > 0L) {
            require(target.stagedFile.length() == expectedLength) {
                "Downloaded bundle component has an unexpected size: ${target.relativePath}"
            }
        }
    }

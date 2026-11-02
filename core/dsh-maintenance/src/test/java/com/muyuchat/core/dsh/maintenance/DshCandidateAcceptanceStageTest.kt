package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Acceptance fixture generator (JVM, opt-in): materialises one real closure candidate from a built
 * APK's own `dsh/MANIFEST.json` + `dsh/payload.zip` with the **production** stager.
 *
 * It is not a test of the upgrade transaction and never runs during a normal build. It exists so a
 * device acceptance run can push an executable, hash-proven candidate into the app's closure root
 * (the out-of-band "installer" step production expects) instead of hand-writing descriptors.
 *
 * ```text
 * ./gradlew :core:dsh-maintenance:testDebugUnitTest \
 *   -Dmca.candidate.apk=<apk> -Dmca.candidate.out=<dir> \
 *   --tests '*DshCandidateAcceptanceStageTest'
 * ```
 */
@OptIn(DshCandidateStagingTestOnly::class)
class DshCandidateAcceptanceStageTest {

    @Test
    fun stageOneRealCandidateFromTheBuiltApk() {
        val apkPath = System.getProperty("mca.candidate.apk").orEmpty()
        val manifestPath = System.getProperty("mca.candidate.manifest").orEmpty()
        val archivePath = System.getProperty("mca.candidate.archive").orEmpty()
        val outPath = System.getProperty("mca.candidate.out").orEmpty()
        val fromFiles = manifestPath.isNotEmpty() && archivePath.isNotEmpty()
        assumeTrue(
            "pass -Dmca.candidate.apk and -Dmca.candidate.out, or -Dmca.candidate.manifest/-Dmca.candidate.archive",
            outPath.isNotEmpty() && (apkPath.isNotEmpty() || fromFiles)
        )
        val outRoot = File(outPath).absoluteFile
        outRoot.mkdirs()
        val candidateId = System.getProperty("mca.candidate.id")?.takeIf { it.isNotEmpty() }
            ?: "acceptance-" + System.currentTimeMillis()
        val manifest: String
        val openArchive: () -> InputStream?
        val closeArchive: () -> Unit
        if (fromFiles) {
            val manifestFile = File(manifestPath)
            val archiveFile = File(archivePath)
            assertTrue("manifest is missing: $manifestPath", manifestFile.isFile)
            assertTrue("archive is missing: $archivePath", archiveFile.isFile)
            manifest = manifestFile.readText()
            openArchive = { archiveFile.inputStream() }
            closeArchive = {}
        } else {
            val apk = File(apkPath)
            assertTrue("APK is missing: $apkPath", apk.isFile)
            val zip = ZipFile(apk)
            manifest = zip.getInputStream(zip.getEntry("assets/dsh/MANIFEST.json"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
            openArchive = { openAsset(zip, "assets/dsh/payload.zip") }
            closeArchive = { zip.close() }
        }
        try {
            val spec = DshPayloadArchiveSpec(
                manifestJson = manifest,
                openArchive = openArchive
            )
            val json = org.json.JSONObject(manifest)
            val result = DshCandidateStager(
                gate = DshCandidateStagingGate.jvmTest(),
                dataRoot = outRoot,
                supportedAbis = setOf("arm64-v8a")
            ).stage(
                DshCandidateStageRequest(
                    candidateId = candidateId,
                    targetAbi = "arm64-v8a",
                    dataSchemaVersion = 5,
                    minReadableDataSchema = 4,
                    migratesData = false,
                    nativeLibraryReplaced = false,
                    prePackagedSlotVerified = true,
                    abiCompatibleWithPrevious = true,
                    archive = spec
                )
            )
            val staged = result as? DshCandidateStageResult.Staged
                ?: throw AssertionError("candidate staging was refused: $result")
            println(
                "DSH_ACCEPTANCE_CANDIDATE root=${staged.stagedRoot} id=${staged.candidateId} " +
                    "fingerprint=${staged.fingerprint} components=${staged.componentHashes.size} " +
                    "archiveSha256=${staged.archiveSha256} payloadVersion=" +
                    json.optString("payloadVersion")
            )
        } finally {
            closeArchive()
        }
    }

    private fun openAsset(zip: ZipFile, name: String): InputStream? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry)
    }
}

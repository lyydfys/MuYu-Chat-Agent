package com.muyuchat.core.dsh.vscreen

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loader provisioning is exercised with real files: a temporary zip stands in for the Shizuku APK,
 * so extraction, the read-only requirement and the memo are covered without Android.
 */
class DshShizukuDexProvisionerTest {

    private val filesDir: File = Files.createTempDirectory("dsh-dex-files").toFile()

    @Test
    fun extractsTheLoaderFromTheManagerApkAndMakesItReadOnly() {
        val apk = temporaryApk(DshShizukuDexProvisioner.ASSET_ENTRY, ByteArray(2_048) { 7 })
        val provisioner = DshShizukuDexProvisioner(
            filesDir = filesDir,
            managerApkPath = { apk.absolutePath },
            environment = { emptyMap() },
            knownShellPaths = emptyList()
        )

        val resolution = provisioner.resolve()
        assertTrue("expected a provisioned dex, got $resolution", resolution is DshShizukuDexResolution.Found)
        val found = resolution as DshShizukuDexResolution.Found
        assertEquals(DshShizukuDexSource.MANAGER_APK_ASSET, found.source)
        val copy = File(found.path)
        assertTrue(copy.isFile)
        assertEquals(2_048L, copy.length())
        assertFalse("Android 14+ refuses to load a writable dex", copy.canWrite())
        assertEquals(found.path, provisioner.currentDexPath())
    }

    @Test
    fun anEnvironmentOverrideWinsAndItsSourceIsReported() {
        val explicit = File(filesDir, "manual-rish.dex").apply { writeBytes(ByteArray(32) { 1 }) }
        val provisioner = DshShizukuDexProvisioner(
            filesDir = filesDir,
            managerApkPath = { null },
            environment = { mapOf(DshShizukuDexProvisioner.ENVIRONMENT_KEY to explicit.absolutePath) },
            knownShellPaths = emptyList()
        )

        val found = provisioner.resolve() as DshShizukuDexResolution.Found
        assertEquals(DshShizukuDexSource.ENVIRONMENT, found.source)
        assertEquals(explicit.absolutePath, found.path)
    }

    @Test
    fun anExistingPrivateCopyIsReusedAndStillMadeReadOnly() {
        val directory = File(filesDir, DshShizukuDexProvisioner.DIRECTORY).apply { mkdirs() }
        val copy = File(directory, DshShizukuDexProvisioner.DEX_NAME).apply {
            writeBytes(ByteArray(64) { 3 })
            setWritable(true)
        }
        val provisioner = DshShizukuDexProvisioner(
            filesDir = filesDir,
            managerApkPath = { null },
            environment = { emptyMap() },
            knownShellPaths = emptyList()
        )

        val found = provisioner.resolve() as DshShizukuDexResolution.Found
        assertEquals(DshShizukuDexSource.APP_PRIVATE_COPY, found.source)
        assertFalse(copy.canWrite())
    }

    @Test
    fun anApkWithoutTheAssetFallsThroughToATypedMiss() {
        val apk = temporaryApk(null, ByteArray(0))
        val provisioner = DshShizukuDexProvisioner(
            filesDir = filesDir,
            managerApkPath = { apk.absolutePath },
            environment = { emptyMap() },
            knownShellPaths = listOf("/data/local/tmp/definitely-absent/rish_shizuku.dex")
        )

        val missing = provisioner.resolve()
        assertTrue(missing is DshShizukuDexResolution.Missing)
        assertTrue((missing as DshShizukuDexResolution.Missing).detail.contains("SHIZUKU_DEX"))
        val directory = File(filesDir, DshShizukuDexProvisioner.DIRECTORY)
        assertTrue("a failed extraction must not leave a temporary dex", directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun aProvisionedDexIsAcceptedByTheRishCommandBuilder() {
        val apk = temporaryApk(DshShizukuDexProvisioner.ASSET_ENTRY, ByteArray(128) { 5 })
        val provisioner = DshShizukuDexProvisioner(
            filesDir = filesDir,
            managerApkPath = { apk.absolutePath },
            environment = { emptyMap() },
            knownShellPaths = emptyList()
        )
        val path = provisioner.currentDexPath()!!
        assertTrue("the provisioned dex must be a real file", File(path).isFile)
        // The rish builder validates the Android POSIX form; this test runs on a Windows JVM where
        // the temp dir is a drive path, so assert acceptance with the production-shaped path.
        val androidPath = "/data/user/0/com.muyuchat.mca/files/dsh-shizuku/rish_shizuku.dex"
        val invocation = DshShizukuRishCommand.build(androidPath, "com.muyuchat.mca", "input -d 1 tap 2 3")
        assertTrue(invocation.argv.contains("-Djava.class.path=$androidPath"))
        assertEquals("input -d 1 tap 2 3", invocation.argv.last())
    }

    @Test
    fun aFailedResolutionIsMemoizedAndExpires() {
        var now = 0L
        var apkLookups = 0
        val provisioner = DshShizukuDexProvisioner(
            filesDir = filesDir,
            managerApkPath = { apkLookups += 1; null },
            environment = { emptyMap() },
            knownShellPaths = emptyList(),
            clock = { now }
        )

        provisioner.resolve()
        provisioner.resolve()
        assertEquals("a missing loader must not reopen the APK on every command", 1, apkLookups)

        now += 31_000L
        provisioner.resolve()
        assertEquals(2, apkLookups)
    }

    /** Builds a zip that stands in for an installed APK; [entryName] null means "no loader asset". */
    private fun temporaryApk(entryName: String?, bytes: ByteArray): File {
        val directory = Files.createTempDirectory("dsh-apk").toFile()
        val apk = File(directory, "shizuku.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            if (entryName != null) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            } else {
                zip.putNextEntry(ZipEntry("assets/unrelated.txt"))
                zip.write(1)
                zip.closeEntry()
            }
        }
        return apk
    }
}

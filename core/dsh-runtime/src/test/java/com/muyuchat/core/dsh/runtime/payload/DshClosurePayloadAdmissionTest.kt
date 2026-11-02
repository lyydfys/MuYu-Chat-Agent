package com.muyuchat.core.dsh.runtime.payload

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshClosurePayloadAdmissionTest {

    private val kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Fixture {
        val dataRoot: File = Files.createTempDirectory("dsh-admission").toFile()
        val closureRoot: File = File(dataRoot, "closure/incoming/v1")
        val payloadRoot: File = File(closureRoot, "payload")
        val critical: File = File(payloadRoot, "dshhome/profiles/web/package.json")
        val kernel: File = File(payloadRoot, "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

        fun prepare() {
            critical.parentFile.mkdirs()
            kernel.parentFile.mkdirs()
            critical.writeText("""{"name":"web"}""")
            kernel.writeText("// kernel\n")
        }

        fun writeDescriptor(criticalPath: String, criticalSha: String) {
            File(closureRoot, "candidate.json").writeText(
                """
                {"payload":{"root":"payload","fileCount":2,
                "criticalFiles":[{"path":"$criticalPath","sha256":"$criticalSha"}]}}
                """.trimIndent()
            )
        }
    }

    @Test
    fun admitsACommittedClosureWithMatchingCriticalFiles() {
        val fixture = Fixture()
        fixture.prepare()
        fixture.writeDescriptor(
            "dshhome/profiles/web/package.json",
            sha256(fixture.critical)
        )

        val result = DshClosurePayloadAdmission.admit(
            dataRoot = fixture.dataRoot,
            payloadRoot = fixture.payloadRoot,
            kernelEntryRelativePath = kernelEntry
        )

        assertTrue(result is DshClosurePayloadAdmission.Result.Admitted)
        assertEquals(
            fixture.payloadRoot.canonicalFile,
            (result as DshClosurePayloadAdmission.Result.Admitted).payloadRoot
        )
    }

    @Test
    fun refusesAPayloadOutsideTheClosureStore() {
        val fixture = Fixture()
        fixture.prepare()
        fixture.writeDescriptor(
            "dshhome/profiles/web/package.json",
            sha256(fixture.critical)
        )
        val outside = File(fixture.dataRoot, "payload")
        fixture.payloadRoot.copyRecursively(outside, overwrite = true)

        val result = DshClosurePayloadAdmission.admit(
            dataRoot = fixture.dataRoot,
            payloadRoot = outside,
            kernelEntryRelativePath = kernelEntry
        )

        assertTrue(result is DshClosurePayloadAdmission.Result.Refused)
    }

    @Test
    fun refusesACriticalFileThatDoesNotMatchItsRecordedHash() {
        val fixture = Fixture()
        fixture.prepare()
        fixture.writeDescriptor("dshhome/profiles/web/package.json", "0".repeat(64))

        val result = DshClosurePayloadAdmission.admit(
            dataRoot = fixture.dataRoot,
            payloadRoot = fixture.payloadRoot,
            kernelEntryRelativePath = kernelEntry
        )

        assertTrue(result is DshClosurePayloadAdmission.Result.Refused)
    }

    @Test
    fun refusesAPayloadWithoutItsKernelEntry() {
        val fixture = Fixture()
        fixture.prepare()
        fixture.writeDescriptor(
            "dshhome/profiles/web/package.json",
            sha256(fixture.critical)
        )
        fixture.kernel.delete()

        val result = DshClosurePayloadAdmission.admit(
            dataRoot = fixture.dataRoot,
            payloadRoot = fixture.payloadRoot,
            kernelEntryRelativePath = kernelEntry
        )

        assertTrue(result is DshClosurePayloadAdmission.Result.Refused)
    }

    @Test
    fun refusesACriticalPathThatEscapesThePayloadTree() {
        val fixture = Fixture()
        fixture.prepare()
        fixture.writeDescriptor("../outside.txt", "0".repeat(64))

        val result = DshClosurePayloadAdmission.admit(
            dataRoot = fixture.dataRoot,
            payloadRoot = fixture.payloadRoot,
            kernelEntryRelativePath = kernelEntry
        )

        assertTrue(result is DshClosurePayloadAdmission.Result.Refused)
    }
}

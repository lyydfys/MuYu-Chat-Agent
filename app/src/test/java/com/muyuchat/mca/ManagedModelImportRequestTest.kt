package com.muyuchat.mca

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedModelImportRequestTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun taskPersistsIdentityModeAndSourceAcrossRestart() {
        val request = ManagedModelImportRequest(uris = listOf("content://provider/tree/中文模型 1"),
            directory = true, textOnly = true, persistentAccess = true)
        request.save(folder.root)
        assertEquals(request, ManagedModelImportRequest.read(folder.root, request.id))
        // Retry keeps the same staging identity rather than creating another model copy.
        ManagedModelImportRequest.read(folder.root, request.id).save(folder.root)
        assertEquals(request.id, ManagedModelImportRequest.read(folder.root, request.id).id)
    }

    @Test fun largeComponentListIsStoredOutsideWorkManagerData() {
        val request = ManagedModelImportRequest(uris = (1..800).map { "content://provider/document/模型/组件 $it.weight" })
        assertTrue(request.toJson().toByteArray().size > 10 * 1024)
        request.save(folder.root)
        assertEquals(request.uris, ManagedModelImportRequest.read(folder.root, request.id).uris)
    }

    @Test fun completeImportIsDefaultAndTemporaryAccessIsNotClaimedPersistent() {
        val request = ManagedModelImportRequest(uris = listOf("content://provider/file/model.litertlm"))
        val restored = ManagedModelImportRequest.fromJson(request.toJson())
        assertFalse(restored.textOnly)
        assertFalse(restored.directory)
        assertFalse(restored.persistentAccess)
    }

    @Test fun differentSelectionsDoNotShareStaging() {
        val first = ManagedModelImportRequest(uris = listOf("content://provider/model"))
        val second = ManagedModelImportRequest(uris = first.uris, textOnly = true)
        assertNotEquals(first.id, second.id)
    }

    @Test fun tamperedIdentityCannotReadOutsideTaskDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagedModelImportRequest.read(folder.root, "../../models")
        }
        val request = ManagedModelImportRequest(uris = listOf("content://provider/model"))
        request.save(folder.root)
        java.io.File(folder.root, "model-import-tasks/${request.id}.json").writeText(
            ManagedModelImportRequest(uris = request.uris).toJson())
        assertThrows(IllegalArgumentException::class.java) { ManagedModelImportRequest.read(folder.root, request.id) }
    }

    @Test fun invalidDirectorySelectionsFailBeforeEnqueue() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagedModelImportRequest(uris = listOf("content://provider/a", "content://provider/b"), directory = true)
        }
        assertThrows(IllegalArgumentException::class.java) { ManagedModelImportRequest(uris = emptyList()) }
    }
}

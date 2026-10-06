package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelLoadLifecycleContractTest {
    @Test
    fun successfulFormalLoadPublishesTheModelAndReadyLifecycleTogether() {
        // R3/S24 + v4-①: the formal-load publication section lives wherever its marker lives;
        // located by content, no path literals. Same anchors and ordering contract.
        val source = SourceTextFixtures.sourceOf("val nativeStatsAfterLoad = engine.nativeStatsJsonOrEmpty()")
        // R3/S15: engine 读取换名（RuntimeEngineReads 缝）；发布区段锚点随迁。
        val publicationStart = source.indexOf("val nativeStatsAfterLoad = engine.nativeStatsJsonOrEmpty()")
        val publicationEnd = source.indexOf("persistChatSessions(sessionsToPersist)", publicationStart)

        assertTrue("Missing formal-load publication start", publicationStart >= 0)
        assertTrue("Missing formal-load publication end", publicationEnd > publicationStart)

        val publication = source.substring(publicationStart, publicationEnd)
        val modelPublished = publication.indexOf("loadedModelId = model.id")
        val statsPublished = publication.indexOf("stats = engine.stats.value")
        val readyPublished = publication.indexOf("engineLifecycle = AgentEngineLifecycle.READY")

        assertTrue("The loaded model id must be published", modelPublished >= 0)
        assertTrue("Loaded runtime stats must be published with the model", statsPublished > modelPublished)
        assertTrue("A successful formal load must publish READY", readyPublished > statsPublished)
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}

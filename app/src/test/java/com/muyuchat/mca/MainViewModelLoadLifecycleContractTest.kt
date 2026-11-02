package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelLoadLifecycleContractTest {
    @Test
    fun successfulFormalLoadPublishesTheModelAndReadyLifecycleTogether() {
        val source = SourceTextFixtures.sourceOf("val nativeStatsAfterLoad = engine.nativeStatsJsonOrEmpty()")
        val publicationStart = source.indexOf("val nativeStatsAfterLoad = engine.nativeStatsJsonOrEmpty()")
        val publicationEnd = source.indexOf("persistChatSessions(sessionsToPersist)", publicationStart)

        assertTrue("Missing formal-load publication start", publicationStart >= 0)
        assertTrue("Missing formal-load publication end", publicationEnd > publicationStart)

        val publication = source.substring(publicationStart, publicationEnd)
        // D02 freezes native/engine reads before CAS. The successful CAS still publishes the model,
        // its captured stats and READY lifecycle together, and supplies the exact sessions persisted.
        val statsCaptured = publication.indexOf("val statsAfterLoad = engine.stats.value")
        val commitStart = publication.indexOf("val sessionsToPersist = store.updateWithResult")
        assertTrue("Loaded runtime stats must be captured before the retryable commit",
            statsCaptured >= 0 && commitStart > statsCaptured)
        val commit = bracedBlock(publication, commitStart)
        val modelPublished = commit.indexOf("loadedModelId = model.id")
        val statsPublished = commit.indexOf("stats = statsAfterLoad")
        val readyPublished = commit.indexOf("engineLifecycle = AgentEngineLifecycle.READY")

        assertTrue("The loaded model id must be published", modelPublished >= 0)
        assertTrue("Loaded runtime stats must be published with the model", statsPublished > modelPublished)
        assertTrue("A successful formal load must publish READY", readyPublished > statsPublished)
        assertTrue("The commit must return the session list from the same CAS attempt", commit.contains(") to sessions"))
        assertEquals("Formal-load publication must remain a single atomic state commit", 1,
            Regex("""store\.update(?:WithResult|AndGet)?\s*\{""").findAll(publication).count())
        assertTrue("Native/engine reads must remain outside the retryable publication",
            !commit.contains("engine.") && !commit.contains("modelStore.") && !commit.contains("cloudApiStore."))
    }

    private fun bracedBlock(source: String, start: Int): String {
        require(start >= 0) { "Missing publication anchor" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing publication body" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openingBrace, index + 1)
                }
            }
        }
        error("Unterminated publication body")
    }
}

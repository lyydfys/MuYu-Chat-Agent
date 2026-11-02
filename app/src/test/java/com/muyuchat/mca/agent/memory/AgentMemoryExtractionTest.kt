package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic extraction (N16a/N16b): deterministic rules, the injectable model port, credential and
 * bounds filtering, candidate/committed separation, dedupe and the approval-bound commit.
 *
 * The tests deliberately assert the *absence* of writes during extraction, because "candidates are
 * not items" is a contract, not an implementation detail.
 */
class AgentMemoryExtractionTest {
    private val persistence = InMemoryAgentMemoryPersistence()
    private val store = AgentMemoryTestFixtures.store(persistence, AgentMemoryTestFixtures.sequentialIds())
    private val extractor = AgentMemoryExtractor(
        store = store,
        idFactory = AgentMemoryTestFixtures.sequentialIds("candidate")
    )

    init {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
    }

    @Test
    fun `rule extraction proposes candidates without writing an item`() = runBlocking {
        val result = propose(
            """
            请记住：明天九点开会。
            我的生日是十月一日。
            我喜欢喝乌龙茶。
            不要在工作时间玩游戏。
            """.trimIndent()
        )

        assertEquals(
            listOf("remember_explicit", "profile_fact", "preference", "constraint"),
            result.candidates.map { (it.origin as AgentMemoryExtractionOrigin.Rule).ruleId }
        )
        assertEquals("明天九点开会", result.candidates.first().text)
        assertEquals(AgentMemoryCandidateConfidence.HIGH, result.candidates.first().confidence)
        assertEquals("session-1", result.candidates.first().sourceRef)
        assertTrue(result.candidates.all { AgentMemoryText.sha256(it.text) == it.contentHash })
        assertEquals(AgentMemoryExtractionModelStatus.DISABLED, result.modelStatus)
        assertFalse(result.truncated)

        // The whole point: review produces no storage write.
        assertEquals(0, persistence.transactionCount)
        assertEquals(0, persistence.liveItemCount("space-1"))
        assertEquals(0, persistence.liveChunkCount(listOf("space-1")))
    }

    @Test
    fun `the remember trigger is stripped and matched case-insensitively`() = runBlocking {
        val result = propose("Remember this: the release train leaves on Friday")

        val candidate = result.candidates.single()
        assertEquals("the release train leaves on Friday", candidate.text)
        assertEquals(AgentMemoryExtractionOrigin.Rule("remember_explicit"), candidate.origin)
    }

    @Test
    fun `repeated sentences produce one candidate and count the duplicates`() = runBlocking {
        val result = propose("记住：周四交报表。记住：周四交报表。")

        assertEquals(1, result.candidates.size)
        assertEquals(1, result.droppedDuplicates)
        assertEquals("周四交报表", result.candidates.single().text)
    }

    @Test
    fun `an item already stored in the space is flagged and the commit is a no-op`() = runBlocking {
        val ingesting = store.ingest(
            spaceId = "space-1",
            request = AgentMemoryIngestRequest("生日", "我的生日是十月一日", AgentMemorySourceKind.USER_TEXT),
            approval = AgentMemoryTestFixtures.itemsApproval("space-1")
        ) as AgentMemoryIngestOutcome.Ingested

        val result = propose("我的生日是十月一日")
        val candidate = result.candidates.single()
        assertEquals(ingesting.item.memoryId, candidate.duplicateOf?.memoryId)
        assertFalse(candidate.duplicateOf!!.revoked)

        val commit = extractor.commit(
            spaceId = "space-1",
            candidates = result.candidates,
            approval = AgentMemoryTestFixtures.itemsApproval("space-1")
        ) as AgentMemoryExtractionCommitOutcome.Applied

        val entry = commit.result.entries.single()
        assertEquals(AgentMemoryExtractionCommitStatus.ALREADY_PRESENT, entry.status)
        assertEquals(ingesting.item.memoryId, entry.memoryId)
        assertEquals(1, persistence.liveItemCount("space-1"))
    }

    @Test
    fun `the duplicate lookup never crosses the target space`() = runBlocking {
        store.ingest(
            spaceId = "space-2",
            request = AgentMemoryIngestRequest("生日", "我的生日是十月一日", AgentMemorySourceKind.USER_TEXT),
            approval = AgentMemoryTestFixtures.itemsApproval("space-2")
        )

        assertNull(propose("我的生日是十月一日", spaceId = "space-1").candidates.single().duplicateOf)
        assertNotNull(propose("我的生日是十月一日", spaceId = "space-2").candidates.single().duplicateOf)
    }

    @Test
    fun `credentials are dropped before they can become candidates`() = runBlocking {
        val result = propose(
            "请记住：我的密码是 hunter2。" +
                "请记住 API key: sk-abcdefghijklmnopqrstuvwx。" +
                "记住：明天九点开会。"
        )

        assertEquals(1, result.candidates.size)
        assertEquals("明天九点开会", result.candidates.single().text)
        assertEquals(2, result.droppedSecrets)
    }

    @Test
    fun `oversized and too short sentences are dropped instead of truncated`() = runBlocking {
        val result = propose("记住：" + "长".repeat(AgentMemoryLimits.MAX_EXTRACTION_CANDIDATE_CHARS + 50) + "。记住")

        assertTrue(result.candidates.isEmpty())
        assertEquals(2, result.droppedForBounds)
    }

    @Test
    fun `the candidate budget stops the run deterministically and reports truncation`() = runBlocking {
        val text = (1..40).joinToString("。") { index -> "记住：事实编号$index" }

        val result = propose(text)

        assertEquals(AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES, result.candidates.size)
        assertTrue(result.truncated)
        assertEquals("事实编号1", result.candidates.first().text)
        assertEquals(
            "事实编号${AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES}",
            result.candidates.last().text
        )
    }

    @Test
    fun `a model port contributes proposals through the same filters`() = runBlocking {
        val model = FakeExtractionModel(
            proposals = listOf(
                AgentMemoryExtractionModelProposal("用户偏好深色主题", confidence = 80),
                AgentMemoryExtractionModelProposal("记住 password: hunter2", confidence = 99),
                AgentMemoryExtractionModelProposal("明天九点开会", confidence = 90),
                AgentMemoryExtractionModelProposal("长".repeat(900), confidence = 10)
            )
        )
        val extractor = AgentMemoryExtractor(
            store = store,
            model = model,
            idFactory = AgentMemoryTestFixtures.sequentialIds("model-candidate")
        )

        val result = extractor.extract(AgentMemoryExtractionRequest("space-1", "记住：明天九点开会"))
            .let { (it as AgentMemoryExtractionOutcome.Proposed).result }

        assertEquals(2, result.candidates.size)
        assertEquals(AgentMemoryExtractionModelStatus.APPLIED, result.modelStatus)
        assertEquals(
            AgentMemoryExtractionOrigin.Rule("remember_explicit"),
            result.candidates[0].origin
        )
        assertEquals(
            AgentMemoryExtractionOrigin.Model("local-fake-v1"),
            result.candidates[1].origin
        )
        assertEquals(AgentMemoryCandidateConfidence.HIGH, result.candidates[1].confidence)
        assertEquals(1, result.droppedSecrets)
        assertEquals(1, result.droppedDuplicates)
        assertEquals(1, result.droppedForBounds)
        assertEquals(1, model.calls)
    }

    @Test
    fun `a failing or anonymous model port leaves the rule candidates intact`() = runBlocking {
        val failing = AgentMemoryExtractor(
            store = store,
            model = FakeExtractionModel(failure = true)
        )
        val failedResult = failing.extract(AgentMemoryExtractionRequest("space-1", "记住：明天九点开会"))
            .let { (it as AgentMemoryExtractionOutcome.Proposed).result }
        assertEquals(AgentMemoryExtractionModelStatus.FAILED, failedResult.modelStatus)
        assertEquals(listOf("明天九点开会"), failedResult.candidates.map { it.text })

        val anonymous = AgentMemoryExtractor(
            store = store,
            model = FakeExtractionModel(fingerprint = "  ")
        )
        val anonymousResult = anonymous.extract(AgentMemoryExtractionRequest("space-1", "记住：明天九点开会"))
            .let { (it as AgentMemoryExtractionOutcome.Proposed).result }
        assertEquals(AgentMemoryExtractionModelStatus.FAILED, anonymousResult.modelStatus)
        assertEquals(1, anonymousResult.candidates.size)
    }

    @Test
    fun `commit requires an approval for exactly the target space`() = runBlocking {
        val result = propose("请记住：周五发版")

        val foreign = extractor.commit(
            spaceId = "space-1",
            candidates = result.candidates,
            approval = AgentMemoryTestFixtures.itemsApproval("space-2")
        ) as AgentMemoryExtractionCommitOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, foreign.error.code)

        val admin = extractor.commit(
            spaceId = "space-1",
            candidates = result.candidates,
            approval = AgentMemoryTestFixtures.adminApproval()
        ) as AgentMemoryExtractionCommitOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, admin.error.code)

        assertEquals(0, persistence.liveItemCount("space-1"))
    }

    @Test
    fun `commit writes an auto-extraction item with its source reference`() = runBlocking {
        val result = propose("请记住：周五发版")
        val commit = extractor.commit(
            spaceId = "space-1",
            candidates = result.candidates,
            approval = AgentMemoryTestFixtures.itemsApproval("space-1")
        ) as AgentMemoryExtractionCommitOutcome.Applied

        val entry = commit.result.entries.single()
        assertEquals(AgentMemoryExtractionCommitStatus.STORED, entry.status)
        assertTrue(commit.result.allAccepted)
        val stored = requireNotNull(persistence.findItem(requireNotNull(entry.memoryId)))
        assertEquals(AgentMemorySourceKind.AUTO_EXTRACTION, stored.sourceKind)
        assertEquals("周五发版", stored.text)
        assertEquals("session-1", stored.sourceRef)
        assertEquals(1, persistence.liveItemCount("space-1"))
    }

    @Test
    fun `commit rejects tampered candidates without writing anything`() = runBlocking {
        val genuine = propose("请记住：周五发版").candidates.single()

        val rewritten = extractor.commit(
            spaceId = "space-1",
            candidates = listOf(genuine.copy(text = "被替换的事实")),
            approval = AgentMemoryTestFixtures.itemsApproval("space-1")
        ) as AgentMemoryExtractionCommitOutcome.Applied
        assertEquals(AgentMemoryExtractionCommitStatus.REJECTED, rewritten.result.entries.single().status)
        assertEquals(
            AgentMemoryErrorCodes.CANDIDATE_INVALID,
            rewritten.result.entries.single().errorCode
        )

        val forgedHash = extractor.commit(
            spaceId = "space-1",
            candidates = listOf(genuine.copy(contentHash = "a".repeat(64))),
            approval = AgentMemoryTestFixtures.itemsApproval("space-1")
        ) as AgentMemoryExtractionCommitOutcome.Applied
        assertEquals(AgentMemoryExtractionCommitStatus.REJECTED, forgedHash.result.entries.single().status)

        assertEquals(0, persistence.liveItemCount("space-1"))
    }

    @Test
    fun `commit rejects an empty selection`() = runBlocking {
        val outcome = extractor.commit(
            spaceId = "space-1",
            candidates = emptyList(),
            approval = AgentMemoryTestFixtures.itemsApproval("space-1")
        ) as AgentMemoryExtractionCommitOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.CANDIDATE_INVALID, outcome.error.code)
    }

    @Test
    fun `invalid requests are rejected with typed errors`() = runBlocking {
        assertEquals(
            AgentMemoryErrorCodes.EXTRACTION_REQUEST_INVALID,
            rejectedExtraction("bad space!", "记住：事实")
        )
        assertEquals(
            AgentMemoryErrorCodes.TEXT_EMPTY,
            rejectedExtraction("space-1", "   ")
        )
        assertEquals(
            AgentMemoryErrorCodes.EXTRACTION_SOURCE_TOO_LARGE,
            rejectedExtraction("space-1", "记住：" + "字".repeat(AgentMemoryLimits.MAX_TEXT_BYTES))
        )
        val longRef = extractor.extract(
            AgentMemoryExtractionRequest(
                spaceId = "space-1",
                text = "记住：事实",
                sourceRef = "r".repeat(AgentMemoryLimits.MAX_SOURCE_REF_CHARS + 1)
            )
        ) as AgentMemoryExtractionOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.SOURCE_REF_TOO_LARGE, longRef.error.code)
    }

    private suspend fun propose(
        text: String,
        spaceId: String = "space-1"
    ): AgentMemoryExtractionResult {
        val outcome = extractor.extract(
            AgentMemoryExtractionRequest(spaceId = spaceId, text = text, sourceRef = "session-1")
        )
        return (outcome as AgentMemoryExtractionOutcome.Proposed).result
    }

    private suspend fun rejectedExtraction(spaceId: String, text: String): String {
        val outcome = extractor.extract(AgentMemoryExtractionRequest(spaceId = spaceId, text = text))
        return (outcome as AgentMemoryExtractionOutcome.Rejected).error.code
    }

    private class FakeExtractionModel(
        override val fingerprint: String = "local-fake-v1",
        private val proposals: List<AgentMemoryExtractionModelProposal> = emptyList(),
        private val failure: Boolean = false
    ) : AgentMemoryExtractionModel {
        var calls: Int = 0
            private set

        override suspend fun propose(
            text: String,
            maxProposals: Int
        ): List<AgentMemoryExtractionModelProposal> {
            calls++
            if (failure) throw IllegalStateException("model is not available")
            return proposals.take(maxProposals)
        }
    }
}

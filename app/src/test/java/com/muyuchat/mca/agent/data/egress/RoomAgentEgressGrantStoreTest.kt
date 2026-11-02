package com.muyuchat.mca.agent.data.egress

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentGrantDao
import com.muyuchat.mca.agent.data.AgentGrantEntity
import com.muyuchat.mca.agent.data.AgentGrantScope
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressGrantOutcome
import com.muyuchat.mca.agent.security.AgentEgressTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RoomAgentEgressGrantStoreTest {
    private val now = 1_000L

    @Test
    fun `task scoped grant persists and run grant derives its task`() = runBlocking {
        val dao = FakeGrantDao()
        val reader = ContextReader(
            tasks = mapOf("task-1" to context("task-1", 3L)),
            runs = mapOf("run-1" to context("task-1", 3L))
        )
        val store = store(dao, reader)

        val taskOutcome = store.create(taskGrant("grant-task"))
        assertTrue(taskOutcome is AgentEgressGrantOutcome.Created)
        val taskGrant = (taskOutcome as AgentEgressGrantOutcome.Created).grant
        assertEquals("task-1", taskGrant.taskId)
        assertNull(taskGrant.runId)
        val taskEntity = dao.rows.getValue("grant-task")
        assertEquals(AgentGrantScope.EGRESS_PROVIDER.name, taskEntity.scopeType)
        assertEquals("task-1", taskEntity.taskId)
        assertNull(taskEntity.runId)
        assertEquals(3L, taskEntity.policyVersion)
        assertNull(taskEntity.revokedAt)

        val runOutcome = store.create(runGrant("grant-run"))
        assertTrue(runOutcome is AgentEgressGrantOutcome.Created)
        assertEquals("task-1", (runOutcome as AgentEgressGrantOutcome.Created).grant.taskId)
        val runEntity = dao.rows.getValue("grant-run")
        assertEquals("task-1", runEntity.taskId)
        assertEquals("run-1", runEntity.runId)
    }

    @Test
    fun `stale expired malformed and non-canonical grants are rejected without writes`() = runBlocking {
        val dao = FakeGrantDao()
        val reader = ContextReader(
            tasks = mapOf("task-1" to context("task-1", 3L)),
            runs = mapOf("run-1" to context("task-1", 3L))
        )
        val store = store(dao, reader)

        val stale = store.create(taskGrant("grant-stale", policyVersion = 2L))
        assertEquals("POLICY_EGRESS_DENIED", errorCode(stale))
        assertEquals(0, dao.rows.size)

        val expired = store.create(taskGrant("grant-expired", expiresAtMillis = now - 1L))
        assertEquals("POLICY_EGRESS_DENIED", errorCode(expired))
        assertEquals(0, dao.rows.size)

        val uppercase = store.create(
            runGrant("grant-uppercase", origin = "https://API.example.com")
        )
        assertEquals("POLICY_EGRESS_DENIED", errorCode(uppercase))
        assertEquals(0, dao.rows.size)

        val cleartext = store.create(
            runGrant("grant-cleartext", origin = "http://api.example.com")
        )
        assertEquals("POLICY_EGRESS_DENIED", errorCode(cleartext))
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `local only rejects cloud but permits a private local server`() = runBlocking {
        val dao = FakeGrantDao()
        val reader = ContextReader(
            tasks = emptyMap(),
            runs = mapOf("run-1" to context("task-1", 3L, AgentNetworkPolicy.LOCAL_ONLY))
        )
        val store = store(dao, reader)
        val cloud = store.create(runGrant("grant-cloud", policyVersion = 3L))
        assertEquals("POLICY_OFFLINE", errorCode(cloud))
        assertTrue(dao.rows.isEmpty())

        val local = store.create(
            runGrant(
                "grant-local",
                transport = AgentEgressTransport.LOCAL_SERVER,
                origin = "http://127.0.0.1:8080"
            )
        )
        assertTrue(local is AgentEgressGrantOutcome.Created)
        assertEquals("run-1", dao.rows.getValue("grant-local").runId)
    }

    @Test
    fun `duplicate grant id replaces only within the same task`() = runBlocking {
        val dao = FakeGrantDao()
        val reader = ContextReader(
            tasks = mapOf(
                "task-1" to context("task-1", 3L),
                "task-2" to context("task-2", 3L)
            ),
            runs = emptyMap()
        )
        val store = store(dao, reader)
        assertTrue(store.create(taskGrant("grant-dup")) is AgentEgressGrantOutcome.Created)
        val replaced = store.create(taskGrant("grant-dup", origin = "https://second.example.com"))
        assertTrue(replaced is AgentEgressGrantOutcome.Created)
        assertEquals(1, dao.rows.size)
        assertEquals("https://second.example.com", AgentEgressGrantMapper.decodeScope(
            dao.rows.getValue("grant-dup").scopeJson
        )!!.origin)

        val otherTask = store.create(
            AgentEgressGrant(
                grantId = "grant-dup",
                runId = null,
                taskId = "task-2",
                transport = AgentEgressTransport.CLOUD_PROVIDER,
                origin = "https://third.example.com",
                allowedDataClasses = setOf(AgentDataClass.PRIVATE),
                allowedSourceIds = setOf("source-1"),
                policyVersion = 3L
            )
        )
        assertEquals("POLICY_EGRESS_DENIED", errorCode(otherTask))
        assertEquals("https://second.example.com", AgentEgressGrantMapper.decodeScope(
            dao.rows.getValue("grant-dup").scopeJson
        )!!.origin)
    }

    @Test
    fun `active listing filters stale revoked expired and non-egress rows`() = runBlocking {
        val dao = FakeGrantDao()
        val reader = ContextReader(
            tasks = mapOf("task-1" to context("task-1", 3L)),
            runs = mapOf(
                "run-1" to context("task-1", 3L),
                "run-2" to context("task-1", 3L)
            )
        )
        val store = store(dao, reader)
        assertTrue(store.create(taskGrant("grant-task")) is AgentEgressGrantOutcome.Created)
        assertTrue(store.create(runGrant("grant-run")) is AgentEgressGrantOutcome.Created)
        val stale = AgentEgressGrantMapper.toEntity(
            runGrant("grant-stale", policyVersion = 2L).copy(runId = "run-2"),
            "task-1",
            now
        )
        dao.upsert(stale)
        dao.upsert(
            AgentEgressGrantMapper.toEntity(taskGrant("grant-revoked"), "task-1", now)
                .copy(revokedAt = 900L, revokedReason = "USER")
        )
        val activeTaskEntity = AgentEgressGrantMapper.toEntity(taskGrant("grant-task"), "task-1", now)
        dao.upsert(activeTaskEntity)
        dao.upsert(
            AgentEgressGrantMapper.toEntity(taskGrant("grant-expired"), "task-1", now)
                .copy(expiresAt = 500L)
        )
        dao.upsert(
            AgentGrantEntity(
                grantId = "workspace-grant",
                taskId = "task-1",
                runId = null,
                scopeType = AgentGrantScope.WORKSPACE_READ.name,
                scopeJson = "{}",
                policyVersion = 3L,
                modelFingerprint = null,
                grantedAt = 100L,
                expiresAt = null,
                revokedAt = null,
                revokedReason = null
            )
        )

        val active = store.listActiveForTask("task-1", now, 20)
        assertEquals(setOf("grant-task", "grant-run"), active.map { it.grantId }.toSet())
        assertTrue(active.all { !it.revoked && it.taskId == "task-1" })
    }

    @Test
    fun `revoke is CAS based idempotent and limited to egress grants`() = runBlocking {
        val dao = FakeGrantDao()
        val reader = ContextReader(
            tasks = mapOf("task-1" to context("task-1", 3L)),
            runs = mapOf("run-1" to context("task-1", 3L))
        )
        val store = store(dao, reader)
        assertTrue(store.create(runGrant("grant-run")) is AgentEgressGrantOutcome.Created)
        assertTrue(store.revoke("grant-run", 1_100L, "USER_REVOKED"))
        assertEquals(1_100L, dao.rows.getValue("grant-run").revokedAt)
        assertEquals("USER_REVOKED", dao.rows.getValue("grant-run").revokedReason)
        assertTrue("idempotent repeat", store.revoke("grant-run", 1_200L, "USER_REVOKED"))
        assertFalse(store.revoke("missing-grant", 1_100L, "USER_REVOKED"))
        assertFalse(store.revoke("grant-run", -1L, "USER_REVOKED"))
        assertFalse(store.revoke("grant-run", 1_100L, " "))

        dao.upsert(
            AgentGrantEntity(
                grantId = "workspace-grant",
                taskId = "task-1",
                runId = null,
                scopeType = AgentGrantScope.WORKSPACE_READ.name,
                scopeJson = "{}",
                policyVersion = 3L,
                modelFingerprint = null,
                grantedAt = 1L,
                expiresAt = null,
                revokedAt = null,
                revokedReason = null
            )
        )
        assertFalse("egress port must not revoke a workspace grant", store.revoke("workspace-grant", 1_100L, "X"))
    }

    @Test
    fun `task scope revoke leaves run grants and other tasks intact`() = runBlocking {
        val dao = FakeGrantDao()
        val reader = ContextReader(
            tasks = mapOf(
                "task-1" to context("task-1", 3L),
                "task-2" to context("task-2", 3L)
            ),
            runs = mapOf("run-1" to context("task-1", 3L))
        )
        val store = store(dao, reader)
        assertTrue(store.create(taskGrant("task-grant-1")) is AgentEgressGrantOutcome.Created)
        assertTrue(store.create(taskGrant("task-grant-2")) is AgentEgressGrantOutcome.Created)
        assertTrue(store.create(runGrant("run-grant")) is AgentEgressGrantOutcome.Created)
        assertTrue(
            store.create(
                taskGrant("task-grant-other").copy(taskId = "task-2")
            ) is AgentEgressGrantOutcome.Created
        )

        assertEquals(2, store.revokeTaskScope("task-1", 1_500L, "TASK_DELETED"))
        assertEquals(1_500L, dao.rows.getValue("task-grant-1").revokedAt)
        assertEquals(1_500L, dao.rows.getValue("task-grant-2").revokedAt)
        assertNull(dao.rows.getValue("run-grant").revokedAt)
        assertNull(dao.rows.getValue("task-grant-other").revokedAt)
        assertEquals(0, store.revokeTaskScope("task-1", 1_600L, "TASK_DELETED"))
    }

    private fun store(dao: AgentGrantDao, reader: AgentEgressGrantContextReader): RoomAgentEgressGrantStore =
        RoomAgentEgressGrantStore(
            grantDao = dao,
            transactions = ImmediateTransactions,
            contextReader = reader,
            clock = AgentClock { now }
        )

    private fun context(
        taskId: String,
        policyVersion: Long,
        networkPolicy: AgentNetworkPolicy = AgentNetworkPolicy.NETWORK_ENABLED
    ) = AgentEgressGrantContext(taskId, policyVersion, networkPolicy)

    private fun taskGrant(
        grantId: String,
        origin: String = "https://api.example.com",
        policyVersion: Long = 3L,
        expiresAtMillis: Long? = null
    ) = AgentEgressGrant(
        grantId = grantId,
        runId = null,
        taskId = "task-1",
        transport = AgentEgressTransport.CLOUD_PROVIDER,
        origin = origin,
        allowedDataClasses = setOf(AgentDataClass.PRIVATE),
        allowedSourceIds = setOf("source-1"),
        policyVersion = policyVersion,
        expiresAtMillis = expiresAtMillis
    )

    private fun runGrant(
        grantId: String,
        origin: String = "https://api.example.com",
        policyVersion: Long = 3L,
        transport: AgentEgressTransport = AgentEgressTransport.CLOUD_PROVIDER
    ) = AgentEgressGrant(
        grantId = grantId,
        runId = "run-1",
        taskId = null,
        transport = transport,
        origin = origin,
        allowedDataClasses = setOf(AgentDataClass.PRIVATE),
        allowedSourceIds = setOf("source-1"),
        policyVersion = policyVersion
    )

    private fun errorCode(outcome: AgentEgressGrantOutcome): String =
        (outcome as AgentEgressGrantOutcome.Rejected).error.code

    private class ContextReader(
        private val tasks: Map<String, AgentEgressGrantContext>,
        private val runs: Map<String, AgentEgressGrantContext>
    ) : AgentEgressGrantContextReader {
        override suspend fun read(taskId: String?, runId: String?): AgentEgressGrantContext? {
            if (runId != null) {
                val context = runs[runId] ?: return null
                if (taskId != null && taskId != context.taskId) return null
                return context
            }
            return taskId?.let(tasks::get)
        }
    }

    private object ImmediateTransactions : AgentTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private class FakeGrantDao : AgentGrantDao {
        val rows = linkedMapOf<String, AgentGrantEntity>()

        override suspend fun upsert(grant: AgentGrantEntity) {
            rows[grant.grantId] = grant
        }

        override suspend fun find(grantId: String): AgentGrantEntity? = rows[grantId]

        override suspend fun listActiveForTask(
            taskId: String,
            nowMillis: Long,
            limit: Int
        ): List<AgentGrantEntity> = rows.values
            .filter {
                it.taskId == taskId && it.revokedAt == null &&
                    (it.expiresAt == null || it.expiresAt!! > nowMillis)
            }
            .sortedByDescending { it.grantedAt }
            .take(limit)

        override suspend fun listActiveEgressForTask(
            taskId: String,
            nowMillis: Long,
            limit: Int
        ): List<AgentGrantEntity> = rows.values
            .filter {
                it.taskId == taskId && it.scopeType == AgentGrantScope.EGRESS_PROVIDER.name &&
                    it.revokedAt == null && (it.expiresAt == null || it.expiresAt!! > nowMillis)
            }
            .sortedByDescending { it.grantedAt }
            .take(limit)

        override suspend fun revokeTaskScope(
            taskId: String,
            scopeType: String,
            revokedAt: Long,
            reason: String
        ): Int {
            val targets = rows.values.filter {
                it.taskId == taskId && it.scopeType == scopeType &&
                    it.runId == null && it.revokedAt == null
            }
            targets.forEach {
                rows[it.grantId] = it.copy(revokedAt = revokedAt, revokedReason = reason)
            }
            return targets.size
        }

        override suspend fun revokeCas(
            grantId: String,
            scopeType: String,
            revokedAt: Long,
            reason: String
        ): Int {
            val row = rows[grantId] ?: return 0
            if (row.scopeType != scopeType || row.revokedAt != null) return 0
            rows[grantId] = row.copy(revokedAt = revokedAt, revokedReason = reason)
            return 1
        }

        override suspend fun deleteForTask(taskId: String): Int {
            val ids = rows.values.filter { it.taskId == taskId }.map { it.grantId }
            ids.forEach(rows::remove)
            return ids.size
        }
    }
}

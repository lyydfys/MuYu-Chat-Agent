package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-shot approval guarantee must hold under concurrent double-clicks/retries: exactly one
 * consume wins and therefore exactly one side effect may happen (architecture 8.2).
 */
class AgentApprovalConcurrencyTest {
    @Test
    fun oneHundredConcurrentApproveClicksExecuteExactlyOnce() {
        val store = InMemoryAgentApprovalStore()
        val ids = AtomicInteger()
        val broker = AgentPermissionBroker(store, approvalIdFactory = { "approval-${ids.incrementAndGet()}" })
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val frozen = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"artifacts/report.md","content":"hello","expectedOldHash":null}"""
        )
        val candidate = AgentSecurityFixtures.candidate(frozen, descriptor)

        val approvalId = runBlocking {
            val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
            assertTrue(decision is AgentPermissionDecision.NeedsApproval)
            (decision as AgentPermissionDecision.NeedsApproval).approval.approvalId
        }

        val executions = AtomicInteger()
        val authorized = AtomicInteger()
        val notExecuted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val start = CountDownLatch(1)
            val tasks = (1..100).map {
                Callable {
                    start.await()
                    runBlocking {
                        when (
                            val authorization = broker.authorize(
                                candidate, approvalId, AgentSecurityFixtures.policy(), null, NOW + 2_000L
                            )
                        ) {
                            is AgentExecutionAuthorization.Authorized -> {
                                authorized.incrementAndGet()
                                // Simulated executor side effect: only reachable once.
                                executions.incrementAndGet()
                            }
                            is AgentExecutionAuthorization.Denied -> {
                                assertEquals(
                                    com.muyuchat.core.agent.contract.AgentToolResultStatus.NOT_EXECUTED,
                                    authorization.toolStatus
                                )
                                notExecuted.incrementAndGet()
                            }
                        }
                    }
                }
            }
            val futures = tasks.map { pool.submit(it) }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("exactly one thread may be authorized", 1, authorized.get())
        assertEquals("executor must run exactly once", 1, executions.get())
        assertEquals("all other clicks must be NOT_EXECUTED", 99, notExecuted.get())
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approvalId))
    }

    @Test
    fun oneHundredConcurrentAuthorizeCallsConsumeOnce() {
        val store = InMemoryAgentApprovalStore()
        var nextId = 0
        val broker = AgentPermissionBroker(store, approvalIdFactory = { "approval-${++nextId}" })
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val frozen = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"artifacts/report.md","content":"hello","expectedOldHash":null}"""
        )
        val candidate = AgentSecurityFixtures.candidate(frozen, descriptor)
        val approvalId = runBlocking {
            val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
            (decision as AgentPermissionDecision.NeedsApproval).approval.approvalId
        }

        val authorized = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val start = CountDownLatch(1)
            val futures = (1..100).map {
                pool.submit(
                    Callable {
                        start.await()
                        runBlocking {
                            val authorization = broker.authorize(
                                candidate, approvalId, AgentSecurityFixtures.policy(), null, NOW + 2_000L
                            )
                            if (authorization is AgentExecutionAuthorization.Authorized) {
                                authorized.incrementAndGet()
                            }
                        }
                    }
                )
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, authorized.get())
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approvalId))
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}

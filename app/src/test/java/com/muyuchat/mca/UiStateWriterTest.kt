package com.muyuchat.mca

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real CAS behavior of field-limited capabilities, independent of production domain migration. */
class UiStateWriterTest {
    private data class PageFields(val page: Int)
    private data class RevisionFields(val revision: Long)

    @Test
    fun `an untrusted fields snapshot cannot replace foreign state`() {
        val before = MainUiState(input = "private draft", hubPage = 4, revision = 7L)
        val store = UiStateStore(before)
        val writer = store.writer(
            project = { it.toChatLoraSelectionFields() },
            merge = { current, fields -> fields.mergeInto(current) },
        )
        writer.update { fields ->
            fields.copy(snapshot = MainUiState(input = "injected", hubPage = 999), chatImageLoraSelections = null)
        }
        assertEquals(before.copy(chatImageLoraSelections = null), store.current)
    }

    @Test
    fun `restricted writer returns only the successful CAS result`() {
        val store = UiStateStore(MainUiState(hubPage = 0, revision = 3L, input = "keep"))
        val writer = pageWriter(store).restrict(
            project = { it.page },
            merge = { parent, child -> parent.copy(page = child) },
        )
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val firstAttempt = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Int> {
                writer.updateWithResult { page ->
                    if (firstAttempt.compareAndSet(true, false)) {
                        entered.countDown()
                        assertTrue(resume.await(5, TimeUnit.SECONDS))
                    }
                    (page + 1) to page
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pageWriter(store).update { it.copy(page = 10) }
            revisionWriter(store).update { it.copy(revision = 9L) }
            resume.countDown()
            assertEquals(10, pending.get(5, TimeUnit.SECONDS))
            assertEquals(MainUiState(hubPage = 11, revision = 9L, input = "keep"), store.current)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `updateAndGet returns a committed projection instead of a later live read`() {
        val store = UiStateStore(MainUiState(hubPage = 4, input = "keep"))
        val writer = pageWriter(store)
        val committed = writer.updateAndGet { it.copy(page = 8) }
        writer.update { it.copy(page = 12) }
        assertEquals(8, committed.page)
        assertEquals(12, writer.current.hubPage)
        assertEquals("keep", store.current.input)
    }

    @Test
    fun `field-only reducers compose from the current projection`() {
        val store = UiStateStore(MainUiState(input = "keep", hubPage = 2, revision = 9L))
        val writer = pageWriter(store)
        writer.update { it.copy(page = it.page + 1) }
        writer.update { it.copy(page = it.page + 1) }
        assertEquals(MainUiState(input = "keep", hubPage = 4, revision = 9L), store.current)
    }

    @Test
    fun `a writer changes its fields and shares the authoritative read-only flow`() {
        val initial = MainUiState(input = "keep", hubPage = 2, revision = 9L)
        val store = UiStateStore(initial)
        val writer = pageWriter(store)

        writer.update { snapshot, fields ->
            assertSame(store.current, snapshot)
            assertEquals(snapshot.hubPage, fields.page)
            fields.copy(page = fields.page + 1)
        }

        assertEquals(initial.copy(hubPage = 3), store.current)
        assertSame(store.current, writer.current)
        assertSame(store.state, writer.state)
        val exposed: StateFlow<*> = writer.state
        assertFalse(exposed is MutableStateFlow<*>)
    }

    @Test
    fun `status command and field updates preserve each other's state`() {
        val store = UiStateStore(MainUiState(input = "keep", hubPage = 2, revision = 9L))
        val writer = pageWriter(store)
        val status: UiStatusWriter = writer
        status.showStatus("working")
        writer.update { _, fields -> fields.copy(page = fields.page + 1) }
        assertEquals("working", store.current.statusMessage)
        assertEquals(3, store.current.hubPage)
        status.showStatus(null)
        assertEquals(MainUiState(input = "keep", hubPage = 3, revision = 9L), store.current)
    }

    @Test
    fun `CAS retry projects the new domain fields and preserves another domain's update`() {
        val store = UiStateStore(MainUiState(hubPage = 0, revision = 0L))
        val projections = AtomicInteger()
        val projectedPages = Collections.synchronizedList(mutableListOf<Int>())
        val first = store.writer(
            project = { snapshot ->
                projections.incrementAndGet()
                projectedPages += snapshot.hubPage
                PageFields(snapshot.hubPage)
            },
            merge = { snapshot, fields -> snapshot.copy(hubPage = fields.page) },
        )
        val second = pageWriter(store)
        val revisions = revisionWriter(store)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val firstAttempt = AtomicBoolean(true)
        val seenRevisions = Collections.synchronizedList(mutableListOf<Long?>())
        val reducedPages = Collections.synchronizedList(mutableListOf<Int>())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit {
                first.update { snapshot, fields ->
                    seenRevisions += snapshot.revision
                    reducedPages += fields.page
                    if (firstAttempt.compareAndSet(true, false)) {
                        entered.countDown()
                        assertTrue("test did not release the first CAS attempt", resume.await(5, TimeUnit.SECONDS))
                    }
                    fields.copy(page = fields.page + 1)
                }
            }
            assertTrue("writer did not start", entered.await(5, TimeUnit.SECONDS))
            second.update { _, fields -> fields.copy(page = fields.page + 10) }
            revisions.update { _, fields -> fields.copy(revision = fields.revision + 1) }
            resume.countDown()
            pending.get(5, TimeUnit.SECONDS)

            assertEquals(11, store.current.hubPage)
            assertEquals(1L, store.current.revision)
            assertEquals(listOf(0L, 1L), seenRevisions.toList())
            assertEquals("the retry must reduce the current domain fields", listOf(0, 10), reducedPages.toList())
            // Each CAS attempt projects its input; a successful commit additionally projects the
            // exact committed state for updateAndGet/restricted writers. This final projection must
            // not be mistaken for a third reducer attempt or a later read of the live store.
            assertEquals(listOf(0, 10, 11), projectedPages.toList())
            assertEquals("two CAS attempts plus the committed-result projection", 3, projections.get())
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent domain writers do not lose increments in either domain`() {
        val store = UiStateStore(MainUiState(input = "keep", hubPage = 0, revision = 0L))
        val pages = pageWriter(store)
        val revisions = revisionWriter(store)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val pending = (0 until 4).map { index ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(500) {
                        if (index < 2) pages.update { _, fields -> fields.copy(page = fields.page + 1) }
                        else revisions.update { _, fields -> fields.copy(revision = fields.revision + 1) }
                    }
                }
            }
            start.countDown()
            pending.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(MainUiState(input = "keep", hubPage = 1000, revision = 1000L), store.current)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a failing reducer propagates its error without publishing a partial state`() {
        val initial = MainUiState(input = "keep", hubPage = 4, revision = 9L)
        val store = UiStateStore(initial)
        val writer = pageWriter(store)
        val failure = IllegalStateException("failed domain transition")

        val actual = assertThrows(IllegalStateException::class.java) {
            writer.update { _, _ -> throw failure }
        }

        assertSame(failure, actual)
        assertSame(initial, store.current)
        assertSame(initial, writer.state.value)
    }

    private fun pageWriter(store: UiStateStore): UiStateWriter<PageFields> = store.writer(
        project = { PageFields(it.hubPage) },
        merge = { snapshot, fields -> snapshot.copy(hubPage = fields.page) },
    )

    private fun revisionWriter(store: UiStateStore): UiStateWriter<RevisionFields> = store.writer(
        project = { RevisionFields(it.revision ?: 0L) },
        merge = { snapshot, fields -> snapshot.copy(revision = fields.revision) },
    )
}

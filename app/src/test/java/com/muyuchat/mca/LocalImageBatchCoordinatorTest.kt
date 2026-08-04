package com.muyuchat.mca

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageBatchCoordinatorTest {
    @Test
    fun `qnn and mnn split eight outputs into strict sequential single image requests`() {
        listOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION).forEach { runtime ->
            val plan = planLocalImageBatch(
                parentRequestId = "parent",
                runtime = runtime,
                requestedOptions = LocalImageGenerationOptions(batchCount = 8, seed = 41)
            )

            assertEquals(LocalImageBatchExecutionMode.COORDINATOR_SEQUENTIAL, plan.executionMode)
            assertEquals(8, plan.requests.size)
            assertEquals((41..48).toList(), plan.requests.map { it.options.seed })
            assertTrue(plan.requests.all { it.options.batchCount == 1 })
            assertEquals("parent-batch-007", plan.requests.last().requestId)
        }
    }

    @Test
    fun `stable diffusion keeps one physical native batch with per output lineage`() {
        val plan = planLocalImageBatch(
            parentRequestId = "stable-parent",
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            requestedOptions = LocalImageGenerationOptions(batchCount = 8, seed = 100)
        )

        assertEquals(LocalImageBatchExecutionMode.NATIVE_BATCH, plan.executionMode)
        assertEquals(1, plan.requests.size)
        assertEquals(8, plan.requests.single().options.batchCount)
        assertEquals((100..107).toList(), plan.requests.single().outputLineages.map { it.seed })
    }

    @Test
    fun `random seed is resolved once in the safe parent range`() {
        var calls = 0
        var observedMaximum = -1
        val plan = planLocalImageBatch(
            parentRequestId = "random-parent",
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            requestedOptions = LocalImageGenerationOptions(batchCount = 8, seed = -1),
            randomBaseSeed = { maximum ->
                calls += 1
                observedMaximum = maximum
                maximum
            }
        )

        assertEquals(1, calls)
        assertEquals(Int.MAX_VALUE - 7, observedMaximum)
        assertEquals(Int.MAX_VALUE - 7, plan.baseSeed)
        assertEquals(Int.MAX_VALUE, plan.requests.last().options.seed)
    }

    @Test
    fun `seed overflow is rejected before execution`() {
        assertThrows(ArithmeticException::class.java) {
            planLocalImageBatch(
                parentRequestId = "overflow",
                runtime = LocalImageRuntime.QNN_HTP,
                requestedOptions = LocalImageGenerationOptions(batchCount = 2, seed = Int.MAX_VALUE)
            )
        }
    }

    @Test
    fun `failure cleans every candidate and never reaches later child`() = runBlocking {
        val plan = planLocalImageBatch(
            parentRequestId = "failure",
            runtime = LocalImageRuntime.QNN_HTP,
            requestedOptions = LocalImageGenerationOptions(batchCount = 4, seed = 10)
        )
        val executed = mutableListOf<Int>()
        val cleaned = mutableListOf<Int>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                executeLocalImageBatchPlan(
                    plan = plan,
                    cancellationRequested = { false },
                    execute = { child ->
                        executed += child.outputOffset
                        if (child.outputOffset == 2) error("third child failed")
                        listOf(child.outputOffset)
                    },
                    cleanup = { cleaned += it }
                )
            }
        }

        assertEquals(listOf(0, 1, 2), executed)
        assertEquals(listOf(0, 1), cleaned)
    }

    @Test
    fun `cancellation between children cleans candidates and prevents next execution`() = runBlocking {
        val plan = planLocalImageBatch(
            parentRequestId = "cancel",
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            requestedOptions = LocalImageGenerationOptions(batchCount = 3, seed = 20)
        )
        var cancelled = false
        val executed = mutableListOf<Int>()
        val cleaned = mutableListOf<Int>()

        assertThrows(LocalImageWorkerCancelledException::class.java) {
            runBlocking {
                executeLocalImageBatchPlan(
                    plan = plan,
                    cancellationRequested = { cancelled },
                    execute = { child ->
                        executed += child.outputOffset
                        cancelled = true
                        listOf(child.outputOffset)
                    },
                    cleanup = { cleaned += it }
                )
            }
        }

        assertEquals(listOf(0), executed)
        assertEquals(listOf(0), cleaned)
    }

    @Test
    fun `successful batch returns every candidate and never cleans`() = runBlocking {
        val plan = planLocalImageBatch(
            parentRequestId = "success",
            runtime = LocalImageRuntime.QNN_HTP,
            requestedOptions = LocalImageGenerationOptions(batchCount = 3, seed = 30)
        )
        var cleanupCalls = 0

        val outputs = executeLocalImageBatchPlan(
            plan = plan,
            cancellationRequested = { false },
            execute = { listOf(it.outputOffset) },
            cleanup = { cleanupCalls += 1 }
        )

        assertEquals(listOf(0, 1, 2), outputs)
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun `preview revisions remain monotonic when child revisions restart`() {
        val revisions = listOf(
            coordinatedPreviewRevision(0, 1),
            coordinatedPreviewRevision(0, 2),
            coordinatedPreviewRevision(1, 1),
            coordinatedPreviewRevision(1, 2)
        )

        assertEquals(revisions.sorted(), revisions)
        assertTrue(revisions.zipWithNext().all { (left, right) -> right > left })
    }

    @Test
    fun `sequential child progress maps to the parent image and step range`() {
        val plan = planLocalImageBatch(
            parentRequestId = "progress",
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            requestedOptions = LocalImageGenerationOptions(batchCount = 3, seed = 50)
        )
        val mapped = plan.requests[1].parentProgress(
            LocalImageProgress(
                phase = "sampling",
                message = "sampling",
                step = 2,
                steps = 4,
                elapsedMs = 1_000L,
                secondsPerStep = 0.5,
                threads = 4,
                width = 512,
                height = 512,
                cancelRequested = false,
                previewRevision = 3L
            )
        )

        assertEquals("第 2/3 张 · sampling", mapped.message)
        assertEquals(6, mapped.step)
        assertEquals(12, mapped.steps)
        assertEquals((1L shl 32) or 3L, mapped.previewRevision)
    }
}

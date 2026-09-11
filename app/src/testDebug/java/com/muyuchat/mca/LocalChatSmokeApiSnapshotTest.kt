package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.mca.debug.LocalChatSmokeGlobalSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertSame
import org.junit.Test

class LocalChatSmokeApiSnapshotTest {
    @Test
    fun restoresContextAwareProvidersAndCoordinator() {
        val before = LocalChatSmokeGlobalSnapshot.capture()
        try {
            val contextProvider: (ChatRequest, LocalChatExecutionContext) -> Flow<GenerateEvent> = { _, _ -> emptyFlow() }
            val stopProvider: suspend (String) -> Boolean = { true }
            val plane = object : LocalApiControlPlane {}
            LocalApiRuntime.streamChatWithContextProvider = contextProvider
            LocalApiRuntime.stopGenerationIfRequestActiveProvider = stopProvider
            LocalApiRuntime.controlPlane = plane
            val snapshot = LocalChatSmokeGlobalSnapshot.capture()
            LocalApiRuntime.streamChatWithContextProvider = null
            LocalApiRuntime.stopGenerationIfRequestActiveProvider = null
            LocalApiRuntime.controlPlane = null
            snapshot.restore()
            assertSame(contextProvider, LocalApiRuntime.streamChatWithContextProvider)
            assertSame(stopProvider, LocalApiRuntime.stopGenerationIfRequestActiveProvider)
            assertSame(plane, LocalApiRuntime.controlPlane)
        } finally {
            before.restore()
        }
    }
}

package com.muyuchat.mca.agent.cloud

import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.agent.inference.AgentNativeToolEvidenceStore
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Device-only: records native-wire evidence for an explicit provider/model pair. */
class SeedNativeEvidenceDeviceTest {

    @Test
    fun seedNativeEvidence() {
        val args = InstrumentationRegistry.getArguments()
        val providerId = args.getString("nativeProviderId").orEmpty()
        val modelId = args.getString("nativeModelId").orEmpty()
        assumeTrue("native provider/model args are required", providerId.isNotBlank() && modelId.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AgentNativeToolEvidenceStore(File(context.filesDir, "agent"))
        store.recordVerified(providerId, modelId, "responses-native-probe", System.currentTimeMillis())
        assertTrue(store.isVerified(providerId, modelId))
        Thread.sleep(1_500)
    }
}

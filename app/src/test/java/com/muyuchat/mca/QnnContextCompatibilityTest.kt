package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.QnnRuntimeProfileSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnContextCompatibilityTest {
    @Test
    fun contextBuildIdExposesStableQnnSdkPrefix() {
        assertEquals(
            "2.28.0",
            qnnSdkVersionFromContextBuildId("v2.28.0.241029232508_102474")
        )
        assertEquals(
            "2.45.0",
            qnnSdkVersionFromContextBuildId("QNN context v2.45.0.260326154327_123")
        )
    }

    @Test
    fun sdkComparisonUsesMajorAndMinorAndKeepsUnknownSeparate() {
        assertTrue(qnnContextSdkMatchesDeclared("2.28.0", "2.28") == true)
        assertTrue(qnnContextSdkMatchesDeclared("2.45.0", "2.45.0.260326154327") == true)
        assertFalse(qnnContextSdkMatchesDeclared("2.45.0", "2.28") == true)
        assertNull(qnnContextSdkMatchesDeclared(null, "2.28"))
        assertNull(qnnContextSdkMatchesDeclared("", "2.28"))
    }

    @Test
    fun contextIdentityRetainsSocHtpSdkAndBuildId() {
        val identity = qnnContextTargetIdentity(
            socModel = 57,
            socVersion = "SM8650",
            buildId = "v2.28.0.241029232508_102474",
            htpArch = QnnRuntimeProfileSelector.htpArchVersionForSocModel(57)
        )

        assertEquals(57, identity.socModel)
        assertEquals("SM8650", identity.socVersion)
        assertEquals(75, identity.htpArch)
        assertEquals("2.28.0", identity.sdkVersion)
        assertEquals("v2.28.0.241029232508_102474", identity.buildId)
    }

    @Test
    fun unknownBuildIdDoesNotClaimAnSdkMatch() {
        assertNull(qnnSdkVersionFromContextBuildId("legacy-context-without-version"))
        val identity = qnnContextTargetIdentity(0, "", "", null)
        assertNull(identity.socModel)
        assertNull(identity.socVersion)
        assertNull(identity.htpArch)
        assertNull(identity.sdkVersion)
        assertNull(identity.buildId)
    }
}

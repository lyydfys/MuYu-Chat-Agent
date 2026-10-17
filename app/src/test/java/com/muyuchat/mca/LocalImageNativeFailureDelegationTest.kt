package com.muyuchat.mca

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Regression contract for the image-provider ports' Android-boundary logger.
 *
 * The port method has the same name as the file's private top-level logger. An
 * unqualified call therefore dispatches back to the anonymous port and turns
 * the original native error into StackOverflowError (reported as "stack size
 * 1035/1037KB"). Exercise the actual port without Android/native initialization.
 */
class LocalImageNativeFailureDelegationTest {
    @Test
    fun generationPortPreservesTheNativeFailure() {
        assertNativeFailureSurvivesLogging("localImageGenerationPort")
    }

    @Test
    fun upscalePortPreservesTheNativeFailure() {
        assertNativeFailureSurvivesLogging("localImageUpscalePort")
    }

    private fun assertNativeFailureSurvivesLogging(portName: String) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val providerType = Class.forName("com.muyuchat.mca.LocalImageProvider")
        val failure = JSONObject()
            .put("ok", false)
            .put("errorCode", "NATIVE_PROBE_FAILURE")
            .put("error", "sentinel native failure")

        // Bypass Context/native setup; the diagnostic callback has no dependency on provider state.
        // Use the real anonymous-port constructor so the captured outer reference is valid.
        val portType = Class.forName("com.muyuchat.mca.LocalImageProvider\$$portName\$1")
        val provider = allocateInstance.invoke(unsafe, providerType)
        val constructor = portType.declaredConstructors.single().apply { isAccessible = true }
        val port = constructor.newInstance(provider)
        val logger = portType.getMethod(
            "logLocalImageNativeFailure",
            JSONObject::class.java,
            String::class.java
        ).apply { isAccessible = true }
        val mapper = Class.forName("com.muyuchat.mca.LocalImageProviderKt").getMethod(
            "throwLocalImageNativeFailure",
            JSONObject::class.java,
            String::class.java
        )
        val thrown = try {
            logger.invoke(port, failure, "fallback native failure")
            mapper.invoke(null, failure, "fallback native failure")
            null
        } catch (error: InvocationTargetException) {
            error.cause
        }

        assertTrue(
            "$portName must preserve the native error instead of overflowing: $thrown",
            thrown?.javaClass?.name == "com.muyuchat.mca.LocalImageProductContractException"
        )
        val nativeError = requireNotNull(thrown)
        assertEquals("native_probe_failure", nativeError.javaClass.getMethod("getCode").invoke(nativeError))
        assertEquals("sentinel native failure", nativeError.message)
    }
}
